package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.concurrent.BoundedThreadPool;
import io.github.mgeladzerezo.sockethttp.concurrent.RejectionPolicy;
import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.http.HttpVersion;
import io.github.mgeladzerezo.sockethttp.parser.HttpParseException;
import io.github.mgeladzerezo.sockethttp.parser.RequestParser;
import io.github.mgeladzerezo.sockethttp.parser.RequestParser.Phase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * The event-loop model: one selector thread does every {@code accept} and every socket read
 * with non-blocking I/O; handlers and response writes run on a worker pool.
 *
 * <h2>Division of labour</h2>
 * <ul>
 *   <li><b>Loop thread.</b> Accepts, reads into one shared buffer, feeds each connection's
 *       incremental parser, enforces read deadlines, and writes the few tiny responses that
 *       need no handler (100 Continue, parse errors, 408, 503). All connection state except
 *       the write path is confined to this thread, so none of it needs locking.</li>
 *   <li><b>Workers.</b> Run the handler for one complete request and write its response. A
 *       worker writes straight to the non-blocking channel; if the socket's send buffer is
 *       full it asks the loop to watch for writability and parks until the loop wakes it or
 *       the write timeout passes. That gives handlers the same blocking write semantics as
 *       the other models, which is what lets streaming responses and SSE work unchanged.</li>
 * </ul>
 *
 * <h2>Ordering and backpressure</h2>
 * While a request is with a worker the loop stops reading from that connection (interest set
 * to 0). Pipelined requests therefore wait, already buffered or still in the kernel, and are
 * parsed only after the previous response is complete: responses cannot overtake each other,
 * and a client that pipelines faster than the server answers is throttled by its own TCP
 * window rather than by server memory. When every worker is busy and the queue is full, the
 * {@code BLOCK} policy parks the request on its (unread) connection until a worker frees up;
 * {@code ABORT} answers 503 from the loop.
 *
 * <h2>What an idle connection costs</h2>
 * A selection key, a parser with a 256-byte line buffer and, once it has served a request,
 * its output buffer. No thread and no read buffer: the loop reads every connection through
 * the same buffer, and only bytes of a pipelined request that arrived early are copied aside.
 */
final class NioEngine implements ConnectionEngine {

    private static final System.Logger LOG = System.getLogger(NioEngine.class.getName());

    private static final int SELECT_TIMEOUT_MILLIS = 100;
    private static final long SWEEP_INTERVAL_NANOS = 100_000_000L;
    private static final long LINGER_NANOS = 1_000_000_000L;
    private static final int MAX_ACCEPTS_PER_WAKEUP = 256;
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private enum State { READING, HANDLING, LINGERING, CLOSED }

    private final ServerSocketChannel server;
    private final ServerConfig config;
    private final ExchangeProcessor processor;
    private final BooleanSupplier stopping;
    private final long writeTimeoutNanos;

    private final Selector selector;
    private final BoundedThreadPool workers;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final CountDownLatch drained = new CountDownLatch(1);
    private final Thread loopThread;
    private volatile boolean running = true;

    // Confined to the loop thread.
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(64 * 1024);
    private final ArrayDeque<Connection> waitingForWorker = new ArrayDeque<>();
    private SelectionKey acceptKey;
    private int connectionCount;
    private boolean draining;

    NioEngine(ServerSocketChannel server, ServerConfig config, ExchangeProcessor processor, BooleanSupplier stopping) {
        this.server = server;
        this.config = config;
        this.processor = processor;
        this.stopping = stopping;
        this.writeTimeoutNanos = config.writeTimeout().toNanos();
        try {
            this.selector = Selector.open();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open a selector", e);
        }
        int core = Math.min(config.workerThreads(), Runtime.getRuntime().availableProcessors());
        // At least one queue slot: a request parked under BLOCK is re-offered when a worker
        // reports completion, which happens a moment before that worker is idle again.
        this.workers = new BoundedThreadPool(core, config.workerThreads(), Math.max(1, config.queueCapacity()),
                Duration.ofSeconds(60), RejectionPolicy.ABORT, BoundedThreadPool.namedDaemonFactory("sockethttp-nio-worker"));
        this.loopThread = Thread.ofPlatform().name("sockethttp-event-loop").daemon(false).unstarted(this::loop);
    }

    @Override
    public void start() {
        try {
            server.configureBlocking(false);
            acceptKey = server.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot register the listening socket", e);
        }
        loopThread.start();
    }

    /** Runs {@code task} on the loop thread. Safe from any thread. */
    private void execute(Runnable task) {
        tasks.add(task);
        try {
            selector.wakeup();
        } catch (ClosedSelectorException e) {
            // The engine has shut down; a straggling worker has nobody left to report to.
        }
    }

    // ------------------------------------------------------------------- loop

    private void loop() {
        long lastSweep = System.nanoTime();
        try {
            while (running) {
                selector.select(this::onKey, SELECT_TIMEOUT_MILLIS);
                Runnable task;
                while ((task = tasks.poll()) != null) {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "event loop task failed", e);
                    }
                }
                long now = System.nanoTime();
                if (now - lastSweep >= SWEEP_INTERVAL_NANOS) {
                    lastSweep = now;
                    sweepDeadlines(now);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "event loop failed", e);
        } finally {
            for (SelectionKey key : selector.keys()) {
                if (key.attachment() instanceof Connection connection) {
                    close(connection);
                }
            }
            drained.countDown();
        }
    }

    private void onKey(SelectionKey key) {
        if (key == acceptKey) {
            accept();
            return;
        }
        Connection connection = (Connection) key.attachment();
        try {
            if (key.isValid() && key.isWritable()) {
                // A worker is parked waiting for send-buffer space: stop watching and wake it.
                key.interestOps(0);
                connection.signalWritable();
            }
            if (key.isValid() && key.isReadable()) {
                onReadable(connection);
            }
        } catch (CancelledKeyException e) {
            close(connection);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "connection from {0} ended: {1}", connection.remote, e.toString());
            close(connection);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "connection from " + connection.remote + " failed", e);
            close(connection);
        }
    }

    private void accept() {
        try {
            for (int i = 0; i < MAX_ACCEPTS_PER_WAKEUP; i++) {
                if (connectionCount >= config.maxConnections()) {
                    // Connection limit: stop accepting. Clients queue in the listen backlog
                    // until close() re-enables OP_ACCEPT.
                    acceptKey.interestOps(0);
                    return;
                }
                SocketChannel channel = server.accept();
                if (channel == null) {
                    return;
                }
                try {
                    channel.configureBlocking(false);
                    channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    Connection connection = new Connection(channel);
                    connection.key = channel.register(selector, SelectionKey.OP_READ, connection);
                    connection.deadlines.connectionOpened(System.nanoTime());
                } catch (IOException e) {
                    // The client reset the connection between accept and setup.
                    channel.close();
                    continue;
                }
                connectionCount++;
                processor.metrics().connectionOpened();
            }
        } catch (ClosedChannelException e) {
            // the listening socket was closed by shutdown
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "accept failed", e);
        }
    }

    private void onReadable(Connection connection) throws IOException {
        readBuffer.clear();
        int n = connection.channel.read(readBuffer);
        if (n < 0) {
            close(connection);
            return;
        }
        if (connection.state != State.READING || n == 0) {
            // LINGERING: the bytes are discarded on purpose.
            return;
        }
        processor.metrics().bytesReceived(n);
        readBuffer.flip();
        parse(connection, readBuffer);
        if (readBuffer.hasRemaining() && connection.state == State.HANDLING) {
            // Bytes of the next pipelined request arrived with this one. Keep them for after
            // the response; the shared buffer is about to be reused for another connection.
            connection.leftover = ByteBuffer.allocate(readBuffer.remaining()).put(readBuffer).flip();
        }
    }

    /** Feeds {@code input} to the connection's parser and acts on the outcome. */
    private void parse(Connection connection, ByteBuffer input) {
        try {
            boolean complete = connection.parser.feed(input);
            connection.deadlines.advance(connection.parser.phase(), System.nanoTime());
            if (complete) {
                dispatch(connection);
                return;
            }
            if (connection.parser.awaitingContinue()) {
                connection.parser.continueSent();
                if (!writeFromLoop(connection, CONTINUE)) {
                    close(connection);
                    return;
                }
            }
            if (connection.parser.phase() == Phase.IDLE && stopping.getAsBoolean()) {
                close(connection);
                return;
            }
            connection.key.interestOps(SelectionKey.OP_READ);
        } catch (HttpParseException e) {
            reject(connection, connection.parser.versionSoFar(), e.status(), e.getMessage(), e.reference());
        } catch (CancelledKeyException e) {
            close(connection);
        }
    }

    /** Hands a complete request to a worker and stops reading from the connection meanwhile. */
    private void dispatch(Connection connection) {
        connection.state = State.HANDLING;
        connection.key.interestOps(0);
        connection.served++;
        connection.pendingRequest = connection.parser.request();
        if (!offerToWorkers(connection)) {
            if (config.overloadPolicy() == RejectionPolicy.BLOCK) {
                waitingForWorker.add(connection);
            } else {
                processor.metrics().connectionRejected();
                reject(connection, connection.pendingRequest.version(), HttpStatus.SERVICE_UNAVAILABLE,
                        "The server is at capacity. Try again shortly.", null);
            }
        }
    }

    private boolean offerToWorkers(Connection connection) {
        HttpRequest request = connection.pendingRequest;
        if (workers.tryExecute(() -> handle(connection, request))) {
            connection.pendingRequest = null;
            return true;
        }
        return false;
    }

    /** Runs on a worker thread. */
    private void handle(Connection connection, HttpRequest request) {
        boolean keepAlive = false;
        try {
            keepAlive = processor.process(connection.out, request, connection.remote, connection.served);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "connection from {0} ended: {1}", connection.remote, e.toString());
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "connection from " + connection.remote + " failed", t);
        } finally {
            boolean reuse = keepAlive;
            execute(() -> afterResponse(connection, reuse));
        }
    }

    /** Back on the loop thread once a worker has finished writing a response. */
    private void afterResponse(Connection connection, boolean keepAlive) {
        // A worker just finished: requests parked under the BLOCK policy get the next turn.
        while (!waitingForWorker.isEmpty()) {
            Connection waiting = waitingForWorker.peek();
            if (waiting.state == State.HANDLING && !offerToWorkers(waiting)) {
                break;
            }
            waitingForWorker.poll();
        }
        if (connection.state == State.CLOSED) {
            return;
        }
        try {
            if (!keepAlive) {
                if (connection.leftover != null || connection.channel.read(readBuffer.clear()) > 0) {
                    // Unread pipelined input: closing now would send a reset that can destroy
                    // the response still in flight. Half-close and drain instead.
                    linger(connection);
                } else {
                    close(connection);
                }
                return;
            }
            connection.state = State.READING;
            connection.parser.reset();
            connection.deadlines.awaitingNextRequest(System.nanoTime());
            ByteBuffer leftover = connection.leftover;
            if (leftover != null) {
                parse(connection, leftover);
                if (!leftover.hasRemaining()) {
                    connection.leftover = null;
                }
            } else if (stopping.getAsBoolean()) {
                close(connection);
            } else {
                connection.key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException | CancelledKeyException e) {
            close(connection);
        }
    }

    /**
     * Answers a request without a handler, from the loop thread. The response is a few hundred
     * bytes written to a connection whose send buffer is normally empty; if it does not fit in
     * one non-blocking write the connection is simply closed, since the loop must never block.
     */
    private void reject(Connection connection, HttpVersion version, int status, String message, String reference) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(256);
        try {
            processor.reject(new OutputBuffer(new CapturingTransport(captured, connection.remote)),
                    version, status, message, reference, connection.remote);
        } catch (IOException e) {
            close(connection);
            return;
        }
        if (writeFromLoop(connection, captured.toByteArray())) {
            linger(connection);
        } else {
            close(connection);
        }
    }

    private boolean writeFromLoop(Connection connection, byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            connection.channel.write(buffer);
            return !buffer.hasRemaining();
        } catch (IOException e) {
            return false;
        }
    }

    /** Half-closes and keeps discarding input for a moment, so the client can read our last response. */
    private void linger(Connection connection) {
        try {
            connection.channel.shutdownOutput();
            connection.state = State.LINGERING;
            connection.lingerDeadlineNanos = System.nanoTime() + LINGER_NANOS;
            connection.leftover = null;
            connection.key.interestOps(SelectionKey.OP_READ);
        } catch (IOException | CancelledKeyException e) {
            close(connection);
        }
    }

    private void sweepDeadlines(long now) {
        for (SelectionKey key : selector.keys()) {
            if (!(key.attachment() instanceof Connection connection)) {
                continue;
            }
            if (connection.state == State.LINGERING) {
                if (connection.lingerDeadlineNanos - now <= 0) {
                    close(connection);
                }
            } else if (connection.state == State.READING && connection.deadlines.expired(now)) {
                Phase phase = connection.parser.phase();
                if (phase == Phase.IDLE) {
                    // An idle persistent connection is closed without a response (RFC 9112 §9.5).
                    close(connection);
                } else {
                    String what = phase == Phase.HEAD ? "request header" : "request content";
                    reject(connection, connection.parser.versionSoFar(), HttpStatus.REQUEST_TIMEOUT,
                            "The " + what + " was not received in time.", "RFC 9110 §15.5.9");
                }
            }
        }
    }

    private void close(Connection connection) {
        if (connection.state == State.CLOSED) {
            return;
        }
        connection.state = State.CLOSED;
        connection.closed = true;
        connection.key.cancel();
        try {
            connection.channel.close();
        } catch (IOException e) {
            // nothing useful to do
        }
        connection.signalWritable();
        connectionCount--;
        processor.metrics().connectionClosed();
        if (draining) {
            if (connectionCount == 0) {
                drained.countDown();
            }
        } else if (acceptKey.isValid() && acceptKey.interestOps() == 0 && connectionCount < config.maxConnections()) {
            acceptKey.interestOps(SelectionKey.OP_ACCEPT);
        }
    }

    // --------------------------------------------------------------- shutdown

    @Override
    public boolean shutdown(Duration grace) {
        execute(() -> {
            draining = true;
            acceptKey.cancel();
            try {
                server.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "closing the listening socket failed", e);
            }
            for (SelectionKey key : selector.keys()) {
                if (key.attachment() instanceof Connection connection
                        && connection.state == State.READING && connection.parser.phase() == Phase.IDLE) {
                    close(connection);
                }
            }
            if (connectionCount == 0) {
                drained.countDown();
            }
        });
        boolean clean = false;
        try {
            clean = drained.await(grace.toNanos(), TimeUnit.NANOSECONDS);
            running = false;
            selector.wakeup();
            loopThread.join(5_000);
            workers.shutdown();
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                workers.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            running = false;
            selector.wakeup();
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Closed last: workers call wakeup() on it when they report completion.
        try {
            selector.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing the selector failed", e);
        }
        return clean;
    }

    // ------------------------------------------------------------- connection

    /**
     * Per-connection state. Fields without {@code volatile} are touched only by the loop
     * thread; the {@link Transport} methods are called only by the one worker that currently
     * holds the connection's request.
     */
    private final class Connection implements Transport {
        final SocketChannel channel;
        final SocketAddress remote;
        final RequestParser parser = new RequestParser(config.limits());
        final ReadDeadlines deadlines = new ReadDeadlines(config);
        final OutputBuffer out = new OutputBuffer(this);
        SelectionKey key;
        State state = State.READING;
        ByteBuffer leftover;
        HttpRequest pendingRequest;
        int served;
        long lingerDeadlineNanos;

        volatile boolean closed;
        private volatile Thread parkedWriter;
        private volatile boolean writable;

        Connection(SocketChannel channel) throws IOException {
            this.channel = channel;
            this.remote = channel.getRemoteAddress();
        }

        @Override
        public void write(ByteBuffer src) throws IOException {
            while (src.hasRemaining()) {
                if (channel.write(src) == 0) {
                    awaitWritable();
                }
            }
        }

        @Override
        public void transferFrom(FileChannel file, long position, long count) throws IOException {
            long sent = 0;
            while (sent < count) {
                long n = file.transferTo(position + sent, count - sent, channel);
                if (n > 0) {
                    sent += n;
                } else if (position + sent >= file.size()) {
                    throw new IOException("file shrank while it was being sent");
                } else {
                    awaitWritable();
                }
            }
        }

        /**
         * Parks the calling worker until the loop reports the socket writable. The flag is
         * cleared before the loop is asked to watch, and re-checked around every park, so a
         * wake-up that arrives before the park is not lost.
         */
        private void awaitWritable() throws IOException {
            writable = false;
            parkedWriter = Thread.currentThread();
            try {
                execute(() -> {
                    if (state == State.CLOSED) {
                        signalWritable();
                    } else {
                        try {
                            key.interestOps(SelectionKey.OP_WRITE);
                        } catch (CancelledKeyException e) {
                            signalWritable();
                        }
                    }
                });
                long deadline = System.nanoTime() + writeTimeoutNanos;
                while (!writable) {
                    if (closed) {
                        throw new ClosedChannelException();
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new SocketTimeoutException("client did not read the response within the write timeout");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("worker interrupted while writing");
                    }
                    LockSupport.parkNanos(this, remaining);
                }
                if (closed) {
                    throw new ClosedChannelException();
                }
            } finally {
                parkedWriter = null;
            }
        }

        void signalWritable() {
            writable = true;
            Thread writer = parkedWriter;
            if (writer != null) {
                LockSupport.unpark(writer);
            }
        }

        @Override
        public SocketAddress remoteAddress() {
            return remote;
        }
    }

    /** Collects a response in memory so the loop can send it with one non-blocking write. */
    private record CapturingTransport(ByteArrayOutputStream sink, SocketAddress remote) implements Transport {

        @Override
        public void write(ByteBuffer src) {
            sink.write(src.array(), src.arrayOffset() + src.position(), src.remaining());
            src.position(src.limit());
        }

        @Override
        public void transferFrom(FileChannel file, long position, long count) {
            throw new UnsupportedOperationException("error responses have no file content");
        }

        @Override
        public SocketAddress remoteAddress() {
            return remote;
        }
    }
}
