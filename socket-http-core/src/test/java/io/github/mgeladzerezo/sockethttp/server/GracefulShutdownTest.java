package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code stop()} must finish what was started and nothing more: in-flight requests complete,
 * idle connections and the listening socket close at once, and a handler that will not finish
 * cannot hold the shutdown hostage beyond the grace period.
 */
@Timeout(30)
class GracefulShutdownTest {

    private static final String HOST = "Host: test\r\n";

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void inFlightRequestsCompleteWhileIdleConnectionsAreClosed(ConcurrencyModel model) throws Exception {
        CountDownLatch handlersRunning = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        TestServer server = TestServer.start(model, c -> c.shutdownGrace(Duration.ofSeconds(10)), s -> {
            TestRoutes.install(s);
            s.get("/work/{id}", ctx -> {
                handlersRunning.countDown();
                release.await(10, TimeUnit.SECONDS);
                ctx.text("done " + ctx.pathParam("id"));
            });
        });
        List<RawClient> busy = new ArrayList<>();
        try (RawClient idle = server.connect()) {
            assertThat(idle.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text()).isEqualTo("hello");
            for (int i = 0; i < 4; i++) {
                busy.add(server.connect().send("GET /work/" + i + " HTTP/1.1\r\n" + HOST + "\r\n"));
            }
            assertThat(handlersRunning.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> stopped = CompletableFuture.supplyAsync(() -> server.server().stop());

            // The idle keep-alive connection is closed right away, without waiting for the others.
            assertThat(idle.readUntilClose()).isEmpty();
            assertThat(stopped).as("stop() waits for the handlers").isNotDone();

            release.countDown();
            for (int i = 0; i < 4; i++) {
                RawResponse response = busy.get(i).readResponse();
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.text()).isEqualTo("done " + i);
                assertThat(response.header("Connection")).as("the client is told not to reuse the connection").isEqualTo("close");
                assertThat(busy.get(i).isClosedByServer(3_000)).isTrue();
            }
            assertThat(stopped.get(10, TimeUnit.SECONDS)).as("clean shutdown").isTrue();
            assertThat(server.server().metrics().connectionsActive()).isZero();
        } finally {
            for (RawClient client : busy) {
                client.close();
            }
            server.close();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void noNewConnectionsAreServedAfterStop(ConcurrencyModel model) throws Exception {
        TestServer server = TestServer.start(model, TestRoutes::install);
        int port = server.port();
        try (RawClient before = server.connect()) {
            assertThat(before.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n").readResponse().status()).isEqualTo(200);
        }

        assertThat(server.server().stop()).isTrue();

        try (RawClient after = new RawClient(port)) {
            after.send("GET /hello HTTP/1.1\r\n" + HOST + "\r\n");
            assertThat(after.isClosedByServer(2_000)).isTrue();
        } catch (ConnectException expected) {
            // the usual outcome: nothing is listening any more
        } catch (IOException e) {
            assertThat(e).hasMessageContaining("reset");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aHandlerThatOutlivesTheGracePeriodIsCutOff(ConcurrencyModel model) throws Exception {
        CountDownLatch handlerRunning = new CountDownLatch(1);
        TestServer server = TestServer.start(model, c -> c.shutdownGrace(Duration.ofMillis(300)), s ->
                s.get("/stuck", ctx -> {
                    handlerRunning.countDown();
                    try {
                        Thread.sleep(20_000);
                    } catch (InterruptedException e) {
                        // interrupted by the forced part of the shutdown
                    }
                    ctx.text("too late");
                }));
        try (RawClient client = server.connect()) {
            client.send("GET /stuck HTTP/1.1\r\n" + HOST + "\r\n");
            assertThat(handlerRunning.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            boolean clean = server.server().stop();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(clean).as("stop() reports that the grace period expired").isFalse();
            assertThat(took).isBetween(Duration.ofMillis(250), Duration.ofSeconds(8));
            assertThat(client.isClosedByServer(5_000)).isTrue();
        } finally {
            server.close();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aRequestWhoseBytesAreStillArrivingIsAllowedToFinish(ConcurrencyModel model) throws Exception {
        TestServer server = TestServer.start(model, c -> c.shutdownGrace(Duration.ofSeconds(10)), TestRoutes::install);
        try (RawClient client = server.connect()) {
            client.send("POST /echo HTTP/1.1\r\n" + HOST + "Content-Length: 8\r\n\r\nhalf");
            Thread.sleep(150);

            CompletableFuture<Boolean> stopped = CompletableFuture.supplyAsync(() -> server.server().stop());
            Thread.sleep(150);
            client.send("done");

            RawResponse response = client.readResponse();
            assertThat(response.text()).isEqualTo("halfdone");
            assertThat(response.header("Connection")).isEqualTo("close");
            assertThat(stopped.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            server.close();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void stopIsIdempotentAndStartIsSingleUse(ConcurrencyModel model) {
        TestServer server = TestServer.start(model, TestRoutes::install);

        assertThat(server.server().stop()).isTrue();
        assertThat(server.server().stop()).isTrue();
        assertThat(server.server().isStopping()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.server().start())
                .isInstanceOf(IllegalStateException.class);
    }
}
