package io.github.mgeladzerezo.sockethttp.routing;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.http.RequestTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Maps a request to a handler through a trie of path segments, and runs the middleware chain.
 *
 * <p>Matching works on the decoded segments the parser produced, never on a re-joined path
 * string, so an encoded slash inside a segment ({@code /files/a%2Fb}) is one parameter value
 * and cannot change which route matches. The trie is searched depth-first with backtracking,
 * trying a literal child, then the parameter child, then a wildcard, so precedence does not
 * depend on registration order and {@code /users/me} beats {@code /users/{id}}.
 *
 * <p>Besides finding a handler the router produces the three protocol answers that depend on
 * the route table: 404 when no pattern matches the path, 405 with an {@code Allow} header
 * when the path matches but the method does not (RFC 9110 §15.5.6), and 501 for a method no
 * route and no built-in behaviour knows (§15.6.2). HEAD is served by the GET route, and
 * OPTIONS on a known path is answered with its {@code Allow} list unless a route handles it.
 *
 * <p>Routes must be registered before the server starts; lookups take no locks.
 */
public final class Router implements Routes<Router>, Handler {

    private static final Set<String> STANDARD_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final Node root = new Node();
    private final List<Middleware> global = new ArrayList<>();
    private final Set<String> registeredMethods = ConcurrentHashMap.newKeySet();
    private volatile Handler entry = this::dispatch;

    private record Route(String method, String pattern, List<String> paramNames, Handler handler) {
    }

    private static final class Node {
        final Map<String, Node> literals = new HashMap<>();
        Node param;
        final Map<String, Route> routes = new LinkedHashMap<>();
        final Map<String, Route> wildcardRoutes = new LinkedHashMap<>();
    }

    /** Scratch state of one lookup. */
    private static final class Lookup {
        final List<String> captures = new ArrayList<>(4);
        final Set<String> allowed = new TreeSet<>();
        int wildcardFrom = -1;
    }

    @Override
    public Router route(String method, String path, Handler handler) {
        add(method, path, handler, List.of());
        return this;
    }

    @Override
    public Router use(Middleware middleware) {
        global.add(middleware);
        entry = compose(global, this::dispatch);
        return this;
    }

    @Override
    public Router group(String prefix, Consumer<RouteGroup> routes) {
        routes.accept(new RouteGroup(this, RouteGroup.join("", prefix), List.of()));
        return this;
    }

    /** Registers a route whose handler is wrapped in {@code middleware} (outermost first). */
    synchronized void add(String method, String pattern, Handler handler, List<Middleware> middleware) {
        if (method == null || method.isEmpty() || pattern == null || handler == null) {
            throw new IllegalArgumentException("method, pattern and handler are required");
        }
        if (!pattern.isEmpty() && !pattern.startsWith("/")) {
            throw new IllegalArgumentException("route pattern must start with '/': " + pattern);
        }
        String[] parts = pattern.length() <= 1 ? new String[0] : pattern.substring(1).split("/", -1);
        int length = parts.length > 0 && parts[parts.length - 1].isEmpty() ? parts.length - 1 : parts.length;
        Node node = root;
        List<String> paramNames = new ArrayList<>();
        boolean wildcard = false;
        for (int i = 0; i < length; i++) {
            String part = parts[i];
            if (part.equals("*")) {
                if (i != length - 1) {
                    throw new IllegalArgumentException("wildcard must be the last segment: " + pattern);
                }
                wildcard = true;
            } else if (part.startsWith("{") && part.endsWith("}") && part.length() > 2) {
                String name = part.substring(1, part.length() - 1);
                if (paramNames.contains(name)) {
                    throw new IllegalArgumentException("duplicate path parameter {" + name + "} in " + pattern);
                }
                paramNames.add(name);
                if (node.param == null) {
                    node.param = new Node();
                }
                node = node.param;
            } else if (part.isEmpty() || part.contains("{") || part.contains("}") || part.contains("*")) {
                throw new IllegalArgumentException("invalid segment '" + part + "' in route pattern " + pattern);
            } else {
                node = node.literals.computeIfAbsent(part, k -> new Node());
            }
        }
        Map<String, Route> target = wildcard ? node.wildcardRoutes : node.routes;
        Route route = new Route(method, pattern.isEmpty() ? "/" : pattern, List.copyOf(paramNames),
                compose(List.copyOf(middleware), handler));
        if (target.putIfAbsent(method, route) != null) {
            throw new IllegalArgumentException("duplicate route: " + method + " " + pattern);
        }
        registeredMethods.add(method);
    }

    /** Runs server-level middleware and then the matched route. */
    @Override
    public void handle(Context ctx) throws Exception {
        entry.handle(ctx);
    }

    private void dispatch(Context ctx) throws Exception {
        HttpRequest request = ctx.request();
        String method = request.method();
        if (!STANDARD_METHODS.contains(method) && !registeredMethods.contains(method)) {
            throw new HttpException(HttpStatus.NOT_IMPLEMENTED, "method " + method + " is not implemented");
        }
        if (request.target().form() == RequestTarget.Form.ASTERISK) {
            // "OPTIONS *" asks about the server as a whole (RFC 9110 §9.3.7).
            ctx.status(HttpStatus.NO_CONTENT).header("Allow", String.join(", ", new TreeSet<>(STANDARD_METHODS)));
            return;
        }

        List<String> segments = request.target().segments();
        int end = !segments.isEmpty() && segments.getLast().isEmpty() ? segments.size() - 1 : segments.size();
        Lookup lookup = new Lookup();
        Route route = find(root, segments, 0, end, method, lookup);

        if (route == null) {
            if (lookup.allowed.isEmpty()) {
                throw new HttpException(HttpStatus.NOT_FOUND, "no route for " + request.path());
            }
            if (lookup.allowed.contains("GET")) {
                lookup.allowed.add("HEAD");
            }
            lookup.allowed.add("OPTIONS");
            String allow = String.join(", ", lookup.allowed);
            if (method.equals("OPTIONS")) {
                ctx.status(HttpStatus.NO_CONTENT).header("Allow", allow);
                return;
            }
            throw new HttpException(HttpStatus.METHOD_NOT_ALLOWED, "method " + method + " is not allowed for " + request.path())
                    .header("Allow", allow);
        }

        Map<String, String> params = Map.of();
        if (!route.paramNames.isEmpty()) {
            params = new LinkedHashMap<>();
            for (int i = 0; i < route.paramNames.size(); i++) {
                params.put(route.paramNames.get(i), lookup.captures.get(i));
            }
        }
        List<String> wildcard = lookup.wildcardFrom < 0 ? null : segments.subList(lookup.wildcardFrom, segments.size());
        ctx.matched(route.pattern, params, wildcard);
        route.handler.handle(ctx);
    }

    private Route find(Node node, List<String> segments, int index, int end, String method, Lookup lookup) {
        if (index == end) {
            Route route = pick(node.routes, method);
            if (route != null) {
                lookup.wildcardFrom = -1;
                return route;
            }
            lookup.allowed.addAll(node.routes.keySet());
        } else {
            String segment = segments.get(index);
            Node literal = node.literals.get(segment);
            if (literal != null) {
                Route route = find(literal, segments, index + 1, end, method, lookup);
                if (route != null) {
                    return route;
                }
            }
            if (node.param != null && !segment.isEmpty()) {
                lookup.captures.add(segment);
                Route route = find(node.param, segments, index + 1, end, method, lookup);
                if (route != null) {
                    return route;
                }
                lookup.captures.removeLast();
            }
        }
        if (!node.wildcardRoutes.isEmpty()) {
            Route route = pick(node.wildcardRoutes, method);
            if (route != null) {
                lookup.wildcardFrom = index;
                return route;
            }
            lookup.allowed.addAll(node.wildcardRoutes.keySet());
        }
        return null;
    }

    /** The route for {@code method}, letting HEAD fall back to GET (RFC 9110 §9.3.2). */
    private static Route pick(Map<String, Route> routes, String method) {
        Route route = routes.get(method);
        if (route == null && method.equals("HEAD")) {
            route = routes.get("GET");
        }
        return route;
    }

    /** Folds middleware around a handler so that the first element runs outermost. */
    static Handler compose(List<Middleware> middleware, Handler terminal) {
        Handler handler = terminal;
        for (int i = middleware.size() - 1; i >= 0; i--) {
            Middleware m = middleware.get(i);
            Handler next = handler;
            handler = ctx -> m.handle(ctx, next);
        }
        return handler;
    }
}
