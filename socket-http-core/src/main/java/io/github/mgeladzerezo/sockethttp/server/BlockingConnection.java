package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.parser.HttpParseException;
import io.github.mgeladzerezo.sockethttp.parser.RequestParser;
import io.github.mgeladzerezo.sockethttp.parser.RequestParser.Phase;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One client connection served by one thread with blocking I/O, for as long as the connection
 * lives. Used by the thread-pool and the virtual-thread models; which kind of thread runs
 * {@link #run()} is the only difference between them.
 *
 * <p>The loop is the textbook one: read bytes, feed the parser, and when it reports a complete
 * request hand it to the {@link ExchangeProcessor}; repeat while the connection is persistent.
 * Bytes left in the read buffer after a request are the start of the next one, which is all
 * that pipelining needs: responses come out in request order because nothing else can write
 * to this connection until the current response is finished.
 */
final class BlockingConnection implements Runnable, Transport {

    private static final System.Logger LOG = System.getLogger(BlockingConnection.class.getName());

    /** Waiting for the first byte of a request; a graceful shutdown may close it. */
    private static final int IDLE = 0;
    /** Reading or answering a request; a graceful shutdown lets it finish. */
    private static final int BUSY = 1;
    private static final int CLOSED = 2;

    private static final int READ_BUFFER_SIZE = 16 * 1024;
    /** Largest piece handed to one write call, so the write watchdog measures progress. */
    private static final int WRITE_SLICE = 256 * 1024;
    private static final int LINGER_MILLIS = 1_000;

    private final BlockingEngine engine;
    private final SocketChannel channel;
    private final Socket socket;
    private final SocketAddress remote;
    private final ServerConfig config;
    private final ExchangeProcessor processor;

    private final AtomicInteger state = new AtomicInteger(IDLE);
    private final RequestParser parser;
    private final ReadDeadlines deadlines;
    private final OutputBuffer out = new OutputBuffer(this);
    private final byte[] readArray = new byte[READ_BUFFER_SIZE];
    private final ByteBuffer readBuffer = ByteBuffer.wrap(readArray).limit(0);
    private InputStream in;
    /** The thread serving this connection, once it has started. */
    private volatile Thread servingThread;

    /** {@code System.nanoTime()} when the current write call began, or 0 when not writing. */
    private volatile long writeStartedNanos;

    BlockingConnection(BlockingEngine engine, SocketChannel channel, ServerConfig config, ExchangeProcessor processor) {
        this.engine = engine;
        this.channel = channel;
        this.socket = channel.socket();
        this.remote = socket.getRemoteSocketAddress();
        this.config = config;
        this.processor = processor;
        this.parser = new RequestParser(config.limits());
        this.deadlines = new ReadDeadlines(config);
        this.deadlines.connectionOpened(System.nanoTime());
    }

    @Override
    public void run() {
        servingThread = Thread.currentThread();
        try {
            if (state.get() != CLOSED) {
                in = socket.getInputStream();
                serve();
            }
        } catch (IOException e) {
            // The client went away or a write stalled; there is nobody left to tell.
            LOG.log(System.Logger.Level.DEBUG, "connection from {0} ended: {1}", remote, e.toString());
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "connection from " + remote + " failed", t);
        } finally {
            servingThread = null;
            close();
        }
    }

    private void serve() throws IOException {
        int served = 0;
        while (true) {
            HttpRequest request;
            try {
                request = readRequest();
            } catch (HttpParseException e) {
                processor.reject(out, parser.versionSoFar(), e.status(), e.getMessage(), e.reference(), remote);
                lingeringClose();
                return;
            }
            if (request == null) {
                return;
            }
            served++;
            boolean keepAlive = processor.process(out, request, remote, served);
            if (!keepAlive) {
                if (readBuffer.hasRemaining() || in.available() > 0) {
                    // Unread input (pipelined requests we will not serve) would make close()
                    // send a reset that can destroy the response still in flight.
                    lingeringClose();
                }
                return;
            }
            parser.reset();
            deadlines.awaitingNextRequest(System.nanoTime());
        }
    }

    /**
     * Reads until the parser has a complete request.
     *
     * @return the request, or {@code null} when the connection should simply be closed: the
     *         client closed it, it idled out, or the server is shutting down
     */
    private HttpRequest readRequest() throws IOException, HttpParseException {
        while (true) {
            if (readBuffer.hasRemaining()) {
                if (state.get() == IDLE && !state.compareAndSet(IDLE, BUSY)) {
                    return null;
                }
                boolean complete = parser.feed(readBuffer);
                long now = System.nanoTime();
                deadlines.advance(parser.phase(), now);
                if (complete) {
                    return parser.request();
                }
                if (parser.awaitingContinue()) {
                    ResponseWriter.writeContinue(out);
                    parser.continueSent();
                }
            }
            if (parser.phase() == Phase.IDLE) {
                // Nothing of the next request has arrived. Publish that before looking at the
                // stop flag: shutdown sets the flag first and then closes IDLE connections, so
                // one of the two sides is guaranteed to see the other.
                state.compareAndSet(BUSY, IDLE);
                if (engine.isStopping()) {
                    return null;
                }
            }
            long remainingNanos = deadlines.remainingNanos(System.nanoTime());
            if (remainingNanos <= 0) {
                return timedOut();
            }
            socket.setSoTimeout(Math.clamp(remainingNanos / 1_000_000 + 1, 1, Integer.MAX_VALUE));
            int n;
            try {
                n = in.read(readArray, 0, readArray.length);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (n < 0) {
                return null;
            }
            processor.metrics().bytesReceived(n);
            readBuffer.position(0).limit(n);
        }
    }

    private HttpRequest timedOut() throws IOException {
        if (parser.phase() == Phase.IDLE) {
            // An idle persistent connection is closed without a response (RFC 9112 §9.5).
            return null;
        }
        String what = parser.phase() == Phase.HEAD ? "request header" : "request content";
        processor.reject(out, parser.versionSoFar(), HttpStatus.REQUEST_TIMEOUT,
                "The " + what + " was not received in time.", "RFC 9110 §15.5.9", remote);
        lingeringClose();
        return null;
    }

    /**
     * Closes after a response the client must still be able to read even though it may be
     * sending: half-close our side so the client sees the end of the response, then discard
     * what arrives until the client closes too or a short timer runs out. Closing a socket
     * with unread input makes the TCP stack send RST, and an RST lets the peer throw away
     * data it has received but not yet delivered, including our error response.
     */
    private void lingeringClose() {
        try {
            channel.shutdownOutput();
            long deadline = System.nanoTime() + LINGER_MILLIS * 1_000_000L;
            while (true) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000;
                if (remaining <= 0) {
                    return;
                }
                socket.setSoTimeout((int) remaining);
                if (in.read(readArray, 0, readArray.length) < 0) {
                    return;
                }
            }
        } catch (IOException e) {
            // Timer expired or the peer reset the connection: either way we are done.
        }
    }

    // -------------------------------------------------------------- lifecycle

    /** Closes the connection if it is waiting for a request; used by graceful shutdown. */
    void closeIfIdle() {
        if (state.compareAndSet(IDLE, CLOSED)) {
            release();
        }
    }

    /** Closes the connection whatever it is doing. Safe to call from any thread, repeatedly. */
    void close() {
        if (state.getAndSet(CLOSED) != CLOSED) {
            release();
        }
    }

    /**
     * Closes the connection and interrupts its thread; used when the shutdown grace period has
     * run out and a handler is still going. Closing the socket alone would not wake a handler
     * that is blocked on something other than this connection.
     */
    void abort() {
        close();
        Thread thread = servingThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void release() {
        try {
            // Closing the channel from another thread wakes a thread blocked in read or write
            // on it with an AsynchronousCloseException.
            channel.close();
        } catch (IOException e) {
            // nothing useful to do
        }
        engine.connectionClosed(this);
    }

    /** Called by the engine's watchdog: drops the connection if a write has been stuck too long. */
    void closeIfWriteStalled(long now, long writeTimeoutNanos) {
        long started = writeStartedNanos;
        if (started != 0 && now - started > writeTimeoutNanos) {
            LOG.log(System.Logger.Level.DEBUG, "closing {0}: write made no progress", remote);
            close();
        }
    }

    /** Answers 503 on the calling thread and closes; used when the worker pool refuses the connection. */
    void rejectOverloaded() {
        try {
            processor.reject(out, null, HttpStatus.SERVICE_UNAVAILABLE,
                    "The server is at capacity. Try again shortly.", null, remote);
        } catch (IOException e) {
            // the client is already gone
        } finally {
            close();
        }
    }

    // -------------------------------------------------------------- transport

    @Override
    public void write(ByteBuffer src) throws IOException {
        int limit = src.limit();
        try {
            while (src.position() < limit) {
                src.limit(Math.min(limit, src.position() + WRITE_SLICE));
                writeStartedNanos = System.nanoTime() | 1;
                while (src.hasRemaining()) {
                    channel.write(src);
                }
            }
        } finally {
            writeStartedNanos = 0;
            src.limit(limit);
        }
    }

    @Override
    public void transferFrom(FileChannel file, long position, long count) throws IOException {
        long sent = 0;
        try {
            while (sent < count) {
                writeStartedNanos = System.nanoTime() | 1;
                long n = file.transferTo(position + sent, Math.min(count - sent, WRITE_SLICE), channel);
                if (n <= 0) {
                    if (position + sent >= file.size()) {
                        throw new IOException("file shrank while it was being sent");
                    }
                    Thread.onSpinWait();
                }
                sent += Math.max(n, 0);
            }
        } finally {
            writeStartedNanos = 0;
        }
    }

    @Override
    public SocketAddress remoteAddress() {
        return remote;
    }
}
