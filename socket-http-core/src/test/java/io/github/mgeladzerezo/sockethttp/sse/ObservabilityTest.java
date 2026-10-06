package io.github.mgeladzerezo.sockethttp.sse;

import io.github.mgeladzerezo.sockethttp.json.Json;
import io.github.mgeladzerezo.sockethttp.server.AccessLog;
import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Server-Sent Events, the metrics handler, the access log and a stock JDK client, per model. */
@Timeout(60)
class ObservabilityTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void sseEventsFormatCorrectly(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, s -> s.get("/events", ctx -> ctx.sse(sse -> {
            sse.retry(1500);
            sse.send("first");
            sse.send("tick", "line1\nline2", "7");
            sse.comment("heartbeat");
            sse.send("last");
        })))) {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(server.url("/events"))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("text/event-stream");
            assertThat(response.headers().firstValue("Transfer-Encoding")).contains("chunked");
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).isEqualTo("retry: 1500\n\ndata: first\n\nevent: tick\nid: 7\ndata: line1\ndata: line2\n\n"
                    + ": heartbeat\n\ndata: last\n\n");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void sseEventIsDeliveredWhileTheStreamIsStillOpen(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, s -> s.get("/events", ctx -> ctx.sse(sse -> {
            sse.send("now");
            while (!sse.serverStopping()) {
                Thread.sleep(20);
            }
        })))) {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(server.url("/events"))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            assertThat(in.readLine()).isEqualTo("data: now");
            in.close();
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    @SuppressWarnings("unchecked")
    void metricsHandlerCountsRequestsAndStatuses(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, s -> {
            TestRoutes.install(s);
            s.get("/metrics", s.metricsHandler());
        })) {
            for (int i = 0; i < 5; i++) {
                assertThat(get(server, "/hello").statusCode()).isEqualTo(200);
            }
            assertThat(get(server, "/nope").statusCode()).isEqualTo(404);
            Map<String, Object> m = (Map<String, Object>) Json.parse(get(server, "/metrics").body());
            Map<String, Object> requests = (Map<String, Object>) m.get("requests");
            Map<String, Object> responses = (Map<String, Object>) requests.get("responses");
            assertThat(m.get("concurrencyModel")).isEqualTo(model.name());
            assertThat(((Number) requests.get("total")).longValue()).isGreaterThanOrEqualTo(6);
            assertThat(((Number) responses.get("2xx")).longValue()).isGreaterThanOrEqualTo(5);
            assertThat(((Number) responses.get("4xx")).longValue()).isEqualTo(1);
            Map<String, Object> latency = (Map<String, Object>) m.get("latencyMicros");
            assertThat(((Number) latency.get("count")).longValue()).isGreaterThanOrEqualTo(6);

            assertThat(get(server, "/metrics?format=prometheus").body()).contains("# TYPE");
        }
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void accessLogUsesCommonLogFormat(ConcurrencyModel model) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (TestServer server = TestServer.start(model, s -> {
            TestRoutes.install(s);
            s.accessLog(AccessLog.commonLogFormat(lines::add));
        })) {
            get(server, "/hello");
            get(server, "/missing");
            long deadline = System.currentTimeMillis() + 5000;
            while (lines.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        }
        List<String> copy = new ArrayList<>(lines);
        assertThat(copy).hasSize(2);
        String clf = "^127\\.0\\.0\\.1 - - \\[\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4}\\] "
                + "\"GET /(hello|missing) HTTP/1\\.1\" (200|404) (\\d+|-)$";
        assertThat(copy).allMatch(l -> l.matches(clf), "common log format");
        assertThat(copy.stream().filter(l -> l.contains("\" 200 5"))).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void jdkHttpClientCanPostAStreamedBodyAndReadItBack(ConcurrencyModel model) throws Exception {
        try (TestServer server = TestServer.start(model, TestRoutes::install)) {
            byte[] payload = TestRoutes.pattern(200_000);
            // An unknown-length publisher makes the JDK client send Transfer-Encoding: chunked.
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(URI.create(server.url("/echo")))
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
                            .header("Content-Type", "application/octet-stream").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(payload);
        }
    }

    private HttpResponse<String> get(TestServer server, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(server.url(path))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
