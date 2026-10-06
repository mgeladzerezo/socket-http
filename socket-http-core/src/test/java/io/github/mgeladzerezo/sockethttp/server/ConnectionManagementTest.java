package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connection reuse (RFC 9112 §9): persistence, pipelining, {@code Connection: close},
 * HTTP/1.0 semantics, the per-connection request limit and the keep-alive timeout.
 */
@Timeout(30)
class ConnectionManagementTest {

    private static final String HOST = "Host: test\r\n";

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void servesManyRequestsOnOneConnection(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            for (int i = 0; i < 50; i++) {
                RawResponse response = client.send("GET /echo/n" + i + " HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
                assertThat(response.text()).isEqualTo("n" + i);
            }
            assertThat(server.server().metrics().snapshot().connectionsAccepted()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void answersPipelinedRequestsInOrder(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            // Five requests in one write, with a body-carrying POST and a chunked POST in the
            // middle so the parser has to find each boundary itself.
            client.send("GET /echo/first HTTP/1.1\r\n" + HOST + "\r\n"
                    + "POST /echo HTTP/1.1\r\n" + HOST + "Content-Length: 6\r\n\r\nsecond"
                    + "GET /users/3 HTTP/1.1\r\n" + HOST + "\r\n"
                    + "POST /echo HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked\r\n\r\n6\r\nfourth\r\n0\r\n\r\n"
                    + "GET /echo/fifth HTTP/1.1\r\n" + HOST + "\r\n");

            assertThat(client.readResponse().text()).isEqualTo("first");
            assertThat(client.readResponse().text()).isEqualTo("second");
            assertThat(client.readResponse().text()).isEqualTo("{\"id\":\"3\"}");
            assertThat(client.readResponse().text()).isEqualTo("fourth");
            assertThat(client.readResponse().text()).isEqualTo("fifth");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aSlowFirstRequestIsStillAnsweredBeforeTheFastOnesBehindIt(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            client.send("GET /slow?ms=300&id=a HTTP/1.1\r\n" + HOST + "\r\n"
                    + "GET /echo/b HTTP/1.1\r\n" + HOST + "\r\n"
                    + "GET /slow?ms=50&id=c HTTP/1.1\r\n" + HOST + "\r\n"
                    + "GET /echo/d HTTP/1.1\r\n" + HOST + "\r\n");

            assertThat(client.readResponse().text()).isEqualTo("slow a");
            assertThat(client.readResponse().text()).isEqualTo("b");
            assertThat(client.readResponse().text()).isEqualTo("slow c");
            assertThat(client.readResponse().text()).isEqualTo("d");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void connectionCloseIsHonouredAndLaterPipelinedRequestsAreNotServed(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            client.send("GET /echo/last HTTP/1.1\r\n" + HOST + "Connection: close\r\n\r\n"
                    + "GET /echo/never HTTP/1.1\r\n" + HOST + "\r\n");

            RawResponse response = client.readResponse();
            assertThat(response.text()).isEqualTo("last");
            assertThat(response.header("Connection")).isEqualTo("close");
            assertThat(client.readUntilClose()).as("nothing after the response that announced close").isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void http10ClosesAfterTheResponseUnlessKeepAliveIsRequested(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install)) {
            try (RawClient client = server.connect()) {
                RawResponse response = client.send("GET /hello HTTP/1.0\r\n\r\n").readResponse();
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.text()).isEqualTo("hello");
                assertThat(response.header("Connection")).isEqualTo("close");
                assertThat(client.isClosedByServer(3_000)).isTrue();
            }
            try (RawClient client = server.connect()) {
                RawResponse first = client.send("GET /hello HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").readResponse();
                assertThat(first.header("Connection")).isEqualTo("keep-alive");
                RawResponse second = client.send("GET /echo/again HTTP/1.0\r\nConnection: Keep-Alive\r\n\r\n").readResponse();
                assertThat(second.text()).isEqualTo("again");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void http10GetsAStreamDelimitedByCloseBecauseItCannotReadChunks(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            RawResponse response = client.send("GET /stream HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").readResponse();

            assertThat(response.header("Transfer-Encoding")).isNull();
            assertThat(response.header("Content-Length")).isNull();
            assertThat(response.header("Connection")).isEqualTo("close");
            // RawClient read to end-of-stream, since nothing else delimits the body.
            assertThat(response.text()).isEqualTo("onetwothree");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void closesAfterTheConfiguredNumberOfRequests(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.maxRequestsPerConnection(3), TestRoutes::install);
             RawClient client = server.connect()) {
            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().header("Connection")).isNull();
            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().header("Connection")).isNull();
            RawResponse third = client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse();

            assertThat(third.text()).isEqualTo("hello");
            assertThat(third.header("Connection")).isEqualTo("close");
            assertThat(client.isClosedByServer(3_000)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void anIdleConnectionIsClosedSilentlyAfterTheKeepAliveTimeout(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.keepAliveTimeout(Duration.ofMillis(300)), TestRoutes::install);
             RawClient client = server.connect()) {
            assertThat(client.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");

            assertThat(client.isClosedByServer(100)).as("still open well before the timeout").isFalse();
            long start = System.nanoTime();
            // End of stream, not a 408: the server was not in the middle of a request.
            assertThat(client.readUntilClose()).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(server.server().metrics().snapshot().requestTimeouts()).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aStrayCrlfAfterABodyIsIgnored(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            // Some old clients append CRLF after a POST body; RFC 9112 §2.2 asks servers to cope.
            client.send("POST /echo HTTP/1.1\r\n" + HOST + "Content-Length: 3\r\n\r\nabc\r\n"
                    + "GET /hello HTTP/1.1\r\n" + HOST + "\r\n");

            assertThat(client.readResponse().text()).isEqualTo("abc");
            assertThat(client.readResponse().text()).isEqualTo("hello");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aHalfClosedClientStillGetsItsResponse(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install); RawClient client = server.connect()) {
            client.send("GET /slow?ms=100&id=x HTTP/1.1\r\n" + HOST + "\r\n").shutdownOutput();

            assertThat(client.readResponse().text()).isEqualTo("slow x");
            assertThat(client.isClosedByServer(3_000)).isTrue();
        }
    }
}
