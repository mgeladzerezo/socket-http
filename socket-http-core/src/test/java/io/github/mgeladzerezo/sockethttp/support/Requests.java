package io.github.mgeladzerezo.sockethttp.support;

import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.parser.HttpParseException;
import io.github.mgeladzerezo.sockethttp.parser.RequestParser;
import io.github.mgeladzerezo.sockethttp.routing.Context;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Builds requests and contexts for tests that exercise routing or middleware without a socket. */
public final class Requests {

    private Requests() {
    }

    /** Parses raw request text with the real parser. */
    public static HttpRequest parse(String raw) {
        RequestParser parser = new RequestParser();
        try {
            if (!parser.feed(ByteBuffer.wrap(raw.getBytes(StandardCharsets.ISO_8859_1)))) {
                throw new IllegalArgumentException("incomplete request: " + raw);
            }
        } catch (HttpParseException e) {
            throw new IllegalArgumentException("invalid request: " + e.getMessage(), e);
        }
        return parser.request();
    }

    /** A context for {@code METHOD target} with optional extra header lines ({@code "Name: value"}). */
    public static Context context(String method, String target, String... headers) {
        StringBuilder raw = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: test\r\n");
        for (String header : headers) {
            raw.append(header).append("\r\n");
        }
        raw.append("\r\n");
        return new Context(parse(raw.toString()), new InetSocketAddress("127.0.0.1", 54321), () -> false);
    }
}
