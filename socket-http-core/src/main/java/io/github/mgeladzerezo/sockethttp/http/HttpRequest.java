package io.github.mgeladzerezo.sockethttp.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A complete, validated request: the parser only produces one after the whole message,
 * including any chunked content and trailers, has arrived and passed every framing check.
 *
 * <p>The content is fully buffered (bounded by the configured body limit). That is what lets
 * the same handler code run unchanged under the blocking engines and the NIO event loop, at
 * the price of not being able to stream uploads.
 */
public final class HttpRequest {

    private static final byte[] NO_BODY = new byte[0];

    private final String method;
    private final RequestTarget target;
    private final HttpVersion version;
    private final String versionText;
    private final Headers headers;
    private final byte[] body;
    private final Headers trailers;

    public HttpRequest(String method, RequestTarget target, HttpVersion version, String versionText,
                       Headers headers, byte[] body, Headers trailers) {
        this.method = method;
        this.target = target;
        this.version = version;
        this.versionText = versionText;
        this.headers = headers;
        this.body = body == null ? NO_BODY : body;
        this.trailers = trailers == null ? new Headers() : trailers;
    }

    /** The method token exactly as sent; methods are case-sensitive (RFC 9110 §9.1). */
    public String method() {
        return method;
    }

    public RequestTarget target() {
        return target;
    }

    /** The version the request is processed as. */
    public HttpVersion version() {
        return version;
    }

    /** The percent-decoded path, for example {@code /users/42}. */
    public String path() {
        return target.path();
    }

    public Headers headers() {
        return headers;
    }

    /** First value of a header field, compared case-insensitively. */
    public Optional<String> header(String name) {
        return headers.first(name);
    }

    /** First value of a query parameter. */
    public Optional<String> queryParam(String name) {
        List<String> values = target.query().get(name);
        return values == null ? Optional.empty() : Optional.of(values.getFirst());
    }

    /** Every value of a query parameter, empty when absent. */
    public List<String> queryParams(String name) {
        return target.query().getOrDefault(name, List.of());
    }

    public Map<String, List<String>> query() {
        return target.query();
    }

    /** The decoded content; chunked framing has already been removed. Never {@code null}. */
    public byte[] body() {
        return body;
    }

    /**
     * The content as text, using the {@code charset} parameter of {@code Content-Type} when it
     * names a charset this JVM supports and UTF-8 otherwise.
     */
    public String bodyAsString() {
        return new String(body, charset());
    }

    /** Trailer fields of a chunked request; empty when there were none. */
    public Headers trailers() {
        return trailers;
    }

    /** The media type from {@code Content-Type}, lower-cased and without parameters. */
    public Optional<String> contentType() {
        String value = headers.get("Content-Type");
        if (value == null) {
            return Optional.empty();
        }
        int semicolon = value.indexOf(';');
        String type = (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        return type.isEmpty() ? Optional.empty() : Optional.of(type);
    }

    /**
     * The host the request was addressed to: the authority of an absolute-form target when
     * there is one (RFC 9112 §3.2.2 says it overrides the Host field), else the Host field.
     */
    public String host() {
        return target.authority() != null ? target.authority() : headers.get("Host");
    }

    /** The request line as received, for the access log. */
    public String requestLine() {
        return method + " " + target.raw() + " " + versionText;
    }

    private Charset charset() {
        String value = headers.get("Content-Type");
        if (value != null) {
            for (String part : value.split(";")) {
                String p = part.trim();
                if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                    String name = p.substring(8).trim();
                    if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                        name = name.substring(1, name.length() - 1);
                    }
                    try {
                        return Charset.forName(name);
                    } catch (IllegalArgumentException e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public String toString() {
        return requestLine();
    }
}
