package io.github.mgeladzerezo.sockethttp.middleware;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.routing.Context;
import io.github.mgeladzerezo.sockethttp.routing.Handler;
import io.github.mgeladzerezo.sockethttp.routing.Middleware;

import java.util.function.Consumer;

/**
 * Logs one line per request with method, path, matched route, status and handler time:
 * {@code GET /users/42 (/users/{id}) -> 200 in 0.4 ms}.
 *
 * <p>This is application-level logging: it sees the route pattern and the status the handler
 * chose, but not the bytes sent, and it does not see requests the parser rejected. For an
 * audit trail of everything that reached the socket use the server's
 * {@link io.github.mgeladzerezo.sockethttp.server.AccessLog} instead.
 */
public final class RequestLog implements Middleware {

    private final Consumer<String> sink;

    private RequestLog(Consumer<String> sink) {
        this.sink = sink;
    }

    /** Logs through {@link System.Logger} at INFO under the name {@code sockethttp.request}. */
    public static RequestLog toSystemLogger() {
        System.Logger logger = System.getLogger("sockethttp.request");
        return new RequestLog(line -> logger.log(System.Logger.Level.INFO, line));
    }

    public static RequestLog to(Consumer<String> sink) {
        return new RequestLog(sink);
    }

    @Override
    public void handle(Context ctx, Handler next) throws Exception {
        long start = System.nanoTime();
        int status = 500;
        try {
            next.handle(ctx);
            status = ctx.response().status();
        } catch (HttpException e) {
            status = e.status();
            throw e;
        } finally {
            double millis = (System.nanoTime() - start) / 1_000_000.0;
            String route = ctx.routePattern() == null ? "" : " (" + ctx.routePattern() + ")";
            sink.accept(ctx.method() + " " + ctx.path() + route + " -> " + status
                    + " in " + Math.round(millis * 10) / 10.0 + " ms");
        }
    }
}
