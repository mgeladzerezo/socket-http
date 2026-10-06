package io.github.mgeladzerezo.sockethttp.routing;

/**
 * Wraps the handling of a request: code before {@code next.handle(ctx)} runs on the way in,
 * code after it on the way out, and not calling {@code next} short-circuits the chain.
 *
 * <p>Because the response is only written to the socket after the whole chain has returned,
 * middleware can still add or change headers (and even replace the body) on the way out.
 * Middleware runs in the order it was registered; server-level middleware also sees requests
 * that match no route, so logging and CORS apply to 404s as well.
 */
@FunctionalInterface
public interface Middleware {
    void handle(Context ctx, Handler next) throws Exception;
}
