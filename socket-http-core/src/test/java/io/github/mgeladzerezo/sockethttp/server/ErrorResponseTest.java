package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.parser.ParserLimits;
import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * What a client sees when it sends something the parser refuses: the right status, an
 * explanation that names the RFC section, {@code Connection: close}, and then a closed
 * connection on which nothing that followed the bad request is ever interpreted.
 *
 * <p>The exhaustive table of malformed inputs lives in the parser's unit tests; this class
 * takes one representative per status code and per smuggling vector through a real socket
 * and every concurrency model.
 */
@Timeout(30)
class ErrorResponseTest {

    private static final String HOST = "Host: test\r\n";
    private static final ParserLimits SMALL = new ParserLimits(256, 1024, 16, 2048, 64);

    record Case(String name, String request, int status, String reference) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> cases() {
        return List.of(
                new Case("400 bare LF line ending", "GET /hello HTTP/1.1\n" + HOST + "\n", 400, "RFC 9112 §2.2"),
                new Case("400 malformed request line", "GET  /hello  HTTP/1.1\r\n" + HOST + "\r\n", 400, "RFC 9112 §3"),
                new Case("400 missing Host", "GET /hello HTTP/1.1\r\n\r\n", 400, "RFC 9112 §3.2"),
                new Case("400 obsolete line folding", "GET /hello HTTP/1.1\r\n" + HOST + "X: a\r\n b\r\n\r\n", 400, "RFC 9112 §5.2"),
                new Case("400 invalid percent-encoding", "GET /%zz HTTP/1.1\r\n" + HOST + "\r\n", 400, "RFC 3986 §2.1"),
                new Case("400 bad chunk size", "POST /echo HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked\r\n\r\nxyz\r\n", 400, "RFC 9112 §7.1"),
                new Case("413 Content-Length over the limit", "POST /echo HTTP/1.1\r\n" + HOST + "Content-Length: 2049\r\n\r\n", 413, "RFC 9110 §15.5.14"),
                new Case("413 chunked body over the limit", "POST /echo HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked\r\n\r\n"
                        + "800\r\n" + "x".repeat(2048) + "\r\n1\r\nx\r\n0\r\n\r\n", 413, "RFC 9110 §15.5.14"),
                new Case("414 request target too long", "GET /" + "a".repeat(400) + " HTTP/1.1\r\n" + HOST + "\r\n", 414, "RFC 9112 §3"),
                new Case("417 unknown expectation", "GET /hello HTTP/1.1\r\n" + HOST + "Expect: 42\r\n\r\n", 417, "RFC 9110 §10.1.1"),
                new Case("431 header section too large", "GET /hello HTTP/1.1\r\n" + HOST + "X-Big: " + "v".repeat(1100) + "\r\n\r\n", 431, "RFC 6585 §5"),
                new Case("431 too many header fields", "GET /hello HTTP/1.1\r\n" + HOST + "X: 1\r\n".repeat(16) + "\r\n", 431, "RFC 6585 §5"),
                new Case("501 unknown transfer coding", "POST /echo HTTP/1.1\r\n" + HOST + "Transfer-Encoding: gzip, chunked\r\n\r\n", 501, "RFC 9112 §6.1"),
                new Case("505 HTTP/2.0 request line", "GET /hello HTTP/2.0\r\n" + HOST + "\r\n", 505, "RFC 9110 §15.6.6"),

                // Request smuggling vectors (see the README table).
                new Case("smuggling: Content-Length with Transfer-Encoding", "POST /echo HTTP/1.1\r\n" + HOST
                        + "Content-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400, "RFC 9112 §6.1"),
                new Case("smuggling: Transfer-Encoding with Content-Length", "POST /echo HTTP/1.1\r\n" + HOST
                        + "Transfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n0\r\n\r\n", 400, "RFC 9112 §6.1"),
                new Case("smuggling: two different Content-Length fields", "POST /echo HTTP/1.1\r\n" + HOST
                        + "Content-Length: 0\r\nContent-Length: 44\r\n\r\n", 400, "RFC 9112 §6.3"),
                new Case("smuggling: Content-Length list with different values", "POST /echo HTTP/1.1\r\n" + HOST
                        + "Content-Length: 0, 44\r\n\r\n", 400, "RFC 9112 §6.3"),
                new Case("smuggling: whitespace before the colon", "POST /echo HTTP/1.1\r\n" + HOST
                        + "Transfer-Encoding : chunked\r\nContent-Length: 4\r\n\r\nabcd", 400, "RFC 9112 §5.1"),
                new Case("smuggling: Transfer-Encoding in HTTP/1.0", "POST /echo HTTP/1.0\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400, "RFC 9112 §6.1"),
                new Case("smuggling: chunked applied twice", "POST /echo HTTP/1.1\r\n" + HOST
                        + "Transfer-Encoding: chunked, chunked\r\n\r\n0\r\n\r\n", 400, "RFC 9112 §6.1"),
                new Case("smuggling: bare LF inside chunk framing", "POST /echo HTTP/1.1\r\n" + HOST
                        + "Transfer-Encoding: chunked\r\n\r\n3\nabc\r\n0\r\n\r\n", 400, "RFC 9112 §2.2")
        );
    }

    static Stream<Arguments> everyCaseOnEveryModel() {
        List<Arguments> all = new ArrayList<>();
        for (ConcurrencyModel model : ConcurrencyModel.values()) {
            for (Case c : cases()) {
                all.add(arguments(model, c));
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("everyCaseOnEveryModel")
    void rejectsWithStatusReferenceAndClose(ConcurrencyModel model, Case c) throws Exception {
        try (TestServer server = TestServer.start(model, cfg -> cfg.limits(SMALL), TestRoutes::install);
             RawClient client = server.connect()) {
            // A second, perfectly valid request follows in the same write. If the server were to
            // answer it, the bad message's framing would have been "repaired" by guessing.
            client.send(c.request() + "GET /echo/smuggled HTTP/1.1\r\n" + HOST + "\r\n");

            RawResponse response = client.readResponse();
            assertThat(response.status()).isEqualTo(c.status());
            assertThat(response.header("Connection")).isEqualTo("close");
            assertThat(response.text()).contains(c.reference());
            assertThat(client.readUntilClose()).as("no second response on the connection").isEmpty();

            assertThat(server.server().metrics().snapshot().requestsTotal())
                    .as("no handler ran, not even for the trailing request").isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void theServerKeepsServingOtherConnectionsAfterRejectingOne(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install)) {
            try (RawClient bad = server.connect()) {
                bad.send("NOT A REQUEST\r\n\r\n");
                assertThat(bad.readResponse().status()).isEqualTo(400);
            }
            try (RawClient good = server.connect()) {
                assertThat(good.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");
            }
            assertThat(server.server().metrics().snapshot().requestsRejected()).isEqualTo(1);
            assertThat(server.server().metrics().snapshot().responses4xx()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aMalformedHttp10RequestIsStillAnswered(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            client.send("GET /hello HTTP/1.0\r\nBroken Header\r\n\r\n");

            RawResponse response = client.readResponse();
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.header("Content-Length")).as("an HTTP/1.0 client cannot read chunks").isNotNull();
        }
    }
}
