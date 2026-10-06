package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.concurrent.BoundedThreadPool;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The two thread-per-connection models. An acceptor thread takes connections off the listening
 * socket and hands each to an executor, where a {@link BlockingConnection} serves it:
 * <ul>
 *   <li>{@link ConcurrencyModel#THREAD_POOL}: the executor is a {@link BoundedThreadPool};</li>
 *   <li>{@link ConcurrencyModel#VIRTUAL_THREADS}: the executor starts a new virtual thread.</li>
 * </ul>
 *
 * <h2>Connection limit and backpressure</h2>
 * The acceptor takes a permit from a semaphore <em>before</em> calling {@code accept()} and a
 * connection gives it back when it closes. At the limit the acceptor therefore stops
 * accepting; further clients complete their TCP handshake into the kernel's listen backlog
 * and wait there, and once the backlog is full the kernel stops answering SYNs. Load is
 * pushed back to the clients instead of being buffered in the server's heap. With the default
 * {@code BLOCK} overload policy a full worker pool has the same effect, because the acceptor
 * blocks inside {@code execute}.
 */
final class BlockingEngine implements ConnectionEngine {

    private static final System.Logger LOG = System.getLogger(BlockingEngine.class.getName());

    private final ServerSocketChannel server;
    private final ServerConfig config;
    private final ExchangeProcessor processor;
    private final BooleanSupplier stopping;

    private final Semaphore slots;
    private final Set<BlockingConnection> connections = ConcurrentHashMap.newKeySet();
    private final BoundedThreadPool pool;
    private final Executor executor;
    private final Object drained = new Object();

    private Thread acceptor;
    private Thread watchdog;

    BlockingEngine(ServerSocketChannel server, ServerConfig config, ExchangeProcessor processor, BooleanSupplier stopping) {
        this.server = server;
        this.config = config;
        this.processor = processor;
        this.stopping = stopping;
        this.slots = new Semaphore(config.maxConnections());
        if (config.concurrencyModel() == ConcurrencyModel.VIRTUAL_THREADS) {
            ThreadFactory factory = Thread.ofVirtual().name("sockethttp-conn-", 1).factory();
            this.pool = null;
            this.executor = task -> factory.newThread(task).start();
        } else {
            int core = Math.min(config.workerThreads(), Runtime.getRuntime().availableProcessors());
            this.pool = new BoundedThreadPool(core, config.workerThreads(), config.queueCapacity(),
                    Duration.ofSeconds(60), config.overloadPolicy(), BoundedThreadPool.namedDaemonFactory("sockethttp-worker"));
            this.executor = pool;
        }
    }

    @Override
    public void start() {
        acceptor = Thread.ofPlatform().name("sockethttp-acceptor").daemon(false).start(this::acceptLoop);
        watchdog = Thread.ofPlatform().name("sockethttp-write-watchdog").daemon(true).start(this::watchWrites);
    }

    boolean isStopping() {
        return stopping.getAsBoolean();
    }

    private void acceptLoop() {
        while (!stopping.getAsBoolean()) {
            boolean havePermit = false;
            try {
                slots.acquire();
                havePermit = true;
                SocketChannel channel = server.accept();
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                BlockingConnection connection = new BlockingConnection(this, channel, config, processor);
                connections.add(connection);
                processor.metrics().connectionOpened();
                havePermit = false;   // from here on the connection returns the permit when it closes
                try {
                    executor.execute(connection);
                } catch (RejectedExecutionException e) {
                    processor.metrics().connectionRejected();
                    connection.rejectOverloaded();
                }
            } catch (InterruptedException | ClosedChannelException e) {
                // shutdown() interrupts this thread and closes the listening socket
                return;
            } catch (IOException e) {
                if (stopping.getAsBoolean()) {
                    return;
                }
                // Typically "too many open files". Keep serving existing connections and retry
                // shortly rather than spinning or dying.
                LOG.log(System.Logger.Level.WARNING, "accept failed", e);
                pause();
            } finally {
                if (havePermit) {
                    slots.release();
                }
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A blocking socket write has no timeout of its own, so a client that stops reading could
     * hold a thread forever. This thread closes connections whose current write call has made
     * no progress for the write timeout; closing the channel unblocks the stuck writer.
     */
    private void watchWrites() {
        long timeoutNanos = config.writeTimeout().toNanos();
        long intervalMillis = Math.clamp(config.writeTimeout().toMillis() / 4, 10, 1_000);
        try {
            while (true) {
                Thread.sleep(intervalMillis);
                long now = System.nanoTime();
                for (BlockingConnection connection : connections) {
                    connection.closeIfWriteStalled(now, timeoutNanos);
                }
            }
        } catch (InterruptedException e) {
            // engine shut down
        }
    }

    void connectionClosed(BlockingConnection connection) {
        if (connections.remove(connection)) {
            slots.release();
            processor.metrics().connectionClosed();
            if (connections.isEmpty()) {
                synchronized (drained) {
                    drained.notifyAll();
                }
            }
        }
    }

    @Override
    public boolean shutdown(Duration grace) {
        // The caller has already raised the stop flag, so from here on no connection goes
        // back to waiting for another request.
        acceptor.interrupt();
        closeQuietly();
        joinQuietly(acceptor);

        for (BlockingConnection connection : connections) {
            connection.closeIfIdle();
        }
        boolean clean = awaitDrained(grace);
        for (BlockingConnection connection : connections) {
            connection.abort();
        }
        watchdog.interrupt();
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        return clean;
    }

    private boolean awaitDrained(Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos();
        synchronized (drained) {
            while (!connections.isEmpty()) {
                long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
                if (remainingMillis <= 0) {
                    return false;
                }
                try {
                    // Bounded wait: the emptiness check and the notify are not under one lock.
                    drained.wait(Math.min(remainingMillis, 50));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void closeQuietly() {
        try {
            server.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing the listening socket failed", e);
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
