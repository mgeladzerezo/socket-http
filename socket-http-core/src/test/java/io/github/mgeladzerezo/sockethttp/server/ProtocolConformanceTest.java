package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.http.HttpDates;
import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP/1.1 behaviour checked through raw sockets, so the test controls every byte sent and
 * sees every byte returned. Each test runs against all three concurrency models.
 */
@Timeout(30)
class ProtocolConformanceTest {

    private static final String HOST = "Host: test\r\n";

    private static TestServer server(ConcurrencyModel model) {
        return TestServer.start(model, TestRoutes::install);
    }

    // ----------------------------------------------------------------- basics

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void getReturnsStatusHeadersAndBody(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse response = client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse();

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.reason()).isEqualTo("OK");
            assertThat(response.version()).isEqualTo("HTTP/1.1");
            assertThat(response.text()).isEqualTo("hello");
            assertThat(response.header("Content-Length")).isEqualTo("5");
            assertThat(response.header("Content-Type")).isEqualTo("text/plain; charset=utf-8");
            assertThat(response.header("Server")).isEqualTo("socket-http");
            assertThat(response.header("Connection")).as("persistent by default in HTTP/1.1").isNull();
            Instant date = HttpDates.parse(response.header("Date"));
            assertThat(date).isNotNull();
            assertThat(Duration.between(date, Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void headHasTheHeadersOfGetAndNoBody(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse head = client.send("HEAD /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse(true);
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.header("Content-Length")).isEqualTo("5");
            assertThat(head.header("Content-Type")).isEqualTo("text/plain; charset=utf-8");

            // If the server had sent a body, it would be read here as a garbled status line.
            RawResponse next = client.send("GET /users/7 HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(next.status()).isEqualTo(200);
            assertThat(next.text()).isEqualTo("{\"id\":\"7\"}");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void headOnAStreamingRouteSendsNoChunks(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse head = client.send("HEAD /stream HTTP/1.1\r\n" + HOST + "\r\n").readResponse(true);
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.header("Content-Length")).isNull();

            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void readsAContentLengthBody(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse response = client.send("POST /echo HTTP/1.1\r\n" + HOST
                    + "Content-Type: text/plain\r\nContent-Length: 11\r\n\r\nhello world").readResponse();

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.text()).isEqualTo("hello world");
            assertThat(response.header("Content-Type")).isEqualTo("text/plain");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void decodesAChunkedRequestWithTrailers(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse response = client.send("POST /echo HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked\r\n\r\n"
                    + "6\r\nhello \r\n5;ext=1\r\nworld\r\n0\r\nChecksum: abc\r\n\r\n").readResponse();

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.text()).isEqualTo("hello world");
            assertThat(response.header("Content-Length")).isEqualTo("11");
            assertThat(response.header("X-Trailer-Checksum")).isEqualTo("abc");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void streamsAResponseOfUnknownLengthAsChunks(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            client.send("GET /stream HTTP/1.1\r\n" + HOST + "\r\n");

            // Read the raw framing: each write of the handler is one chunk, the empty write
            // produces nothing (it must not be mistaken for the terminating chunk).
            assertThat(client.readLine()).isEqualTo("HTTP/1.1 200 OK");
            String line;
            boolean chunked = false;
            boolean hasLength = false;
            while (!(line = client.readLine()).isEmpty()) {
                chunked |= line.equalsIgnoreCase("Transfer-Encoding: chunked");
                hasLength |= line.toLowerCase().startsWith("content-length");
            }
            assertThat(chunked).isTrue();
            assertThat(hasLength).isFalse();
            assertThat(client.readLine()).isEqualTo("3");
            assertThat(client.readLine()).isEqualTo("one");
            assertThat(client.readLine()).isEqualTo("3");
            assertThat(client.readLine()).isEqualTo("two");
            assertThat(client.readLine()).isEqualTo("5");
            assertThat(client.readLine()).isEqualTo("three");
            assertThat(client.readLine()).isEqualTo("0");
            assertThat(client.readLine()).isEmpty();

            // The connection is still usable after a chunked response.
            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void noContentResponseHasNoBodyAndNoContentLength(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse response = client.send("GET /empty HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(response.status()).isEqualTo(204);
            assertThat(response.header("Content-Length")).isNull();
            assertThat(response.header("Transfer-Encoding")).isNull();

            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void largeBodiesSurviveInBothDirections(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse big = client.send("GET /big?size=700000 HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(big.body()).isEqualTo(TestRoutes.pattern(700_000));

            byte[] upload = TestRoutes.pattern(1_500_000);
            client.send("PUT /echo HTTP/1.1\r\n" + HOST + "Content-Length: " + upload.length + "\r\n\r\n");
            // The echo comes back while we are still sending, so read on another thread.
            Thread writer = Thread.ofPlatform().start(() -> {
                try {
                    client.send(upload);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            RawResponse echoed = client.readResponse();
            writer.join();
            assertThat(echoed.body()).isEqualTo(upload);
        }
    }

    // ------------------------------------------------------------ segmentation

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aRequestDeliveredOneByteAtATimeIsParsedTheSame(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            byte[] request = ("POST /echo HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked\r\n\r\n"
                    + "4\r\nwiki\r\n5\r\npedia\r\n0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            for (byte b : request) {
                client.send(new byte[]{b});
            }

            assertThat(client.readResponse().text()).isEqualTo("wikipedia");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aRequestSplitAtAnyPointAcrossTwoSegmentsIsParsedTheSame(ConcurrencyModel model) throws Exception {
        byte[] request = ("POST /echo HTTP/1.1\r\n" + HOST + "Content-Length: 4\r\n\r\nbody")
                .getBytes(StandardCharsets.ISO_8859_1);
        try (TestServer server = server(model); RawClient client = server.connect()) {
            for (int cut = 1; cut < request.length; cut++) {
                client.send(java.util.Arrays.copyOfRange(request, 0, cut));
                if (cut % 8 == 0) {
                    // Give the first segment time to be read on its own now and then.
                    Thread.sleep(2);
                }
                client.send(java.util.Arrays.copyOfRange(request, cut, request.length));
                RawResponse response = client.readResponse();
                assertThat(response.status()).as("cut at byte %d", cut).isEqualTo(200);
                assertThat(response.text()).isEqualTo("body");
            }
        }
    }

    // ------------------------------------------------------------------ targets

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void acceptsAbsoluteFormAndUsesItsAuthority(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse response = client.send("GET http://from-target.example:8080/host HTTP/1.1\r\n"
                    + "Host: from-header.example\r\n\r\n").readResponse();

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.text()).isEqualTo("from-target.example:8080");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void decodesPathAndQuery(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse query = client.send("GET /query?q=caf%C3%A9+au+lait HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(query.text()).isEqualTo("café au lait");

            // An encoded slash stays inside the path parameter instead of changing the route.
            RawResponse param = client.send("GET /users/a%2Fb%20c HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(param.text()).isEqualTo("{\"id\":\"a/b c\"}");
        }
    }

    // ---------------------------------------------------------------- Expect

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void sends100ContinueBeforeReadingTheBody(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            client.send("POST /echo HTTP/1.1\r\n" + HOST + "Expect: 100-continue\r\nContent-Length: 4\r\n\r\n");

            RawResponse interim = client.readResponse();
            assertThat(interim.status()).isEqualTo(100);
            assertThat(interim.headers().isEmpty()).isTrue();

            RawResponse response = client.send("data").readResponse();
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.text()).isEqualTo("data");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void answersAnOversizedExpectRequestWithTheFinalStatusInsteadOf100(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.limits(io.github.mgeladzerezo.sockethttp.parser.ParserLimits.DEFAULT.withMaxBodySize(1000)),
                TestRoutes::install); RawClient client = server.connect()) {
            client.send("POST /echo HTTP/1.1\r\n" + HOST + "Expect: 100-continue\r\nContent-Length: 1001\r\n\r\n");

            RawResponse response = client.readResponse();
            assertThat(response.status()).as("the client never has to send the body").isEqualTo(413);
            assertThat(response.header("Connection")).isEqualTo("close");
            assertThat(client.isClosedByServer(3_000)).isTrue();
        }
    }

    // ------------------------------------------------------------ status codes

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void routingErrorsMapToTheirStatusCodes(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse notFound = client.send("GET /nope HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(notFound.status()).isEqualTo(404);

            RawResponse notAllowed = client.send("DELETE /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(notAllowed.status()).isEqualTo(405);
            assertThat(notAllowed.header("Allow")).isEqualTo("GET, HEAD, OPTIONS");

            RawResponse notImplemented = client.send("BREW /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(notImplemented.status()).isEqualTo(501);

            RawResponse options = client.send("OPTIONS /echo HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(options.status()).isEqualTo(204);
            assertThat(options.header("Allow")).isEqualTo("OPTIONS, POST, PUT");

            RawResponse serverWide = client.send("OPTIONS * HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(serverWide.status()).isEqualTo(204);
            assertThat(serverWide.header("Allow")).contains("GET").contains("POST");

            RawResponse teapot = client.send("GET /teapot HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(teapot.status()).isEqualTo(418);
            assertThat(teapot.text()).contains("short and stout");

            // All of the above were ordinary responses on one persistent connection.
            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aFailingHandlerGives500WithoutLeakingDetailsAndKeepsTheConnection(ConcurrencyModel model) throws Exception {
        try (TestServer server = server(model); RawClient client = server.connect()) {
            RawResponse response = client.send("GET /boom HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.text()).doesNotContain("internal detail");

            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");
        }
    }
}
