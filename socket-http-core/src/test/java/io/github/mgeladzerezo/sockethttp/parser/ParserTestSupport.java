package io.github.mgeladzerezo.sockethttp.parser;

import io.github.mgeladzerezo.sockethttp.http.HttpRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Shared helpers for the parser tests. */
final class ParserTestSupport {

    private ParserTestSupport() {
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Parses exactly one request delivered in a single buffer. */
    static HttpRequest parseOne(String raw) throws HttpParseException {
        return parseOne(raw, ParserLimits.DEFAULT);
    }

    static HttpRequest parseOne(String raw, ParserLimits limits) throws HttpParseException {
        RequestParser parser = new RequestParser(limits);
        ByteBuffer in = ByteBuffer.wrap(bytes(raw));
        if (!parser.feed(in)) {
            throw new AssertionError("request is incomplete: parser stopped in phase " + parser.phase());
        }
        if (in.hasRemaining()) {
            throw new AssertionError(in.remaining() + " bytes left over after the request");
        }
        return parser.request();
    }

    /**
     * Parses a byte stream that may hold several pipelined requests, delivered in the
     * segments defined by {@code cuts} (ascending offsets at which a new segment starts).
     * Returns a canonical description of every request, so two deliveries of the same bytes
     * can be compared with {@code equals}.
     */
    static List<String> parseStream(byte[] data, ParserLimits limits, int... cuts) throws HttpParseException {
        RequestParser parser = new RequestParser(limits);
        List<String> requests = new ArrayList<>();
        int from = 0;
        for (int i = 0; i <= cuts.length; i++) {
            int to = i < cuts.length ? cuts[i] : data.length;
            ByteBuffer segment = ByteBuffer.wrap(data, from, to - from);
            while (segment.hasRemaining()) {
                if (parser.feed(segment)) {
                    requests.add(describe(parser.request()));
                    parser.reset();
                }
            }
            from = to;
        }
        if (parser.phase() != RequestParser.Phase.IDLE) {
            throw new AssertionError("stream ended inside a request, phase " + parser.phase());
        }
        return requests;
    }

    /** Everything observable about a request, as one string. */
    static String describe(HttpRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.requestLine()).append('\n');
        sb.append("form=").append(r.target().form()).append(" path=").append(r.path())
                .append(" segments=").append(r.target().segments())
                .append(" query=").append(r.query())
                .append(" authority=").append(r.target().authority()).append('\n');
        sb.append("version=").append(r.version()).append('\n');
        r.headers().forEach((n, v) -> sb.append("H ").append(n).append('=').append(v).append('\n'));
        sb.append("body=").append(HexFormat.of().formatHex(r.body())).append('\n');
        r.trailers().forEach((n, v) -> sb.append("T ").append(n).append('=').append(v).append('\n'));
        return sb.toString();
    }
}
