package io.github.mgeladzerezo.sockethttp.routing;

/**
 * Handles one request by reading from and describing a response on the {@link Context}.
 *
 * <p>Handlers run on a thread they are allowed to block: a pool worker, a virtual thread, or
 * an event-loop worker, depending on the configured concurrency model. Any exception ends the
 * request with 500, except {@link io.github.mgeladzerezo.sockethttp.http.HttpException},
 * which carries its own status.
 */
@FunctionalInterface
public interface Handler {
    void handle(Context ctx) throws Exception;
}
