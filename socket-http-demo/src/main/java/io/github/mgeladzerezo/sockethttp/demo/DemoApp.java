package io.github.mgeladzerezo.sockethttp.demo;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.middleware.Cors;
import io.github.mgeladzerezo.sockethttp.middleware.ErrorMapper;
import io.github.mgeladzerezo.sockethttp.server.AccessLog;
import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.server.HttpServer;
import io.github.mgeladzerezo.sockethttp.server.ServerConfig;
import io.github.mgeladzerezo.sockethttp.staticfiles.StaticFiles;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The demo: a designed static site that explains the server, a small JSON API, and a live
 * metrics stream over Server-Sent Events.
 *
 * <p>Configuration comes from the environment: {@code PORT} (default 8203),
 * {@code CONCURRENCY_MODEL} ({@code THREAD_POOL}, {@code VIRTUAL_THREADS} or
 * {@code NIO_EVENT_LOOP}; default virtual threads) and {@code SITE_DIR} (serve the site from a
 * directory instead of the copy bundled in the jar, handy while editing it).
 */
public final class DemoApp {

    private DemoApp() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8203"));
        ConcurrencyModel model = ConcurrencyModel.valueOf(env("CONCURRENCY_MODEL", "VIRTUAL_THREADS"));
        String siteDir = System.getenv("SITE_DIR");
        Path site = siteDir != null ? Path.of(siteDir) : SiteResources.extractToTempDirectory();

        ServerConfig config = ServerConfig.builder()
                .host(env("HOST", "0.0.0.0"))
                .port(port)
                .concurrencyModel(model)
                .workerThreads(64)
                .maxConnections(2_000)
                .shutdownGrace(Duration.ofSeconds(10))
                .build();
        HttpServer server = build(config, site).accessLog(AccessLog.commonLogFormat(System.out));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));
        server.start();
        Thread.currentThread().join();
    }

    /** Registers the demo's routes on a new server; separate from {@code main} so tests can start it on a free port. */
    public static HttpServer build(ServerConfig config, Path site) {
        HttpServer server = HttpServer.create(config);
        UserStore users = new UserStore();
        MetricsStream stream = new MetricsStream(server);

        server.use(ErrorMapper.json());
        server.use(Cors.permissive());

        server.get("/healthz", ctx -> ctx.json(Map.of("status", "up")));
        server.get("/metrics", server.metricsHandler());
        server.get("/events/metrics", ctx -> ctx.sse(stream::run));

        server.group("/api", api -> {
            api.get("/info", ctx -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("server", config.serverName());
                info.put("concurrencyModel", config.concurrencyModel().name());
                info.put("java", Runtime.version().toString());
                info.put("time", Instant.now().toString());
                ctx.json(info);
            });
            api.get("/hello", ctx -> ctx.json(Map.of("message", "hello, " + ctx.queryParam("name").orElse("world"))));
            api.get("/slow", ctx -> {
                int ms = Math.min(ctx.queryParamAsInt("ms", 20), 2_000);
                Thread.sleep(ms);
                ctx.json(Map.of("sleptMillis", ms));
            });
            api.post("/echo", ctx -> ctx.json(Map.of(
                    "method", ctx.method(),
                    "contentType", ctx.header("Content-Type").orElse(""),
                    "length", ctx.body().length,
                    "body", ctx.bodyAsString())));

            api.get("/users", ctx -> ctx.json(users.list()));
            api.get("/users/{id}", ctx -> ctx.json(users.find(ctx.pathParamAsLong("id"))));
            api.post("/users", ctx -> {
                Map<String, Object> created = users.create(requiredString(ctx.bodyAsJsonObject(), "name"));
                ctx.status(201).header("Location", "/api/users/" + created.get("id")).json(created);
            });
            api.put("/users/{id}", ctx -> ctx.json(
                    users.rename(ctx.pathParamAsLong("id"), requiredString(ctx.bodyAsJsonObject(), "name"))));
            api.delete("/users/{id}", ctx -> {
                users.delete(ctx.pathParamAsLong("id"));
                ctx.noContent();
            });
        });

        server.get("/*", StaticFiles.from(site).cacheControl("public, max-age=60").build());
        return server;
    }

    private static String requiredString(Map<String, Object> body, String field) {
        if (body.get(field) instanceof String s && !s.isBlank() && s.length() <= 100) {
            return s;
        }
        throw HttpException.badRequest("\"" + field + "\" must be a non-empty string of at most 100 characters");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

}
