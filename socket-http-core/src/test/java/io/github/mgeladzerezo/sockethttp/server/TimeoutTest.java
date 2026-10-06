package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read and write deadlines: the defences against clients that are slow on purpose.
 */
@Timeout(30)
class TimeoutTest {

    private static final String HOST = "Host: test\r\n";

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void anUnfinishedHeaderSectionGets408(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.headerTimeout(Duration.ofMillis(300)), TestRoutes::install);
             RawClient client = server.connect()) {
            long start = System.nanoTime();
            client.send("GET /hello HTTP/1.1\r\n" + HOST);

            RawResponse response = client.readResponse();
            assertThat(response.status()).isEqualTo(408);
            assertThat(response.header("Connection")).isEqualTo("close");
            assertThat(elapsed(start)).isBetween(Duration.ofMillis(250), Duration.ofSeconds(3));
            assertThat(client.isClosedByServer(3_000)).isTrue();
            assertThat(server.server().metrics().snapshot().requestTimeouts()).isEqualTo(1);
        }
    }

    /**
     * The slowloris attack: keep the connection "active" by sending one more header byte
     * shortly before any idle timer would fire. The header deadline is absolute, so the
     * steady trickle does not extend it.
     */
    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aClientDrippingHeaderBytesIsCutOffAtTheHeaderDeadline(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.headerTimeout(Duration.ofMillis(500)), TestRoutes::install);
             RawClient client = server.connect()) {
            long start = System.nanoTime();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger bytesDripped = new AtomicInteger();
            Thread dripper = Thread.ofPlatform().start(() -> {
                try {
                    client.send("GET /hello HTTP/1.1\r\n" + HOST + "X-Slow: ");
                    while (!stop.get()) {
                        client.send("a");
                        bytesDripped.incrementAndGet();
                        Thread.sleep(40);
                    }
                } catch (IOException | InterruptedException e) {
                    // the server closed the connection, as it should
                }
            });

            RawResponse response = client.readResponse();
            Duration took = elapsed(start);
            stop.set(true);
            dripper.join();

            assertThat(response.status()).isEqualTo(408);
            assertThat(bytesDripped.get()).as("the client really was sending the whole time").isGreaterThan(5);
            assertThat(took).isBetween(Duration.ofMillis(450), Duration.ofSeconds(3));
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void anUnfinishedBodyGets408(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.bodyTimeout(Duration.ofMillis(300)), TestRoutes::install);
             RawClient client = server.connect()) {
            client.send("POST /echo HTTP/1.1\r\n" + HOST + "Content-Length: 10\r\n\r\nabc");

            RawResponse response = client.readResponse();
            assertThat(response.status()).isEqualTo(408);
            assertThat(response.text()).contains("request content");
            assertThat(client.isClosedByServer(3_000)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void anUnfinishedChunkedBodyGets408(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.bodyTimeout(Duration.ofMillis(300)), TestRoutes::install);
             RawClient client = server.connect()) {
            client.send("POST /echo HTTP/1.1\r\n" + HOST + "Transfer-Encoding: chunked\r\n\r\n5\r\nhel");

            assertThat(client.readResponse().status()).isEqualTo(408);
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aConnectionThatNeverSendsAnythingIsClosedWithoutAResponse(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.headerTimeout(Duration.ofMillis(300)), TestRoutes::install);
             RawClient client = server.connect()) {
            long start = System.nanoTime();

            assertThat(client.readUntilClose()).isEmpty();
            assertThat(elapsed(start)).isBetween(Duration.ofMillis(250), Duration.ofSeconds(3));
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aRequestThatArrivesInTimeIsNotAffectedByShortDeadlines(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model,
                c -> c.headerTimeout(Duration.ofMillis(400)).bodyTimeout(Duration.ofMillis(400)), TestRoutes::install);
             RawClient client = server.connect()) {
            // The handler takes longer than both read deadlines; they only govern reading.
            client.send("GET /slow?ms=700&id=ok HTTP/1.1\r\n" + HOST + "\r\n");

            assertThat(client.readResponse().text()).isEqualTo("slow ok");
        }
    }

    /**
     * The mirror image of slowloris: request a large response and never read it. The server's
     * send buffer fills, the write stalls, and after the write timeout the connection is dropped
     * so that it stops holding a worker.
     */
    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aClientThatStopsReadingIsDroppedAfterTheWriteTimeout(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.writeTimeout(Duration.ofMillis(400)), s ->
                s.get("/endless", ctx -> ctx.stream("application/octet-stream", sink -> {
                    byte[] block = new byte[64 * 1024];
                    for (int i = 0; i < 4096; i++) {
                        sink.write(block);
                    }
                })));
             RawClient client = server.connect()) {
            client.socket().setReceiveBufferSize(8 * 1024);
            client.send("GET /endless HTTP/1.1\r\n" + HOST + "\r\n");

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (server.server().metrics().connectionsActive() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertThat(server.server().metrics().connectionsActive())
                    .as("the stalled connection was closed by the server").isZero();
            assertThat(server.server().metrics().requestsInFlight()).isZero();
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
