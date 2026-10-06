package io.github.mgeladzerezo.sockethttp.bench;

import io.github.mgeladzerezo.sockethttp.metrics.Histogram;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A closed-loop HTTP/1.1 load generator: each connection sends a request, reads the complete
 * response, and only then sends the next one, so offered load is limited by response time (the
 * usual wrk/ab behaviour; it cannot show queueing delay beyond what the connections create).
 *
 * <p>Every response is checked (status 200, the expected body length), and anything else is
 * counted as an error rather than as a fast request: a server that answers 503 immediately
 * must not look quick. One platform thread per connection, one {@link Histogram} per
 * connection, merged at the end, so recording needs no synchronisation.
 */
public final class LoadGenerator {

    /** Outcome of one measured run. */
    public record Result(long requests, long errors, double seconds, Histogram latencyMicros,
                         long connectFailures, long connects) {
        public double requestsPerSecond() {
            return requests / seconds;
        }
    }

    private final String host;
    private final int port;
    private final String path;
    private final int expectedBodyBytes;

    public LoadGenerator(String host, int port, String path, int expectedBodyBytes) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.expectedBodyBytes = expectedBodyBytes;
    }

    /**
     * @param connections number of concurrent keep-alive connections
     * @param warmup      traffic at full load before measuring starts; its results are discarded
     * @param measure     length of the measured window
     */
    public Result run(int connections, java.time.Duration warmup, java.time.Duration measure) throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        Window window = new Window();
        CountDownLatch ready = new CountDownLatch(connections);
        List<Worker> workers = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            Worker worker = new Worker(window, ready, stop);
            workers.add(worker);
            Thread t = new Thread(worker, "load-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        // Starting a platform thread can take tens of milliseconds on a busy Windows machine, so
        // the clock starts only once every worker is running and connected. Without this the
        // late starters were still being created when the measured window had already begun.
        if (!ready.await(60, TimeUnit.SECONDS)) {
            stop.set(true);
            throw new IllegalStateException("load workers did not come up within 60 s");
        }
        long measureFrom = System.nanoTime() + warmup.toNanos();
        long end = measureFrom + measure.toNanos();
        window.measureFrom = measureFrom;
        window.end = end;
        window.go.countDown();
        for (Thread t : threads) {
            t.join(java.time.Duration.ofNanos(Math.max(1, end - System.nanoTime())).plusSeconds(15).toMillis());
        }
        stop.set(true);
        Histogram merged = new Histogram();
        long requests = 0;
        long errors = 0;
        long connectFailures = 0;
        long connects = 0;
        for (Worker w : workers) {
            merged.add(w.histogram);
            requests += w.requests;
            errors += w.errors;
            connectFailures += w.connectFailures;
            connects += w.connects;
        }
        return new Result(requests, errors, measure.toNanos() / 1e9, merged, connectFailures, connects);
    }

    /** The measured window, published to the workers once all of them are ready. */
    private static final class Window {
        volatile long measureFrom;
        volatile long end;
        final CountDownLatch go = new CountDownLatch(1);
    }

    private final class Worker implements Runnable {
        private final Window window;
        private final CountDownLatch ready;
        private final AtomicBoolean stop;
        final Histogram histogram = new Histogram();
        long requests;
        long errors;
        long connectFailures;
        long connects;

        private final byte[] request = ("GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port
                + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        private final byte[] buffer = new byte[64 * 1024];

        Worker(Window window, CountDownLatch ready, AtomicBoolean stop) {
            this.window = window;
            this.ready = ready;
            this.stop = stop;
        }

        @Override
        public void run() {
            Socket socket = null;
            try {
                socket = connect();
                ready.countDown();
                window.go.await();
                long measureFrom = window.measureFrom;
                long end = window.end;
                while (!stop.get() && System.nanoTime() < end) {
                    if (socket == null) {
                        socket = connect();
                        if (socket == null) {
                            Thread.sleep(50);
                            continue;
                        }
                    }
                    long t0 = System.nanoTime();
                    boolean keepAlive;
                    try {
                        keepAlive = exchange(socket);
                    } catch (IOException e) {
                        if (t0 >= measureFrom) {
                            errors++;
                        }
                        closeQuietly(socket);
                        socket = null;
                        continue;
                    }
                    long t1 = System.nanoTime();
                    if (t0 >= measureFrom && t1 <= end) {
                        histogram.record((t1 - t0) / 1_000);
                        requests++;
                    }
                    if (!keepAlive) {
                        closeQuietly(socket);
                        socket = null;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                closeQuietly(socket);
            }
        }

        /** A connected socket, or {@code null} (counted as a connect failure) if the server refused. */
        private Socket connect() {
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5_000);
                socket.connect(new InetSocketAddress(host, port), 5_000);
                connects++;
                return socket;
            } catch (IOException e) {
                connectFailures++;
                closeQuietly(socket);
                return null;
            }
        }

        /** Sends one request and consumes one response; returns whether the connection can be reused. */
        private boolean exchange(Socket socket) throws IOException {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(request);
            out.flush();

            // Read until the end of the header section, keeping any body bytes that came with it.
            int filled = 0;
            int headerEnd = -1;
            while (headerEnd < 0) {
                int n = in.read(buffer, filled, buffer.length - filled);
                if (n < 0) {
                    throw new IOException("connection closed before a complete response head");
                }
                filled += n;
                headerEnd = indexOfHeaderEnd(buffer, filled);
                if (headerEnd < 0 && filled == buffer.length) {
                    throw new IOException("response head too large");
                }
            }
            // Parsed on the bytes: the generator must not allocate per response, or its own
            // garbage collection becomes part of the measurement.
            if (!startsWith(buffer, 0, headerEnd, STATUS_200)) {
                throw new IOException("unexpected status: " + new String(buffer, 0, Math.min(headerEnd, 40), StandardCharsets.ISO_8859_1));
            }
            long length = -1;
            boolean chunked = false;
            boolean close = false;
            int lineStart = indexOfLf(buffer, 0, headerEnd) + 1;
            while (lineStart > 0 && lineStart < headerEnd) {
                int lineEnd = indexOfLf(buffer, lineStart, headerEnd);
                int end = lineEnd < 0 ? headerEnd : lineEnd - 1;
                if (startsWith(buffer, lineStart, end, CONTENT_LENGTH)) {
                    length = parseLong(buffer, lineStart + CONTENT_LENGTH.length, end);
                } else if (startsWith(buffer, lineStart, end, TRANSFER_ENCODING)) {
                    chunked = true;
                } else if (startsWith(buffer, lineStart, end, CONNECTION) && contains(buffer, lineStart, end, CLOSE)) {
                    close = true;
                }
                lineStart = lineEnd < 0 ? headerEnd : lineEnd + 1;
            }
            if (chunked || length < 0) {
                throw new IOException("benchmark endpoints must answer with Content-Length");
            }
            if (length != expectedBodyBytes) {
                throw new IOException("body is " + length + " bytes, expected " + expectedBodyBytes);
            }
            long have = filled - (headerEnd + 4);
            if (have > length) {
                throw new IOException("bytes beyond the response: the server pipelined or mis-framed");
            }
            long remaining = length - have;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new IOException("connection closed inside the body");
                }
                remaining -= n;
            }
            return !close;
        }


        private static final byte[] STATUS_200 = "HTTP/1.1 200".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] CONTENT_LENGTH = "content-length:".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] TRANSFER_ENCODING = "transfer-encoding:".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] CONNECTION = "connection:".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] CLOSE = "close".getBytes(StandardCharsets.US_ASCII);

        private static int indexOfLf(byte[] b, int from, int to) {
            for (int i = from; i < to; i++) {
                if (b[i] == '\n') {
                    return i;
                }
            }
            return -1;
        }

        /** Case-insensitive prefix test; {@code lowerCasePrefix} must already be lower case. */
        private static boolean startsWith(byte[] b, int from, int to, byte[] lowerCasePrefix) {
            if (to - from < lowerCasePrefix.length) {
                return false;
            }
            for (int i = 0; i < lowerCasePrefix.length; i++) {
                if (Character.toLowerCase((char) (b[from + i] & 0xFF)) != lowerCasePrefix[i]
                        && b[from + i] != lowerCasePrefix[i]) {
                    return false;
                }
            }
            return true;
        }

        private static boolean contains(byte[] b, int from, int to, byte[] lowerCaseWord) {
            for (int i = from; i + lowerCaseWord.length <= to; i++) {
                boolean match = true;
                for (int j = 0; j < lowerCaseWord.length && match; j++) {
                    match = Character.toLowerCase((char) (b[i + j] & 0xFF)) == lowerCaseWord[j];
                }
                if (match) {
                    return true;
                }
            }
            return false;
        }

        private static long parseLong(byte[] b, int from, int to) throws IOException {
            long value = 0;
            boolean digits = false;
            for (int i = from; i < to; i++) {
                int c = b[i];
                if (c >= '0' && c <= '9') {
                    value = value * 10 + (c - '0');
                    digits = true;
                } else if (c != ' ' && c != '\t') {
                    throw new IOException("bad Content-Length");
                }
            }
            if (!digits) {
                throw new IOException("bad Content-Length");
            }
            return value;
        }
        private static int indexOfHeaderEnd(byte[] b, int len) {
            for (int i = 3; i < len; i++) {
                if (b[i] == '\n' && b[i - 1] == '\r' && b[i - 2] == '\n' && b[i - 3] == '\r') {
                    return i - 3;
                }
            }
            return -1;
        }

        private static void closeQuietly(Socket s) {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing a socket that is already broken
                }
            }
        }
    }
}
