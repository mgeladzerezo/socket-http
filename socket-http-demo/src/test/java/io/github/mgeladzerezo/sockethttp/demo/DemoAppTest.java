package io.github.mgeladzerezo.sockethttp.demo;

import io.github.mgeladzerezo.sockethttp.json.Json;
import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.server.HttpServer;
import io.github.mgeladzerezo.sockethttp.server.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
class DemoAppTest {

    @TempDir
    Path site;

    private HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        SiteResources.extractTo(site);
        server = DemoApp.build(ServerConfig.builder().host("127.0.0.1").port(0)
                .concurrencyModel(ConcurrencyModel.VIRTUAL_THREADS).build(), site).start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void manifestListsEveryFileOfTheBundledSite() throws Exception {
        Path source = Path.of("src/main/resources/site");
        List<String> listed = Files.readAllLines(source.resolve("files.txt")).stream().filter(s -> !s.isBlank()).toList();
        try (Stream<Path> walk = Files.walk(source)) {
            List<String> onDisk = walk.filter(Files::isRegularFile)
                    .map(p -> source.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> !n.equals("files.txt")).toList();
            assertThat(listed).containsExactlyInAnyOrderElementsOf(onDisk);
        }
    }

    @Test
    void servesTheSiteWithTypesThatBrowsersAcceptForModules() throws Exception {
        HttpResponse<String> index = get("/");
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(index.body()).contains("<title>socket-http</title>");
        assertThat(get("/js/main.js").headers().firstValue("Content-Type").orElse("")).contains("javascript");
        assertThat(get("/css/style.css").headers().firstValue("Content-Type").orElse("")).startsWith("text/css");
        assertThat(get("/nothing-here").statusCode()).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void userApiSupportsCrud() throws Exception {
        List<Object> initial = (List<Object>) Json.parse(get("/api/users").body());
        assertThat(initial).hasSize(3);

        HttpResponse<String> created = send("POST", "/api/users", "{\"name\":\"Linus\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        Map<String, Object> user = (Map<String, Object>) Json.parse(created.body());
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/api/users/" + user.get("id"));
        assertThat(get(location).body()).contains("Linus");

        assertThat(send("PUT", location, "{\"name\":\"Linus T\"}").body()).contains("Linus T");
        assertThat(send("DELETE", location, null).statusCode()).isEqualTo(204);
        assertThat(get(location).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/users", "{\"name\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/users", "not json").statusCode()).isEqualTo(400);
    }

    @Test
    void metricsStreamPushesParseableSnapshots() throws Exception {
        get("/api/hello");
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(base() + "/events/metrics")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            String data = null;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    data = line.substring(6);
                    break;
                }
            }
            assertThat(data).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) Json.parse(data);
            assertThat(snapshot).containsKeys("connections", "requests", "latencyMicros");
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(HttpRequest.newBuilder(URI.create(base() + path)).method(method, publisher)
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
    }
}
