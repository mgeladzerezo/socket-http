package io.github.mgeladzerezo.sockethttp.routing;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.http.Response;
import io.github.mgeladzerezo.sockethttp.http.ResponseBody;
import io.github.mgeladzerezo.sockethttp.json.Json;
import io.github.mgeladzerezo.sockethttp.sse.SseEmitter;
import io.github.mgeladzerezo.sockethttp.sse.SseHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Everything a handler needs for one request: the parsed request, the values the router
 * extracted from the path, typed accessors that turn bad input into a 400, and helpers that
 * describe the response.
 *
 * <p>The response helpers do not write to the socket. They record what should be sent; the
 * server writes it after the handler and all middleware have returned.
 */
public final class Context {

    private final HttpRequest request;
    private final SocketAddress remoteAddress;
    private final BooleanSupplier serverStopping;
    private final Response response = new Response();

    private String routePattern;
    private Map<String, String> pathParams = Map.of();
    private List<String> wildcard;
    private Map<String, Object> attributes;

    public Context(HttpRequest request, SocketAddress remoteAddress, BooleanSupplier serverStopping) {
        this.request = request;
        this.remoteAddress = remoteAddress;
        this.serverStopping = serverStopping;
    }

    /** Called by the router once a route has matched. */
    void matched(String pattern, Map<String, String> params, List<String> wildcardSegments) {
        this.routePattern = pattern;
        this.pathParams = params;
        this.wildcard = wildcardSegments;
    }

    // ---------------------------------------------------------------- request

    public HttpRequest request() {
        return request;
    }

    public String method() {
        return request.method();
    }

    /** The percent-decoded request path. */
    public String path() {
        return request.path();
    }

    /** The pattern of the matched route, such as {@code /users/{id}}; {@code null} before routing. */
    public String routePattern() {
        return routePattern;
    }

    /** The client's address as seen by the socket. */
    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    /** The client's IP address, or {@code "-"} when it is not an internet socket. */
    public String remoteHost() {
        return remoteAddress instanceof InetSocketAddress inet && inet.getAddress() != null
                ? inet.getAddress().getHostAddress() : "-";
    }

    /**
     * The decoded value of a path parameter.
     *
     * @throws IllegalArgumentException if the matched route has no such parameter, which is a
     *                                  programming error rather than a client error
     */
    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new IllegalArgumentException("route " + routePattern + " has no path parameter {" + name + "}");
        }
        return value;
    }

    public Map<String, String> pathParams() {
        return pathParams;
    }

    /** A path parameter as an {@code int}; 400 if the client sent something else. */
    public int pathParamAsInt(String name) {
        try {
            return Integer.parseInt(pathParam(name));
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("path parameter '" + name + "' must be an integer");
        }
    }

    /** A path parameter as a {@code long}; 400 if the client sent something else. */
    public long pathParamAsLong(String name) {
        try {
            return Long.parseLong(pathParam(name));
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("path parameter '" + name + "' must be an integer");
        }
    }

    /**
     * The decoded segments a trailing {@code *} matched. {@code /assets/*} on
     * {@code /assets/css/site.css} gives {@code ["css", "site.css"]}; a trailing slash on the
     * request shows up as a final empty segment. Empty when the route has no wildcard.
     */
    public List<String> wildcardSegments() {
        return wildcard == null ? List.of() : wildcard;
    }

    /** The wildcard match joined with {@code /}. */
    public String wildcard() {
        return String.join("/", wildcardSegments());
    }

    public Optional<String> queryParam(String name) {
        return request.queryParam(name);
    }

    /** A query parameter as an {@code int}, or {@code defaultValue} when absent; 400 if malformed. */
    public int queryParamAsInt(String name, int defaultValue) {
        Optional<String> value = request.queryParam(name);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.get());
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("query parameter '" + name + "' must be an integer");
        }
    }

    public Optional<String> header(String name) {
        return request.header(name);
    }

    public byte[] body() {
        return request.body();
    }

    public String bodyAsString() {
        return request.bodyAsString();
    }

    /** The request content parsed as JSON; 400 if it is not valid JSON. */
    public Object bodyAsJson() {
        try {
            return Json.parse(request.bodyAsString());
        } catch (Json.JsonException e) {
            throw HttpException.badRequest("request body is not valid JSON: " + e.getMessage());
        }
    }

    /** The request content parsed as a JSON object; 400 if it is anything else. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> bodyAsJsonObject() {
        if (bodyAsJson() instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw HttpException.badRequest("request body must be a JSON object");
    }

    /** A value stored by earlier middleware, or {@code null}. */
    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) {
        return attributes == null ? null : (T) attributes.get(key);
    }

    /** Stores a value for later middleware and the handler. */
    public Context attribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
        return this;
    }

    /** Whether the server has started a graceful shutdown; long-running handlers should wrap up. */
    public boolean serverStopping() {
        return serverStopping.getAsBoolean();
    }

    // --------------------------------------------------------------- response

    public Response response() {
        return response;
    }

    public Context status(int status) {
        response.status(status);
        return this;
    }

    public Context header(String name, String value) {
        response.header(name, value);
        return this;
    }

    public Context text(String body) {
        response.body(body, "text/plain; charset=utf-8");
        return this;
    }

    public Context html(String body) {
        response.body(body, "text/html; charset=utf-8");
        return this;
    }

    /** Serialises {@code value} with {@link Json#write} and sends it as {@code application/json}. */
    public Context json(Object value) {
        response.body(Json.write(value), "application/json");
        return this;
    }

    public Context bytes(byte[] body, String contentType) {
        response.body(body, contentType);
        return this;
    }

    /** 204 with no content. */
    public Context noContent() {
        response.status(HttpStatus.NO_CONTENT).body(ResponseBody.EMPTY);
        return this;
    }

    /** 302 to {@code location}. */
    public Context redirect(String location) {
        return redirect(HttpStatus.FOUND, location);
    }

    public Context redirect(int status, String location) {
        response.status(status).header("Location", location).body(ResponseBody.EMPTY);
        return this;
    }

    /**
     * Streams a body of unknown length. The writer runs after the handler returns, while the
     * response is being written; under HTTP/1.1 each write becomes one chunk.
     */
    public Context stream(String contentType, ResponseBody.Writer writer) {
        response.header("Content-Type", contentType).body(new ResponseBody.Stream(-1, writer));
        return this;
    }

    /**
     * Turns the response into a Server-Sent Events stream. The handler is invoked once the
     * response head has been sent and may block for as long as the stream should stay open.
     */
    public Context sse(SseHandler handler) {
        response.header("Content-Type", "text/event-stream")
                .header("Cache-Control", "no-store")
                // Asks nginx-style reverse proxies not to buffer the stream.
                .header("X-Accel-Buffering", "no")
                .body(new ResponseBody.Stream(-1, sink -> {
                    sink.flush();
                    try {
                        handler.handle(new SseEmitter(sink, serverStopping));
                    } catch (IOException | RuntimeException e) {
                        throw e;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        throw new IOException("SSE handler failed", e);
                    }
                }));
        return this;
    }
}
