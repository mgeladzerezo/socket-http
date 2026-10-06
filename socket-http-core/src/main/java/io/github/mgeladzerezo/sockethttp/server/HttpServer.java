package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.metrics.ServerMetrics;
import io.github.mgeladzerezo.sockethttp.routing.Handler;
import io.github.mgeladzerezo.sockethttp.routing.Middleware;
import io.github.mgeladzerezo.sockethttp.routing.RouteGroup;
import io.github.mgeladzerezo.sockethttp.routing.Router;
import io.github.mgeladzerezo.sockethttp.routing.Routes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * An HTTP/1.1 server on a plain {@link ServerSocketChannel}.
 *
 * <pre>{@code
 * HttpServer server = HttpServer.create(ServerConfig.builder().port(8080).build());
 * server.use(Middlewares.cors());
 * server.get("/hello/{name}", ctx -> ctx.json(Map.of("hello", ctx.pathParam("name"))));
 * server.get("/metrics", server.metricsHandler());
 * server.start();
 * ...
 * server.stop();   // graceful: in-flight requests finish first
 * }</pre>
 *
 * <p>Register routes and middleware before {@link #start()}. The instance is single-use: once
 * stopped it cannot be started again.
 */
public final class HttpServer implements Routes<HttpServer>, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(HttpServer.class.getName());

    private final ServerConfig config;
    private final Router router = new Router();
    private final ServerMetrics metrics;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    private AccessLog accessLog;
    private ServerSocketChannel channel;
    private ConnectionEngine engine;
    private volatile int boundPort = -1;

    private HttpServer(ServerConfig config) {
        this.config = config;
        this.metrics = new ServerMetrics(config.concurrencyModel().name());
    }

    public static HttpServer create(ServerConfig config) {
        return new HttpServer(config);
    }

    /** A server with default settings on the given port. */
    public static HttpServer create(int port) {
        return new HttpServer(ServerConfig.builder().port(port).build());
    }

    // ---------------------------------------------------------------- routing

    @Override
    public HttpServer route(String method, String path, Handler handler) {
        router.route(method, path, handler);
        return this;
    }

    @Override
    public HttpServer use(Middleware middleware) {
        router.use(middleware);
        return this;
    }

    @Override
    public HttpServer group(String prefix, Consumer<RouteGroup> routes) {
        router.group(prefix, routes);
        return this;
    }

    /** Sends one entry per finished exchange to {@code log}. Must be set before {@link #start()}. */
    public HttpServer accessLog(AccessLog log) {
        this.accessLog = log;
        return this;
    }

    /**
     * A handler that serves this server's metrics: JSON by default, the Prometheus text format
     * with {@code ?format=prometheus}.
     */
    public Handler metricsHandler() {
        return ctx -> {
            if (ctx.queryParam("format").filter("prometheus"::equals).isPresent()) {
                ctx.response().body(metrics.snapshot().toPrometheus(), "text/plain; version=0.0.4; charset=utf-8");
            } else {
                ctx.json(metrics.snapshot().toMap());
            }
            ctx.header("Cache-Control", "no-store");
        };
    }

    public ServerMetrics metrics() {
        return metrics;
    }

    public ServerConfig config() {
        return config;
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Binds the listening socket and starts accepting connections.
     *
     * @throws UncheckedIOException if the address cannot be bound
     */
    public HttpServer start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("server was already started");
        }
        try {
            channel = ServerSocketChannel.open();
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
            boundPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        } catch (IOException e) {
            closeChannel();
            throw new UncheckedIOException("cannot bind " + config.host() + ":" + config.port(), e);
        }
        ExchangeProcessor processor = new ExchangeProcessor(config, router, metrics, accessLog, stopping::get);
        engine = switch (config.concurrencyModel()) {
            case THREAD_POOL, VIRTUAL_THREADS -> new BlockingEngine(channel, config, processor, stopping::get);
            case NIO_EVENT_LOOP -> new NioEngine(channel, config, processor, stopping::get);
        };
        engine.start();
        LOG.log(System.Logger.Level.INFO, "listening on {0}:{1} ({2})", config.host(), Integer.toString(boundPort),
                config.concurrencyModel());
        return this;
    }

    /** The port the server is listening on; useful when the configured port was 0. */
    public int port() {
        if (boundPort < 0) {
            throw new IllegalStateException("server has not been started");
        }
        return boundPort;
    }

    /** Whether a graceful shutdown has begun. */
    public boolean isStopping() {
        return stopping.get();
    }

    /**
     * Stops the server gracefully: no new connections are accepted, idle connections are
     * closed, requests in flight get up to {@link ServerConfig#shutdownGrace()} to finish, and
     * anything still open after that is cut.
     *
     * @return {@code true} if every in-flight request finished within the grace period
     */
    public boolean stop() {
        if (!started.get() || !stopping.compareAndSet(false, true)) {
            return true;
        }
        boolean clean = engine == null || engine.shutdown(config.shutdownGrace());
        closeChannel();
        LOG.log(System.Logger.Level.INFO, "stopped ({0})", clean ? "all requests finished" : "grace period expired");
        return clean;
    }

    @Override
    public void close() {
        stop();
    }

    private void closeChannel() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing the listening socket failed", e);
        }
    }
}
