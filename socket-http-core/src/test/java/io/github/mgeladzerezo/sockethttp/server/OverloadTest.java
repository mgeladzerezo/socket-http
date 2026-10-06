package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.concurrent.RejectionPolicy;
import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens at the limits: the connection cap, a saturated worker pool under each overload
 * policy, and the difference between the models in what an idle connection costs.
 */
@Timeout(60)
class OverloadTest {

    private static final String HOST = "Host: test\r\n";
    private static final String HELLO = "GET /hello HTTP/1.1\r\n" + HOST + "\r\n";

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void connectionsBeyondTheLimitWaitInTheBacklogUntilASlotFrees(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, c -> c.maxConnections(2), TestRoutes::install);
             RawClient first = server.connect();
             RawClient second = server.connect()) {
            assertThat(first.send(HELLO).readResponse().status()).isEqualTo(200);
            assertThat(second.send(HELLO).readResponse().status()).isEqualTo(200);

            // The TCP handshake of the third connection completes (the kernel queues it in the
            // listen backlog), but the server does not accept it, so the request sits unread.
            try (RawClient third = server.connect()) {
                third.send(HELLO).timeoutMillis(500);
                assertThatThrownBy(third::readResponse).isInstanceOf(SocketTimeoutException.class);
                assertThat(server.server().metrics().connectionsActive()).isEqualTo(2);

                first.close();

                third.timeoutMillis(10_000);
                assertThat(third.readResponse().text()).isEqualTo("hello");
            }
        }
    }

    @Test
    void threadPoolAnIdleKeepAliveConnectionPinsAWorker() throws Exception {
        try (TestServer server = TestServer.start(ConcurrencyModel.THREAD_POOL,
                c -> c.workerThreads(2).queueCapacity(4), TestRoutes::install);
             RawClient first = server.connect();
             RawClient second = server.connect()) {
            assertThat(first.send(HELLO).readResponse().status()).isEqualTo(200);
            assertThat(second.send(HELLO).readResponse().status()).isEqualTo(200);

            // Both workers are now parked in read() on idle connections. A third connection is
            // accepted and queued, but nobody is free to serve it.
            try (RawClient third = server.connect()) {
                third.send(HELLO).timeoutMillis(500);
                assertThatThrownBy(third::readResponse).isInstanceOf(SocketTimeoutException.class);

                second.close();

                third.timeoutMillis(10_000);
                assertThat(third.readResponse().text()).isEqualTo("hello");
            }
        }
    }

    @Test
    void threadPoolWithAbortPolicyAnswers503WhenSaturated() throws Exception {
        try (TestServer server = TestServer.start(ConcurrencyModel.THREAD_POOL,
                c -> c.workerThreads(1).queueCapacity(0).overloadPolicy(RejectionPolicy.ABORT), TestRoutes::install);
             RawClient holder = server.connect()) {
            assertThat(holder.send(HELLO).readResponse().status()).isEqualTo(200);

            try (RawClient rejected = server.connect()) {
                RawResponse response = rejected.readResponse();
                assertThat(response.status()).isEqualTo(503);
                assertThat(response.header("Retry-After")).isEqualTo("1");
                assertThat(response.header("Connection")).isEqualTo("close");
                assertThat(rejected.isClosedByServer(3_000)).isTrue();
            }
            assertThat(server.server().metrics().snapshot().connectionsRejected()).isEqualTo(1);

            // The connection that holds the worker is unaffected.
            assertThat(holder.send(HELLO).readResponse().status()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ConcurrencyModel.class, names = {"NIO_EVENT_LOOP", "VIRTUAL_THREADS"})
    void manyIdleConnectionsDoNotStarveAnActiveOne(ConcurrencyModel model) throws Exception {
        // Two workers, sixty open connections. Under THREAD_POOL this configuration serves two
        // clients (see the test above); here idle connections hold no thread at all.
        try (TestServer server = TestServer.start(model, c -> c.workerThreads(2), TestRoutes::install)) {
            List<RawClient> clients = new ArrayList<>();
            try {
                for (int i = 0; i < 60; i++) {
                    RawClient client = server.connect();
                    clients.add(client);
                    assertThat(client.send("GET /echo/c" + i + " HTTP/1.1\r\n" + HOST + "\r\n").readResponse().text())
                            .isEqualTo("c" + i);
                }
                assertThat(server.server().metrics().connectionsActive()).isEqualTo(60);
                for (int round = 0; round < 3; round++) {
                    for (int i = 0; i < 60; i++) {
                        assertThat(clients.get(i).send("GET /echo/r" + round + "c" + i + " HTTP/1.1\r\n" + HOST + "\r\n")
                                .readResponse().text()).isEqualTo("r" + round + "c" + i);
                    }
                }
            } finally {
                for (RawClient client : clients) {
                    client.close();
                }
            }
        }
    }

    @Test
    void eventLoopWithAbortPolicyAnswers503WhenWorkersAndQueueAreFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try (TestServer server = TestServer.start(ConcurrencyModel.NIO_EVENT_LOOP,
                c -> c.workerThreads(1).queueCapacity(1).overloadPolicy(RejectionPolicy.ABORT), s ->
                        s.get("/hold", ctx -> {
                            running.countDown();
                            release.await(20, TimeUnit.SECONDS);
                            ctx.text("held");
                        }));
             RawClient running1 = server.connect();
             RawClient queued = server.connect();
             RawClient rejected = server.connect()) {
            running1.send("GET /hold HTTP/1.1\r\n" + HOST + "\r\n");
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
            queued.send("GET /hold HTTP/1.1\r\n" + HOST + "\r\n");
            Thread.sleep(200);

            RawResponse response = rejected.send("GET /hold HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.header("Retry-After")).isEqualTo("1");

            release.countDown();
            assertThat(running1.readResponse().text()).isEqualTo("held");
            assertThat(queued.readResponse().text()).isEqualTo("held");
        }
    }

    @Test
    void eventLoopWithBlockPolicyParksRequestsUntilAWorkerIsFree() throws Exception {
        try (TestServer server = TestServer.start(ConcurrencyModel.NIO_EVENT_LOOP,
                c -> c.workerThreads(1).queueCapacity(1).overloadPolicy(RejectionPolicy.BLOCK), TestRoutes::install)) {
            List<RawClient> clients = new ArrayList<>();
            try {
                // Eight slow requests at once against one worker and one queue slot: six of
                // them have to wait on their own connections. None is refused.
                for (int i = 0; i < 8; i++) {
                    clients.add(server.connect().send("GET /slow?ms=100&id=" + i + " HTTP/1.1\r\n" + HOST + "\r\n"));
                }
                for (int i = 0; i < 8; i++) {
                    assertThat(clients.get(i).readResponse().text()).isEqualTo("slow " + i);
                }
                assertThat(server.server().metrics().snapshot().connectionsRejected()).isZero();
            } finally {
                for (RawClient client : clients) {
                    client.close();
                }
            }
        }
    }

    @Test
    void virtualThreadsRunBlockingHandlersConcurrentlyWithoutAPool() throws Exception {
        try (TestServer server = TestServer.start(ConcurrencyModel.VIRTUAL_THREADS, TestRoutes::install)) {
            int connections = 300;
            List<RawClient> clients = new ArrayList<>();
            try {
                long start = System.nanoTime();
                for (int i = 0; i < connections; i++) {
                    clients.add(server.connect().send("GET /slow?ms=400&id=" + i + " HTTP/1.1\r\n" + HOST + "\r\n"));
                }
                for (int i = 0; i < connections; i++) {
                    assertThat(clients.get(i).readResponse().text()).isEqualTo("slow " + i);
                }
                // Sequentially this is 120 s of sleeping; 16 pool threads would need 7.5 s.
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            } finally {
                for (RawClient client : clients) {
                    client.close();
                }
            }
        }
    }
}
