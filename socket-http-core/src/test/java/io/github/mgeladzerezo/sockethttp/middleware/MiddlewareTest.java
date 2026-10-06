package io.github.mgeladzerezo.sockethttp.middleware;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.http.ResponseBody;
import io.github.mgeladzerezo.sockethttp.json.Json;
import io.github.mgeladzerezo.sockethttp.routing.Context;
import io.github.mgeladzerezo.sockethttp.routing.Router;
import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static io.github.mgeladzerezo.sockethttp.support.Requests.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three bundled middleware: CORS, error mapping and request logging. */
class MiddlewareTest {

    private static String body(Context ctx) {
        return new String(((ResponseBody.Bytes) ctx.response().body()).data(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------- CORS

    private static Router corsRouter(Cors cors) {
        Router router = new Router();
        router.use(cors);
        router.get("/data", ctx -> ctx.text("data"));
        router.put("/data", ctx -> ctx.text("stored"));
        return router;
    }

    @Test
    void corsAddsHeadersForAnAllowedOriginAndVariesOnIt() throws Exception {
        Router router = corsRouter(Cors.builder().allowOrigins("https://app.example").exposeHeaders("X-Total").build());

        Context allowed = context("GET", "/data", "Origin: https://app.example");
        router.handle(allowed);
        assertThat(body(allowed)).isEqualTo("data");
        assertThat(allowed.response().headers().get("Access-Control-Allow-Origin")).isEqualTo("https://app.example");
        assertThat(allowed.response().headers().get("Access-Control-Expose-Headers")).isEqualTo("X-Total");
        assertThat(allowed.response().headers().get("Vary")).isEqualTo("Origin");

        Context other = context("GET", "/data", "Origin: https://evil.example");
        router.handle(other);
        assertThat(body(other)).as("the request still runs; the browser enforces the policy").isEqualTo("data");
        assertThat(other.response().headers().contains("Access-Control-Allow-Origin")).isFalse();

        Context sameOrigin = context("GET", "/data");
        router.handle(sameOrigin);
        assertThat(sameOrigin.response().headers().contains("Access-Control-Allow-Origin")).isFalse();
    }

    @Test
    void corsAnswersPreflightWithoutReachingTheRoute() throws Exception {
        Router router = corsRouter(Cors.builder().allowOrigins("https://app.example").allowMethods("GET", "PUT")
                .maxAge(Duration.ofMinutes(5)).allowCredentials(true).build());

        Context preflight = context("OPTIONS", "/data", "Origin: https://app.example",
                "Access-Control-Request-Method: PUT", "Access-Control-Request-Headers: content-type, x-token");
        router.handle(preflight);

        assertThat(preflight.response().status()).isEqualTo(204);
        assertThat(preflight.response().headers().get("Access-Control-Allow-Origin")).isEqualTo("https://app.example");
        assertThat(preflight.response().headers().get("Access-Control-Allow-Methods")).isEqualTo("GET, PUT");
        assertThat(preflight.response().headers().get("Access-Control-Allow-Headers")).isEqualTo("content-type, x-token");
        assertThat(preflight.response().headers().get("Access-Control-Max-Age")).isEqualTo("300");
        assertThat(preflight.response().headers().get("Access-Control-Allow-Credentials")).isEqualTo("true");

        // A plain OPTIONS without the preflight header is ordinary and reaches the router.
        Context plain = context("OPTIONS", "/data", "Origin: https://app.example");
        router.handle(plain);
        assertThat(plain.response().headers().get("Allow")).isEqualTo("GET, HEAD, OPTIONS, PUT");
    }

    @Test
    void permissiveCorsUsesAWildcardAndRefusesToCombineItWithCredentials() throws Exception {
        Router router = corsRouter(Cors.permissive());
        Context ctx = context("GET", "/data", "Origin: https://anything.example");
        router.handle(ctx);

        assertThat(ctx.response().headers().get("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(ctx.response().headers().contains("Vary")).isFalse();
        assertThatThrownBy(() -> Cors.builder().allowAnyOrigin().allowCredentials(true).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corsHeadersSurviveOnErrorResponsesWrittenByTheServer() throws Exception {
        try (TestServer server = TestServer.start(ConcurrencyModel.VIRTUAL_THREADS, s -> {
            s.use(Cors.permissive());
            s.get("/fail", ctx -> {
                throw new IllegalStateException("boom");
            });
        }); RawClient client = server.connect()) {
            RawResponse failed = client.send("GET /fail HTTP/1.1\r\nHost: t\r\nOrigin: https://app.example\r\n\r\n").readResponse();
            assertThat(failed.status()).isEqualTo(500);
            assertThat(failed.header("Access-Control-Allow-Origin")).isEqualTo("*");

            RawResponse missing = client.send("GET /missing HTTP/1.1\r\nHost: t\r\nOrigin: https://app.example\r\n\r\n").readResponse();
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.header("Access-Control-Allow-Origin")).isEqualTo("*");
        }
    }

    // ----------------------------------------------------------- error mapping

    @Test
    void errorMapperUsesTheMostSpecificMapping() throws Exception {
        Router router = new Router();
        router.use(ErrorMapper.create()
                .on(RuntimeException.class, (e, ctx) -> ctx.status(500).text("generic"))
                .on(IllegalArgumentException.class, (e, ctx) -> ctx.status(400).text("bad: " + e.getMessage()))
                .on(NoSuchElementException.class, (e, ctx) -> ctx.status(404).text("missing")));
        router.get("/number", ctx -> {
            throw new NumberFormatException("not a number");
        });
        router.get("/missing", ctx -> {
            throw new NoSuchElementException();
        });
        router.get("/other", ctx -> {
            throw new UnsupportedOperationException();
        });
        router.get("/checked", ctx -> {
            throw new java.io.IOException("unmapped");
        });

        Context number = context("GET", "/number");
        router.handle(number);
        assertThat(number.response().status()).isEqualTo(400);
        assertThat(body(number)).as("NumberFormatException is an IllegalArgumentException").isEqualTo("bad: not a number");

        Context missing = context("GET", "/missing");
        router.handle(missing);
        assertThat(missing.response().status()).isEqualTo(404);

        Context other = context("GET", "/other");
        router.handle(other);
        assertThat(body(other)).isEqualTo("generic");

        assertThatThrownBy(() -> router.handle(context("GET", "/checked")))
                .as("unmapped exceptions propagate to the server").isInstanceOf(java.io.IOException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonErrorMapperRendersRouterErrorsWithTheirStatusAndHeaders() throws Exception {
        Router router = new Router();
        router.use(ErrorMapper.json());
        router.get("/thing", ctx -> ctx.text("thing"));
        router.get("/teapot", ctx -> {
            throw new HttpException(418, "short and stout");
        });
        router.get("/crash", ctx -> {
            ctx.header("Content-Type", "image/png");
            throw new IllegalStateException("secret internal detail");
        });

        Context notFound = context("GET", "/nope");
        router.handle(notFound);
        assertThat(notFound.response().status()).isEqualTo(404);
        Map<String, Object> notFoundBody = (Map<String, Object>) Json.parse(body(notFound));
        assertThat(notFoundBody).containsEntry("status", 404L).containsEntry("error", "Not Found");

        Context notAllowed = context("POST", "/thing");
        router.handle(notAllowed);
        assertThat(notAllowed.response().status()).isEqualTo(405);
        assertThat(notAllowed.response().headers().get("Allow")).isEqualTo("GET, HEAD, OPTIONS");

        Context teapot = context("GET", "/teapot");
        router.handle(teapot);
        assertThat((Map<String, Object>) Json.parse(body(teapot))).containsEntry("message", "short and stout");

        Context crash = context("GET", "/crash");
        router.handle(crash);
        assertThat(crash.response().status()).isEqualTo(500);
        assertThat(crash.response().headers().get("Content-Type")).isEqualTo("application/json");
        assertThat(body(crash)).doesNotContain("secret internal detail");
    }

    // ---------------------------------------------------------------- logging

    @Test
    void requestLogRecordsMethodPathRouteAndStatus() throws Exception {
        List<String> lines = new ArrayList<>();
        Router router = new Router();
        router.use(RequestLog.to(lines::add));
        router.get("/users/{id}", ctx -> ctx.status(201).text("ok"));
        router.get("/crash", ctx -> {
            throw new IllegalStateException();
        });

        router.handle(context("GET", "/users/42"));
        assertThatThrownBy(() -> router.handle(context("GET", "/absent"))).isInstanceOf(HttpException.class);
        assertThatThrownBy(() -> router.handle(context("GET", "/crash"))).isInstanceOf(IllegalStateException.class);

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).matches("GET /users/42 \\(/users/\\{id}\\) -> 201 in \\d+\\.\\d+ ms");
        assertThat(lines.get(1)).startsWith("GET /absent -> 404 in ");
        assertThat(lines.get(2)).startsWith("GET /crash (/crash) -> 500 in ");
    }
}
