package io.github.mgeladzerezo.sockethttp.support;

import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.server.HttpServer;
import io.github.mgeladzerezo.sockethttp.server.ServerConfig;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Starts a server on a free loopback port for one test and stops it afterwards. Tests are
 * parameterised over {@link ConcurrencyModel}, so every protocol behaviour is checked against
 * the thread pool, virtual threads and the NIO event loop alike.
 */
public final class TestServer implements AutoCloseable {

    private final HttpServer server;

    private TestServer(HttpServer server) {
        this.server = server;
    }

    public static TestServer start(ConcurrencyModel model, Consumer<HttpServer> routes) {
        return start(model, UnaryOperator.identity(), routes);
    }

    public static TestServer start(ConcurrencyModel model, UnaryOperator<ServerConfig.Builder> settings,
                                   Consumer<HttpServer> routes) {
        ServerConfig.Builder builder = ServerConfig.builder()
                .host("127.0.0.1")
                .port(0)
                .concurrencyModel(model)
                .workerThreads(16)
                .shutdownGrace(Duration.ofSeconds(2));
        HttpServer server = HttpServer.create(settings.apply(builder).build());
        routes.accept(server);
        server.start();
        return new TestServer(server);
    }

    public int port() {
        return server.port();
    }

    public HttpServer server() {
        return server;
    }

    public RawClient connect() throws IOException {
        return new RawClient(server.port());
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    @Override
    public void close() {
        server.stop();
    }
}
