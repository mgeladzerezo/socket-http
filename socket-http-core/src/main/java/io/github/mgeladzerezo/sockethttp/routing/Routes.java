package io.github.mgeladzerezo.sockethttp.routing;

import java.util.function.Consumer;

/**
 * The route registration API, shared by the server itself and by route groups.
 *
 * <pre>{@code
 * server.get("/users/{id}", ctx -> ctx.json(users.find(ctx.pathParamAsLong("id"))));
 * server.group("/admin", admin -> {
 *     admin.use(requireToken);
 *     admin.delete("/users/{id}", ctx -> ...);
 * });
 * server.get("/assets/*", StaticFiles.serve(Path.of("public")));
 * }</pre>
 *
 * <p>A path is a sequence of segments: a literal, a parameter ({@code {name}}, matches exactly
 * one segment) or, as the last segment only, a wildcard ({@code *}, matches the rest of the
 * path including nothing). When several routes match, literals win over parameters and
 * parameters over wildcards, independent of registration order. A trailing slash on the
 * request path is ignored for matching.
 *
 * @param <T> the concrete type, so calls can be chained
 */
public interface Routes<T extends Routes<T>> {

    /** Registers a handler for a method and path pattern. */
    T route(String method, String path, Handler handler);

    /**
     * Adds middleware. On the server it wraps every request; inside a group it wraps the
     * routes registered in that group <em>after</em> this call.
     */
    T use(Middleware middleware);

    /** Registers routes under a common path prefix, with middleware of their own. */
    T group(String prefix, Consumer<RouteGroup> routes);

    default T get(String path, Handler handler) {
        return route("GET", path, handler);
    }

    default T post(String path, Handler handler) {
        return route("POST", path, handler);
    }

    default T put(String path, Handler handler) {
        return route("PUT", path, handler);
    }

    default T patch(String path, Handler handler) {
        return route("PATCH", path, handler);
    }

    default T delete(String path, Handler handler) {
        return route("DELETE", path, handler);
    }

    default T head(String path, Handler handler) {
        return route("HEAD", path, handler);
    }

    default T options(String path, Handler handler) {
        return route("OPTIONS", path, handler);
    }
}
