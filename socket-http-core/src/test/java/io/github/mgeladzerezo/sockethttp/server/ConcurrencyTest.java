package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many connections at once, each sending thousands of requests whose expected response is
 * unique to that connection and position. A response delivered to the wrong connection, out
 * of order, duplicated or truncated fails the comparison.
 */
@Timeout(120)
class ConcurrencyTest {

    private static final String HOST = "Host: test\r\n";
    private static final int CONNECTIONS = 24;

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void keepAliveRequestsFromManyConnectionsNeverCross(ConcurrencyModel model) throws Exception {
        int requestsPerConnection = 250;
        AtomicLong verified = new AtomicLong();
        try (TestServer server = TestServer.start(model, c -> c.workerThreads(32).maxRequestsPerConnection(0),
                TestRoutes::install)) {
            runOnEveryConnection(server, (client, connection) -> {
                for (int i = 0; i < requestsPerConnection; i++) {
                    String token = "c" + connection + "-r" + i;
                    RawResponse response;
                    if (i % 3 == 0) {
                        // A body unique to this request, echoed back.
                        String body = token + "-" + "x".repeat(i % 97);
                        response = client.send("POST /echo HTTP/1.1\r\n" + HOST + "Content-Length: " + body.length()
                                + "\r\n\r\n" + body).readResponse();
                        assertThat(response.text()).isEqualTo(body);
                    } else {
                        response = client.send("GET /echo/" + token + " HTTP/1.1\r\n" + HOST + "\r\n").readResponse();
                        assertThat(response.text()).isEqualTo(token);
                    }
                    assertThat(response.status()).isEqualTo(200);
                    verified.incrementAndGet();
                }
            });

            assertThat(verified.get()).isEqualTo((long) CONNECTIONS * requestsPerConnection);
            assertThat(server.server().metrics().snapshot().requestsTotal()).isEqualTo(verified.get());
            assertThat(server.server().metrics().snapshot().responses2xx()).isEqualTo(verified.get());
            assertThat(server.server().metrics().snapshot().connectionsAccepted()).isEqualTo(CONNECTIONS);
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void pipelinedBatchesFromManyConnectionsAreAnsweredInOrder(ConcurrencyModel model) throws Exception {
        int batches = 20;
        int batchSize = 16;
        AtomicLong verified = new AtomicLong();
        try (TestServer server = TestServer.start(model, c -> c.workerThreads(32).maxRequestsPerConnection(0),
                TestRoutes::install)) {
            runOnEveryConnection(server, (client, connection) -> {
                for (int batch = 0; batch < batches; batch++) {
                    StringBuilder pipeline = new StringBuilder();
                    List<String> expected = new ArrayList<>();
                    for (int i = 0; i < batchSize; i++) {
                        String token = "c" + connection + "-b" + batch + "-r" + i;
                        if (i % 4 == 1) {
                            pipeline.append("POST /echo HTTP/1.1\r\n").append(HOST)
                                    .append("Transfer-Encoding: chunked\r\n\r\n")
                                    .append(Integer.toHexString(token.length())).append("\r\n").append(token)
                                    .append("\r\n0\r\n\r\n");
                        } else {
                            pipeline.append("GET /echo/").append(token).append(" HTTP/1.1\r\n").append(HOST).append("\r\n");
                        }
                        expected.add(token);
                    }
                    // The whole batch in one write; the split into TCP segments is up to the stack.
                    client.send(pipeline.toString().getBytes(StandardCharsets.ISO_8859_1));
                    for (String token : expected) {
                        assertThat(client.readResponse().text()).isEqualTo(token);
                        verified.incrementAndGet();
                    }
                }
            });

            assertThat(verified.get()).isEqualTo((long) CONNECTIONS * batches * batchSize);
            assertThat(server.server().metrics().snapshot().requestsTotal()).isEqualTo(verified.get());
        }
    }

    @FunctionalInterface
    private interface ConnectionScript {
        void run(RawClient client, int connection) throws Exception;
    }

    private static void runOnEveryConnection(TestServer server, ConnectionScript script) throws Exception {
        CyclicBarrier start = new CyclicBarrier(CONNECTIONS);
        try (ExecutorService clients = Executors.newFixedThreadPool(CONNECTIONS)) {
            List<Future<?>> results = new ArrayList<>();
            for (int c = 0; c < CONNECTIONS; c++) {
                int connection = c;
                results.add(clients.submit(() -> {
                    try (RawClient client = server.connect()) {
                        client.timeoutMillis(30_000);
                        start.await(30, TimeUnit.SECONDS);
                        script.run(client, connection);
                    }
                    return null;
                }));
            }
            for (Future<?> result : results) {
                result.get(100, TimeUnit.SECONDS);
            }
        }
    }
}
