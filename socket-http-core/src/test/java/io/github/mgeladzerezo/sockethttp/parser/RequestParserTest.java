package io.github.mgeladzerezo.sockethttp.parser;

import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpVersion;
import io.github.mgeladzerezo.sockethttp.http.RequestTarget;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.github.mgeladzerezo.sockethttp.parser.ParserTestSupport.bytes;
import static io.github.mgeladzerezo.sockethttp.parser.ParserTestSupport.parseOne;
import static org.assertj.core.api.Assertions.assertThat;

/** What a well-formed request parses to. Rejections live in {@link MalformedRequestTest}. */
class RequestParserTest {

    @Test
    void parsesRequestLineAndHeaders() throws Exception {
        HttpRequest r = parseOne("GET /users/42?verbose=1 HTTP/1.1\r\nHost: example.org\r\nAccept: */*\r\n\r\n");

        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.path()).isEqualTo("/users/42");
        assertThat(r.target().form()).isEqualTo(RequestTarget.Form.ORIGIN);
        assertThat(r.target().segments()).containsExactly("users", "42");
        assertThat(r.queryParam("verbose")).contains("1");
        assertThat(r.version()).isEqualTo(HttpVersion.HTTP_1_1);
        assertThat(r.header("host")).contains("example.org");
        assertThat(r.body()).isEmpty();
        assertThat(r.requestLine()).isEqualTo("GET /users/42?verbose=1 HTTP/1.1");
    }

    @Test
    void headerNamesAreCaseInsensitiveAndRepeatedFieldsAreKeptInOrder() throws Exception {
        HttpRequest r = parseOne("GET / HTTP/1.1\r\nHOST: a\r\nAccept: text/html\r\naccept: application/json\r\n"
                + "X-Empty:\r\nX-Padded: \t spaced out \t\r\n\r\n");

        assertThat(r.header("Host")).contains("a");
        assertThat(r.headers().all("ACCEPT")).containsExactly("text/html", "application/json");
        assertThat(r.headers().tokens("accept")).containsExactly("text/html", "application/json");
        assertThat(r.header("x-empty")).contains("");
        assertThat(r.header("x-padded")).contains("spaced out");
        assertThat(r.headers().names()).containsExactly("host", "accept", "x-empty", "x-padded");
    }

    @Test
    void headerValuesMayContainObsTextAndTabs() throws Exception {
        HttpRequest r = parseOne("GET / HTTP/1.1\r\nHost: a\r\nX-Latin: café\tbar\r\n\r\n");

        assertThat(r.header("X-Latin")).contains("café\tbar");
    }

    @Test
    void readsContentLengthBody() throws Exception {
        HttpRequest r = parseOne("POST /submit HTTP/1.1\r\nHost: a\r\nContent-Type: text/plain; charset=ISO-8859-1\r\n"
                + "Content-Length: 5\r\n\r\ncafé!");

        assertThat(r.body()).hasSize(5);
        assertThat(r.bodyAsString()).isEqualTo("café!");
        assertThat(r.contentType()).contains("text/plain");
    }

    @Test
    void identicalRepeatedContentLengthIsAccepted() throws Exception {
        assertThat(parseOne("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\nabc").body())
                .isEqualTo(bytes("abc"));
        assertThat(parseOne("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3, 3\r\n\r\nabc").body())
                .isEqualTo(bytes("abc"));
    }

    @Test
    void decodesChunkedBodyWithExtensionsAndTrailers() throws Exception {
        HttpRequest r = parseOne("POST /upload HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\nTrailer: X-Checksum\r\n\r\n"
                + "5\r\nhello\r\n"
                + "6;name=value;flag\r\n world\r\n"
                + "A ; quoted=\"a b\"\r\n0123456789\r\n"
                + "0\r\nX-Checksum: abc123\r\nContent-Length: 999\r\nHost: evil\r\n\r\n");

        assertThat(r.bodyAsString()).isEqualTo("hello world0123456789");
        // Trailers that could change framing or routing are dropped (RFC 9110 §6.5.1).
        assertThat(r.trailers().names()).containsExactly("x-checksum");
        assertThat(r.trailers().get("X-Checksum")).isEqualTo("abc123");
        assertThat(r.header("Host")).contains("a");
    }

    @Test
    void chunkedTransferEncodingNameIsCaseInsensitive() throws Exception {
        HttpRequest r = parseOne("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding:\tChunked \r\n\r\n3\r\nabc\r\n0\r\n\r\n");

        assertThat(r.bodyAsString()).isEqualTo("abc");
    }

    @Test
    void emptyChunkedBody() throws Exception {
        HttpRequest r = parseOne("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");

        assertThat(r.body()).isEmpty();
        assertThat(r.trailers().isEmpty()).isTrue();
    }

    @Test
    void acceptsAbsoluteFormAndPrefersItsAuthorityOverHost() throws Exception {
        HttpRequest r = parseOne("GET http://Example.org:8080/a/b?x=1 HTTP/1.1\r\nHost: ignored.example\r\n\r\n");

        assertThat(r.target().form()).isEqualTo(RequestTarget.Form.ABSOLUTE);
        assertThat(r.target().scheme()).isEqualTo("http");
        assertThat(r.host()).isEqualTo("Example.org:8080");
        assertThat(r.path()).isEqualTo("/a/b");
        assertThat(r.queryParam("x")).contains("1");
    }

    @Test
    void absoluteFormWithoutPathMeansRoot() throws Exception {
        assertThat(parseOne("GET http://example.org HTTP/1.1\r\nHost: example.org\r\n\r\n").path()).isEqualTo("/");
        HttpRequest withQuery = parseOne("GET http://example.org?q=1 HTTP/1.1\r\nHost: example.org\r\n\r\n");
        assertThat(withQuery.path()).isEqualTo("/");
        assertThat(withQuery.queryParam("q")).contains("1");
    }

    @Test
    void acceptsAsteriskFormForOptionsAndAuthorityFormForConnect() throws Exception {
        assertThat(parseOne("OPTIONS * HTTP/1.1\r\nHost: a\r\n\r\n").target().form())
                .isEqualTo(RequestTarget.Form.ASTERISK);
        HttpRequest connect = parseOne("CONNECT example.org:443 HTTP/1.1\r\nHost: example.org:443\r\n\r\n");
        assertThat(connect.target().form()).isEqualTo(RequestTarget.Form.AUTHORITY);
        assertThat(connect.target().authority()).isEqualTo("example.org:443");
    }

    @Test
    void percentDecodesPathSegmentsAfterSplittingOnSlash() throws Exception {
        HttpRequest r = parseOne("GET /files/a%2Fb/caf%C3%A9%20menu+1/ HTTP/1.1\r\nHost: a\r\n\r\n");

        // %2F stays inside its segment; '+' is a literal plus in a path.
        assertThat(r.target().segments()).containsExactly("files", "a/b", "café menu+1", "");
        assertThat(r.target().rawPath()).isEqualTo("/files/a%2Fb/caf%C3%A9%20menu+1/");
    }

    @Test
    void parsesQueryParameters() throws Exception {
        HttpRequest r = parseOne("GET /search?q=caf%C3%A9+au+lait&tag=a&tag=b&flag&empty=&&json={\"k\":[1]} HTTP/1.1\r\nHost: a\r\n\r\n");

        assertThat(r.queryParam("q")).contains("café au lait");
        assertThat(r.queryParams("tag")).containsExactly("a", "b");
        assertThat(r.queryParam("flag")).contains("");
        assertThat(r.queryParam("empty")).contains("");
        assertThat(r.queryParam("json")).contains("{\"k\":[1]}");
        assertThat(r.queryParam("missing")).isEmpty();
        assertThat(r.query().keySet()).containsExactly("q", "tag", "flag", "empty", "json");
    }

    @Test
    void http10RequestNeedsNoHost() throws Exception {
        HttpRequest r = parseOne("GET /old HTTP/1.0\r\n\r\n");

        assertThat(r.version()).isEqualTo(HttpVersion.HTTP_1_0);
        assertThat(r.host()).isNull();
    }

    @Test
    void higherMinorVersionIsProcessedAsHttp11() throws Exception {
        HttpRequest r = parseOne("GET / HTTP/1.2\r\nHost: a\r\n\r\n");

        assertThat(r.version()).isEqualTo(HttpVersion.HTTP_1_1);
        assertThat(r.requestLine()).endsWith("HTTP/1.2");
    }

    @Test
    void ignoresEmptyLinesBeforeTheRequestLine() throws Exception {
        assertThat(parseOne("\r\n\r\nGET / HTTP/1.1\r\nHost: a\r\n\r\n").method()).isEqualTo("GET");
    }

    @Test
    void unknownButWellFormedMethodsAreParsed() throws Exception {
        // Whether to answer 501 or 405 is the server's decision, not a syntax error.
        assertThat(parseOne("PROPFIND / HTTP/1.1\r\nHost: a\r\n\r\n").method()).isEqualTo("PROPFIND");
    }

    @Test
    void leavesPipelinedBytesInTheBuffer() throws Exception {
        RequestParser parser = new RequestParser();
        ByteBuffer in = ByteBuffer.wrap(bytes(
                "POST /1 HTTP/1.1\r\nHost: a\r\nContent-Length: 2\r\n\r\nhiGET /2 HTTP/1.1\r\nHost: a\r\n\r\n"));

        assertThat(parser.feed(in)).isTrue();
        assertThat(parser.request().path()).isEqualTo("/1");
        assertThat(parser.request().bodyAsString()).isEqualTo("hi");
        assertThat(new String(in.array(), in.position(), 6, StandardCharsets.US_ASCII)).isEqualTo("GET /2");

        parser.reset();
        assertThat(parser.feed(in)).isTrue();
        assertThat(parser.request().path()).isEqualTo("/2");
        assertThat(in.hasRemaining()).isFalse();
    }

    @Test
    void reportsPhaseAsBytesArrive() throws Exception {
        RequestParser parser = new RequestParser();
        assertThat(parser.phase()).isEqualTo(RequestParser.Phase.IDLE);

        parser.feed(ByteBuffer.wrap(bytes("\r\n")));
        assertThat(parser.phase()).as("an ignored empty line is not a request").isEqualTo(RequestParser.Phase.IDLE);

        parser.feed(ByteBuffer.wrap(bytes("PO")));
        assertThat(parser.phase()).isEqualTo(RequestParser.Phase.HEAD);

        parser.feed(ByteBuffer.wrap(bytes("ST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\n")));
        assertThat(parser.phase()).isEqualTo(RequestParser.Phase.BODY);

        assertThat(parser.feed(ByteBuffer.wrap(bytes("abc")))).isTrue();
        assertThat(parser.phase()).isEqualTo(RequestParser.Phase.COMPLETE);
    }

    @Test
    void expect100ContinueIsSignalledOnceBetweenHeadAndBody() throws Exception {
        RequestParser parser = new RequestParser();
        assertThat(parser.feed(ByteBuffer.wrap(bytes(
                "PUT /doc HTTP/1.1\r\nHost: a\r\nExpect: 100-Continue\r\nContent-Length: 4\r\n\r\n")))).isFalse();
        assertThat(parser.awaitingContinue()).isTrue();

        parser.continueSent();
        assertThat(parser.awaitingContinue()).isFalse();
        assertThat(parser.feed(ByteBuffer.wrap(bytes("data")))).isTrue();
        assertThat(parser.request().bodyAsString()).isEqualTo("data");
    }

    @Test
    void expectIsNotSignalledWhenTheBodyAlreadyArrivedOrTheClientIsHttp10() throws Exception {
        RequestParser eager = new RequestParser();
        eager.feed(ByteBuffer.wrap(bytes("PUT / HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\nda")));
        assertThat(eager.awaitingContinue()).isFalse();

        RequestParser old = new RequestParser();
        old.feed(ByteBuffer.wrap(bytes("PUT / HTTP/1.0\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\n")));
        assertThat(old.awaitingContinue()).isFalse();
    }

    @Test
    void largeBodyGrowsBeyondTheInitialBuffer() throws Exception {
        byte[] content = new byte[300_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        byte[] head = bytes("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: " + content.length + "\r\n\r\n");
        RequestParser parser = new RequestParser();
        assertThat(parser.feed(ByteBuffer.wrap(head))).isFalse();
        for (int off = 0; off < content.length; off += 7001) {
            parser.feed(ByteBuffer.wrap(content, off, Math.min(7001, content.length - off)));
        }

        assertThat(parser.isComplete()).isTrue();
        assertThat(parser.request().body()).isEqualTo(content);
    }

    @Test
    void limitsAreInclusive() throws Exception {
        ParserLimits limits = ParserLimits.DEFAULT.withMaxRequestLineLength(32).withMaxBodySize(4);
        String line = "GET /" + "a".repeat(32 - "GET / HTTP/1.1".length()) + " HTTP/1.1";
        assertThat(line).hasSize(32);

        assertThat(parseOne(line + "\r\nHost: a\r\nContent-Length: 4\r\n\r\nabcd", limits).body()).hasSize(4);
        assertThat(parseOne("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n", limits)
                .body()).hasSize(4);
    }

    @Test
    void aReusedParserKeepsNothingFromThePreviousRequest() throws Exception {
        RequestParser parser = new RequestParser();
        parser.feed(ByteBuffer.wrap(bytes("GET /a?x=1 HTTP/1.1\r\nHost: a\r\nX-One: 1\r\n\r\n")));
        HttpRequest first = parser.request();
        parser.reset();
        parser.feed(ByteBuffer.wrap(bytes("GET /b HTTP/1.1\r\nHost: b\r\n\r\n")));

        assertThat(first.path()).isEqualTo("/a");
        assertThat(first.headers().all("X-One")).isEqualTo(List.of("1"));
        assertThat(parser.request().headers().contains("X-One")).isFalse();
        assertThat(parser.request().query()).isEmpty();
    }
}
