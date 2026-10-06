package io.github.mgeladzerezo.sockethttp.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static io.github.mgeladzerezo.sockethttp.parser.ParserTestSupport.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The table of inputs the parser must refuse, with the status each one maps to.
 *
 * <p>Every row is checked twice: delivered in one buffer and delivered one byte at a time.
 * The status must be the same, which proves that rejection does not depend on how TCP
 * happened to segment the attack.
 */
class MalformedRequestTest {

    private static final String HOST = "Host: a\r\n";
    private static final String CHUNKED_HEAD = "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked\r\n\r\n";

    /** Small limits so the limit rows stay readable. */
    private static final ParserLimits LIMITS = new ParserLimits(64, 256, 8, 32, 32);

    static Stream<Arguments> malformed() {
        return Stream.of(
                // ---- line endings (RFC 9112 §2.2)
                arguments("bare LF ends the request line", "GET / HTTP/1.1\n" + HOST + "\r\n", 400),
                arguments("bare LF ends a header line", "GET / HTTP/1.1\r\nHost: a\n\r\n", 400),
                arguments("bare LF ends the header section", "GET / HTTP/1.1\r\n" + HOST + "\n", 400),
                arguments("bare CR inside a header value", "GET / HTTP/1.1\r\n" + HOST + "X: a\rb\r\n\r\n", 400),
                arguments("bare CR inside the request line", "GET /\r HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("too many empty lines first", "\r\n\r\n\r\n\r\n\r\nGET / HTTP/1.1\r\n" + HOST + "\r\n", 400),

                // ---- request line (RFC 9112 §3)
                arguments("HTTP/0.9 simple request", "GET /\r\n\r\n", 400),
                arguments("two spaces after the method", "GET  / HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("tab as separator", "GET\t/\tHTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("trailing space after the version", "GET / HTTP/1.1 \r\n" + HOST + "\r\n", 400),
                arguments("space inside the target", "GET /a b HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("missing method", " / HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("invalid method character", "G@T / HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("binary garbage (TLS ClientHello)", "\u0016\u0003\u0001\u0002\u0000\u0001", 400),
                arguments("lower-case version", "GET / http/1.1\r\n" + HOST + "\r\n", 400),
                arguments("version without minor", "GET / HTTP/1\r\n" + HOST + "\r\n", 400),
                arguments("two-digit minor version", "GET / HTTP/1.10\r\n" + HOST + "\r\n", 400),
                arguments("HTTP/2.0", "GET / HTTP/2.0\r\n" + HOST + "\r\n", 505),
                arguments("HTTP/0.9 stated explicitly", "GET / HTTP/0.9\r\n" + HOST + "\r\n", 505),
                arguments("HTTP/3.0", "GET / HTTP/3.0\r\n" + HOST + "\r\n", 505),

                // ---- request target (RFC 9112 §3.2, RFC 3986)
                arguments("target is neither origin nor absolute", "GET foo HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("fragment in target", "GET /a#frag HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("invalid percent-escape", "GET /%zz HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("truncated percent-escape", "GET /a%2 HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("escape decodes to invalid UTF-8", "GET /%ff%fe HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("overlong UTF-8 encoding of '/'", "GET /%c0%af HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("encoded NUL in path", "GET /a%00.txt HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("raw non-ASCII byte in target", "GET /café HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("raw backslash in path", "GET /a\\b HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("raw control character in target", "GET /a\u0001b HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("invalid escape in query", "GET /?a=%g1 HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("asterisk-form with GET", "GET * HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("absolute-form with unsupported scheme", "GET ftp://a/x HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("absolute-form with empty authority", "GET http:///x HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("absolute-form with userinfo", "GET http://user:pw@a/x HTTP/1.1\r\n" + HOST + "\r\n", 400),
                arguments("absolute-form with bad port", "GET http://a:80a/x HTTP/1.1\r\n" + HOST + "\r\n", 400),

                // ---- Host (RFC 9112 §3.2)
                arguments("HTTP/1.1 without Host", "GET / HTTP/1.1\r\nAccept: */*\r\n\r\n", 400),
                arguments("two Host fields", "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n", 400),
                arguments("two identical Host fields", "GET / HTTP/1.1\r\nHost: a\r\nHost: a\r\n\r\n", 400),
                arguments("Host with a space", "GET / HTTP/1.1\r\nHost: a b\r\n\r\n", 400),
                arguments("Host with a path", "GET / HTTP/1.1\r\nHost: a/b\r\n\r\n", 400),
                arguments("Host list", "GET / HTTP/1.1\r\nHost: a, b\r\n\r\n", 400),
                arguments("empty Host", "GET / HTTP/1.1\r\nHost:\r\n\r\n", 400),

                // ---- header syntax (RFC 9112 §5)
                arguments("whitespace before the colon", "GET / HTTP/1.1\r\n" + HOST + "X-Test : 1\r\n\r\n", 400),
                arguments("tab before the colon", "GET / HTTP/1.1\r\n" + HOST + "X-Test\t: 1\r\n\r\n", 400),
                arguments("obs-fold continuation line", "GET / HTTP/1.1\r\n" + HOST + "X-Test: 1\r\n  folded\r\n\r\n", 400),
                arguments("tab-led continuation line", "GET / HTTP/1.1\r\n" + HOST + "X-Test: 1\r\n\tfolded\r\n\r\n", 400),
                arguments("whitespace before the first header", "GET / HTTP/1.1\r\n Host: a\r\n\r\n", 400),
                arguments("header line without a colon", "GET / HTTP/1.1\r\n" + HOST + "NoColonHere\r\n\r\n", 400),
                arguments("empty header name", "GET / HTTP/1.1\r\n" + HOST + ": value\r\n\r\n", 400),
                arguments("separator in header name", "GET / HTTP/1.1\r\n" + HOST + "X(Test): 1\r\n\r\n", 400),
                arguments("non-ASCII header name", "GET / HTTP/1.1\r\n" + HOST + "X-Tést: 1\r\n\r\n", 400),
                arguments("NUL in header value", "GET / HTTP/1.1\r\n" + HOST + "X: a\u0000b\r\n\r\n", 400),
                arguments("DEL in header value", "GET / HTTP/1.1\r\n" + HOST + "X: a\u007fb\r\n\r\n", 400),

                // ---- Content-Length (RFC 9110 §8.6, RFC 9112 §6.3)
                arguments("Content-Length is not a number", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: abc\r\n\r\n", 400),
                arguments("Content-Length with a plus sign", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: +5\r\n\r\nhello", 400),
                arguments("negative Content-Length", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: -1\r\n\r\n", 400),
                arguments("hexadecimal Content-Length", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: 0x5\r\n\r\nhello", 400),
                arguments("Content-Length with inner space", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: 1 0\r\n\r\n", 400),
                arguments("empty Content-Length", "POST / HTTP/1.1\r\n" + HOST + "Content-Length:\r\n\r\n", 400),
                arguments("Content-Length list with empty member", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: 5,\r\n\r\nhello", 400),
                arguments("Content-Length overflowing a long", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: 99999999999999999999\r\n\r\n", 400),
                arguments("two different Content-Length fields", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: 5\r\nContent-Length: 6\r\n\r\nhello!", 400),
                arguments("different values in one Content-Length", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: 5, 6\r\n\r\nhello!", 400),
                arguments("Content-Length above the body limit", "POST / HTTP/1.1\r\n" + HOST + "Content-Length: 33\r\n\r\n", 413),

                // ---- Transfer-Encoding (RFC 9112 §6.1)
                arguments("Transfer-Encoding and Content-Length (CL.TE)", "POST / HTTP/1.1\r\n" + HOST
                        + "Content-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400),
                arguments("Transfer-Encoding and Content-Length (TE.CL)", "POST / HTTP/1.1\r\n" + HOST
                        + "Transfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n0\r\n\r\n", 400),
                arguments("obfuscated Transfer-Encoding name", "POST / HTTP/1.1\r\n" + HOST
                        + "Transfer-Encoding : chunked\r\nContent-Length: 4\r\n\r\nabcd", 400),
                arguments("Transfer-Encoding: gzip", "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding: gzip\r\n\r\n", 501),
                arguments("Transfer-Encoding: gzip, chunked", "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n", 501),
                arguments("Transfer-Encoding: identity", "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding: identity\r\n\r\n", 501),
                arguments("Transfer-Encoding: xchunked", "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding: xchunked\r\n\r\n0\r\n\r\n", 501),
                arguments("Transfer-Encoding: chunked with parameter", "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked;q=1\r\n\r\n0\r\n\r\n", 501),
                arguments("chunked applied twice in one field", "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked, chunked\r\n\r\n0\r\n\r\n", 400),
                arguments("chunked applied twice in two fields", "POST / HTTP/1.1\r\n" + HOST
                        + "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400),
                arguments("empty Transfer-Encoding", "POST / HTTP/1.1\r\n" + HOST + "Transfer-Encoding:\r\n\r\n", 400),
                arguments("Transfer-Encoding in HTTP/1.0", "POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400),

                // ---- chunked framing (RFC 9112 §7.1)
                arguments("chunk size is not hexadecimal", CHUNKED_HEAD + "zz\r\nab\r\n0\r\n\r\n", 400),
                arguments("empty chunk-size line", CHUNKED_HEAD + "\r\nab\r\n0\r\n\r\n", 400),
                arguments("negative chunk size", CHUNKED_HEAD + "-2\r\nab\r\n0\r\n\r\n", 400),
                arguments("chunk size with 0x prefix", CHUNKED_HEAD + "0x2\r\nab\r\n0\r\n\r\n", 400),
                arguments("chunk size with leading space", CHUNKED_HEAD + " 2\r\nab\r\n0\r\n\r\n", 400),
                arguments("chunk size with trailing space", CHUNKED_HEAD + "2 \r\nab\r\n0\r\n\r\n", 400),
                arguments("chunk size of sixteen hex digits", CHUNKED_HEAD + "1000000000000002\r\nab\r\n0\r\n\r\n", 400),
                arguments("chunk size line ends in bare LF", CHUNKED_HEAD + "2\nab\r\n0\r\n\r\n", 400),
                arguments("control character in chunk extension", CHUNKED_HEAD + "2;a=\u0001\r\nab\r\n0\r\n\r\n", 400),
                arguments("chunk data longer than its size", CHUNKED_HEAD + "2\r\nabc\r\n0\r\n\r\n", 400),
                arguments("chunk data ends in bare LF", CHUNKED_HEAD + "2\r\nab\n0\r\n\r\n", 400),
                arguments("chunk-size line too long", CHUNKED_HEAD + "2;" + "x".repeat(40) + "\r\nab\r\n0\r\n\r\n", 400),
                arguments("chunked content above the body limit", CHUNKED_HEAD + "21\r\n" + "x".repeat(33) + "\r\n0\r\n\r\n", 413),
                arguments("chunks adding up beyond the body limit", CHUNKED_HEAD + "10\r\n" + "x".repeat(16) + "\r\n10\r\n"
                        + "x".repeat(16) + "\r\n1\r\nx\r\n0\r\n\r\n", 413),
                arguments("obs-fold in trailers", CHUNKED_HEAD + "0\r\nX-A: 1\r\n folded\r\n\r\n", 400),
                arguments("trailer without a colon", CHUNKED_HEAD + "0\r\nnot a field\r\n\r\n", 400),
                arguments("trailer section too large", CHUNKED_HEAD + "0\r\nX-A: " + "v".repeat(300) + "\r\n\r\n", 431),

                // ---- Expect (RFC 9110 §10.1.1)
                arguments("unknown expectation", "POST / HTTP/1.1\r\n" + HOST + "Expect: 200-ok\r\nContent-Length: 2\r\n\r\nab", 417),

                // ---- limits
                arguments("request target too long", "GET /" + "a".repeat(80) + " HTTP/1.1\r\n" + HOST + "\r\n", 414),
                arguments("endless method", "A".repeat(80), 501),
                arguments("one header line too long", "GET / HTTP/1.1\r\n" + HOST + "X-Big: " + "v".repeat(300) + "\r\n\r\n", 431),
                arguments("header section too large in total", "GET / HTTP/1.1\r\n" + HOST
                        + "A: " + "v".repeat(90) + "\r\nB: " + "v".repeat(90) + "\r\nC: " + "v".repeat(90) + "\r\n\r\n", 431),
                arguments("too many header fields", "GET / HTTP/1.1\r\n" + HOST + "A: 1\r\n".repeat(8) + "\r\n", 431)
        );
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("malformed")
    void isRejectedWithTheExpectedStatus(String name, String raw, int expectedStatus) {
        HttpParseException whole = parseExpectingFailure(raw, raw.length());
        assertThat(whole.status()).as(whole.getMessage()).isEqualTo(expectedStatus);
        assertThat(whole.getMessage()).isNotBlank();
        assertThat(whole.reference()).as("every rejection cites the rule behind it").startsWith("RFC ");
    }

    @ParameterizedTest(name = "{0} -> {2} (byte at a time)")
    @MethodSource("malformed")
    void isRejectedTheSameWayWhenDeliveredOneByteAtATime(String name, String raw, int expectedStatus) {
        HttpParseException whole = parseExpectingFailure(raw, raw.length());
        HttpParseException dripped = parseExpectingFailure(raw, 1);

        assertThat(dripped.status()).isEqualTo(expectedStatus);
        assertThat(dripped.getMessage()).isEqualTo(whole.getMessage());
    }

    @Test
    void smugglingRejectionsCiteRfc9112() {
        assertThat(parseExpectingFailure("POST / HTTP/1.1\r\n" + HOST
                + "Content-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n", 1000).reference()).isEqualTo("RFC 9112 §6.1");
        assertThat(parseExpectingFailure("POST / HTTP/1.1\r\n" + HOST
                + "Content-Length: 4\r\nContent-Length: 5\r\n\r\n", 1000).reference()).isEqualTo("RFC 9112 §6.3");
        assertThat(parseExpectingFailure("GET / HTTP/1.1\r\n" + HOST + "X : 1\r\n\r\n", 1000).reference())
                .isEqualTo("RFC 9112 §5.1");
        assertThat(parseExpectingFailure("GET / HTTP/1.1\r\n" + HOST + "X: 1\r\n folded\r\n\r\n", 1000).reference())
                .isEqualTo("RFC 9112 §5.2");
        assertThat(parseExpectingFailure("GET / HTTP/1.1\n", 1000).reference()).isEqualTo("RFC 9112 §2.2");
    }

    @Test
    void anOversizedLineIsRejectedAsSoonAsTheLimitIsPassedNotWhenItEnds() {
        RequestParser parser = new RequestParser(LIMITS);
        byte[] flood = bytes("GET /" + "a".repeat(10_000));
        ByteBuffer in = ByteBuffer.wrap(flood);

        try {
            parser.feed(in);
            fail("expected 414");
        } catch (HttpParseException e) {
            assertThat(e.status()).isEqualTo(414);
        }
        assertThat(in.position()).as("bytes consumed before giving up").isLessThanOrEqualTo(LIMITS.maxRequestLineLength() + 2);
    }

    private static HttpParseException parseExpectingFailure(String raw, int segmentSize) {
        RequestParser parser = new RequestParser(LIMITS);
        byte[] data = bytes(raw);
        try {
            for (int off = 0; off < data.length; off += segmentSize) {
                ByteBuffer segment = ByteBuffer.wrap(data, off, Math.min(segmentSize, data.length - off));
                while (segment.hasRemaining()) {
                    if (parser.feed(segment)) {
                        return fail("parsed successfully: " + parser.request());
                    }
                }
            }
        } catch (HttpParseException e) {
            return e;
        }
        return fail("no error; parser is waiting for more input in phase " + parser.phase());
    }
}
