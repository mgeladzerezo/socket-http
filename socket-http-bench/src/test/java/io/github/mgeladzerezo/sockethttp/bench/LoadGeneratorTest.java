package io.github.mgeladzerezo.sockethttp.bench;

import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.server.HttpServer;
import io.github.mgeladzerezo.sockethttp.server.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The load generator has to be trustworthy before its numbers mean anything. */
@Timeout(60)
class LoadGeneratorTest {

    private HttpServer server;

    @BeforeEach
    void start() {
        server = HttpServer.create(ServerConfig.builder().host("127.0.0.1").port(0)
                .concurrencyModel(ConcurrencyModel.VIRTUAL_THREADS).maxRequestsPerConnection(0).build());
        server.get("/hello", ctx -> ctx.json(Map.of("message", "hello")));
        server.get("/blocking", ctx -> {
            Thread.sleep(BenchServer.BLOCKING_MILLIS);
            ctx.json(Map.of("message", "hello"));
        });
        server.get("/fail", ctx -> ctx.status(503).text("busy"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void countsOnlyVerifiedResponsesAndRecordsLatency() throws Exception {
        var result = new LoadGenerator("127.0.0.1", server.port(), "/hello", BenchServer.HELLO_BODY.length())
                .run(4, Duration.ofMillis(200), Duration.ofSeconds(1));
        assertThat(result.errors()).isZero();
        assertThat(result.requests()).isGreaterThan(100);
        assertThat(result.latencyMicros().count()).isEqualTo(result.requests());
        assertThat(server.metrics().snapshot().requestsTotal()).isGreaterThanOrEqualTo(result.requests());
    }

    @Test
    void errorResponsesAreErrorsNotFastRequests() throws Exception {
        var result = new LoadGenerator("127.0.0.1", server.port(), "/fail", 4)
                .run(2, Duration.ofMillis(100), Duration.ofMillis(500));
        assertThat(result.requests()).isZero();
        assertThat(result.errors()).isPositive();
    }

    @Test
    void aWrongBodyLengthIsAnError() throws Exception {
        var result = new LoadGenerator("127.0.0.1", server.port(), "/hello", 5)
                .run(2, Duration.ofMillis(100), Duration.ofMillis(500));
        assertThat(result.requests()).isZero();
        assertThat(result.errors()).isPositive();
    }

    @Test
    void closedLoopThroughputCannotExceedConnectionsOverServiceTime() throws Exception {
        int connections = 8;
        var result = new LoadGenerator("127.0.0.1", server.port(), "/blocking", BenchServer.HELLO_BODY.length())
                .run(connections, Duration.ofMillis(300), Duration.ofSeconds(2));
        double ceiling = connections / (BenchServer.BLOCKING_MILLIS / 1000.0);
        assertThat(result.errors()).isZero();
        assertThat(result.requestsPerSecond()).isLessThanOrEqualTo(ceiling);
        assertThat(result.latencyMicros().percentile(50)).isGreaterThanOrEqualTo(BenchServer.BLOCKING_MILLIS * 1000 - 1000);
    }
}
