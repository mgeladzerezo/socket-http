package io.github.mgeladzerezo.sockethttp.middleware;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.routing.Context;
import io.github.mgeladzerezo.sockethttp.routing.Handler;
import io.github.mgeladzerezo.sockethttp.routing.Middleware;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions thrown further down the chain into responses.
 *
 * <pre>{@code
 * server.use(ErrorMapper.json()
 *         .on(NoSuchElementException.class, (e, ctx) -> ctx.status(404).json(Map.of("error", e.getMessage())))
 *         .on(IllegalArgumentException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage()))));
 * }</pre>
 *
 * <p>The mapping registered for the most specific matching class wins, whatever the order of
 * registration. Exceptions with no mapping propagate to the server, which answers 500 (or
 * the status of an {@link HttpException}) with a plain-text body.
 */
public final class ErrorMapper implements Middleware {

    private static final System.Logger LOG = System.getLogger(ErrorMapper.class.getName());

    /** Writes the response for one kind of exception. */
    @FunctionalInterface
    public interface Mapping<E extends Throwable> {
        void apply(E error, Context ctx) throws Exception;
    }

    private final Map<Class<?>, Mapping<Throwable>> mappings = new LinkedHashMap<>();

    private ErrorMapper() {
    }

    /** A mapper with no mappings; add them with {@link #on}. */
    public static ErrorMapper create() {
        return new ErrorMapper();
    }

    /**
     * A mapper that renders every error as {@code {"status":..., "error":..., "message":...}}:
     * {@link HttpException}s (including the router's 404 and 405) with their own status and
     * headers, anything else as a 500 whose details are logged, not sent.
     */
    public static ErrorMapper json() {
        return new ErrorMapper()
                .on(HttpException.class, (e, ctx) -> {
                    e.headers().forEach(ctx::header);
                    ctx.status(e.status()).json(errorBody(e.status(), e.getMessage()));
                })
                .on(Exception.class, (e, ctx) -> {
                    LOG.log(System.Logger.Level.ERROR, "unhandled error for " + ctx.request().requestLine(), e);
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .json(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "The server failed to handle the request."));
                });
    }

    private static Map<String, Object> errorBody(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", HttpStatus.reason(status));
        body.put("message", message);
        return body;
    }

    /** Registers how to answer when {@code type} or a subclass of it is thrown. */
    @SuppressWarnings("unchecked")
    public <E extends Throwable> ErrorMapper on(Class<E> type, Mapping<? super E> mapping) {
        mappings.put(type, (Mapping<Throwable>) mapping);
        return this;
    }

    @Override
    public void handle(Context ctx, Handler next) throws Exception {
        try {
            next.handle(ctx);
        } catch (Exception e) {
            Mapping<Throwable> mapping = find(e.getClass());
            if (mapping == null) {
                throw e;
            }
            // Whatever the handler had half-built is replaced, not merged with the error.
            ctx.response().headers().remove("Content-Type");
            ctx.response().headers().remove("Content-Encoding");
            mapping.apply(e, ctx);
        }
    }

    /** Walks up the class hierarchy so the closest registered superclass is used. */
    private Mapping<Throwable> find(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Mapping<Throwable> mapping = mappings.get(c);
            if (mapping != null) {
                return mapping;
            }
        }
        return null;
    }
}
