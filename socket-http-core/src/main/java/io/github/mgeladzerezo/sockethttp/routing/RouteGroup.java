package io.github.mgeladzerezo.sockethttp.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Routes that share a path prefix and a middleware stack. Groups nest: an inner group inherits
 * the prefix and the middleware its parent had at the moment the inner group was opened.
 */
public final class RouteGroup implements Routes<RouteGroup> {

    private final Router router;
    private final String prefix;
    private final List<Middleware> middleware;

    RouteGroup(Router router, String prefix, List<Middleware> inherited) {
        this.router = router;
        this.prefix = prefix;
        this.middleware = new ArrayList<>(inherited);
    }

    @Override
    public RouteGroup route(String method, String path, Handler handler) {
        router.add(method, join(prefix, path), handler, middleware);
        return this;
    }

    @Override
    public RouteGroup use(Middleware m) {
        middleware.add(m);
        return this;
    }

    @Override
    public RouteGroup group(String subPrefix, Consumer<RouteGroup> routes) {
        routes.accept(new RouteGroup(router, join(prefix, subPrefix), middleware));
        return this;
    }

    static String join(String prefix, String path) {
        String left = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String right = path.isEmpty() || path.startsWith("/") ? path : "/" + path;
        String joined = left + right;
        return joined.isEmpty() ? "/" : joined;
    }
}
