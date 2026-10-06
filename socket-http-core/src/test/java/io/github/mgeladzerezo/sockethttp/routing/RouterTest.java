package io.github.mgeladzerezo.sockethttp.routing;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.http.ResponseBody;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.mgeladzerezo.sockethttp.support.Requests.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Route matching, precedence, groups and the middleware chain, without a socket in sight. */
class RouterTest {

    private final Router router = new Router();

    private String call(String method, String target) throws Exception {
        Context ctx = context(method, target);
        router.handle(ctx);
        return body(ctx);
    }

    private static String body(Context ctx) {
        return new String(((ResponseBody.Bytes) ctx.response().body()).data(), StandardCharsets.UTF_8);
    }

    private HttpException failure(String method, String target) {
        return catchThrowableOfType(HttpException.class, () -> router.handle(context(method, target)));
    }

    // --------------------------------------------------------------- matching

    @Test
    void matchesLiteralsParametersAndTheRoot() throws Exception {
        router.get("/", ctx -> ctx.text("root"));
        router.get("/users", ctx -> ctx.text("list"));
        router.get("/users/{id}", ctx -> ctx.text("user " + ctx.pathParam("id")));
        router.get("/users/{id}/posts/{post}", ctx -> ctx.text(ctx.pathParams().toString()));

        assertThat(call("GET", "/")).isEqualTo("root");
        assertThat(call("GET", "/users")).isEqualTo("list");
        assertThat(call("GET", "/users/42")).isEqualTo("user 42");
        assertThat(call("GET", "/users/42/posts/7")).isEqualTo("{id=42, post=7}");
    }

    @Test
    void literalBeatsParameterWhateverTheRegistrationOrder() throws Exception {
        router.get("/users/{id}", ctx -> ctx.text("param"));
        router.get("/users/me", ctx -> ctx.text("literal"));
        router.get("/files/readme", ctx -> ctx.text("literal"));
        router.get("/files/{name}", ctx -> ctx.text("param"));

        assertThat(call("GET", "/users/me")).isEqualTo("literal");
        assertThat(call("GET", "/users/you")).isEqualTo("param");
        assertThat(call("GET", "/files/readme")).isEqualTo("literal");
        assertThat(call("GET", "/files/other")).isEqualTo("param");
    }

    @Test
    void backtracksWhenTheLiteralBranchDeadEnds() throws Exception {
        router.get("/a/b/d", ctx -> ctx.text("literal path"));
        router.get("/a/{x}/c", ctx -> ctx.text("param path x=" + ctx.pathParam("x")));

        // "b" matches the literal child first, but that branch has no "c"; the search must
        // come back and try the parameter child.
        assertThat(call("GET", "/a/b/c")).isEqualTo("param path x=b");
        assertThat(call("GET", "/a/b/d")).isEqualTo("literal path");
    }

    @Test
    void wildcardMatchesTheRestOfThePathIncludingNothing() throws Exception {
        router.get("/assets/*", ctx -> ctx.text(ctx.wildcardSegments() + "|" + ctx.wildcard()));
        router.get("/assets/special", ctx -> ctx.text("special"));

        assertThat(call("GET", "/assets/css/site.css")).isEqualTo("[css, site.css]|css/site.css");
        assertThat(call("GET", "/assets")).isEqualTo("[]|");
        assertThat(call("GET", "/assets/")).isEqualTo("[]|");
        assertThat(call("GET", "/assets/dir/")).isEqualTo("[dir, ]|dir/");
        assertThat(call("GET", "/assets/special")).isEqualTo("special");
        assertThat(call("GET", "/assets/special/deeper")).isEqualTo("[special, deeper]|special/deeper");
    }

    @Test
    void parametersAreDecodedPerSegmentSoAnEncodedSlashCannotChangeTheRoute() throws Exception {
        router.get("/files/{name}", ctx -> ctx.text("file " + ctx.pathParam("name")));
        router.get("/files/{name}/delete", ctx -> ctx.text("DELETED " + ctx.pathParam("name")));

        assertThat(call("GET", "/files/a%2Fdelete")).isEqualTo("file a/delete");
        assertThat(call("GET", "/files/caf%C3%A9%20menu")).isEqualTo("file café menu");
    }

    @Test
    void aTrailingSlashIsIgnoredAndAnEmptySegmentIsNotAParameter() throws Exception {
        router.get("/users/{id}", ctx -> ctx.text("user " + ctx.pathParam("id")));
        router.get("/users", ctx -> ctx.text("list"));

        assertThat(call("GET", "/users/42/")).isEqualTo("user 42");
        assertThat(call("GET", "/users/")).isEqualTo("list");
        assertThat(failure("GET", "/users//").status()).isEqualTo(404);
        assertThat(failure("GET", "//users").status()).isEqualTo(404);
    }

    @Test
    void typedAccessorsTurnBadInputInto400() throws Exception {
        router.get("/items/{id}", ctx -> ctx.text("item " + ctx.pathParamAsLong("id") + " page " + ctx.queryParamAsInt("page", 1)));

        assertThat(call("GET", "/items/9000000000?page=3")).isEqualTo("item 9000000000 page 3");
        assertThat(call("GET", "/items/5")).isEqualTo("item 5 page 1");
        assertThat(failure("GET", "/items/abc").status()).isEqualTo(400);
        assertThat(failure("GET", "/items/5?page=two").status()).isEqualTo(400);
    }

    @Test
    void askingForAParameterTheRouteDoesNotHaveIsAProgrammingError() {
        router.get("/items/{id}", ctx -> ctx.text(ctx.pathParam("name")));

        assertThatThrownBy(() -> router.handle(context("GET", "/items/5")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/items/{id}");
    }

    // ------------------------------------------------------- protocol answers

    @Test
    void unknownPathIs404AndKnownPathWithWrongMethodIs405WithAllow() {
        router.get("/things", ctx -> ctx.text("get"));
        router.post("/things", ctx -> ctx.text("post"));

        assertThat(failure("GET", "/nothing").status()).isEqualTo(404);
        HttpException notAllowed = failure("DELETE", "/things");
        assertThat(notAllowed.status()).isEqualTo(405);
        assertThat(notAllowed.headers().get("Allow")).isEqualTo("GET, HEAD, OPTIONS, POST");
    }

    @Test
    void headUsesTheGetRouteUnlessAHeadRouteExists() throws Exception {
        router.get("/a", ctx -> ctx.text("from get"));
        router.get("/b", ctx -> ctx.text("from get"));
        router.head("/b", ctx -> ctx.text("from head"));

        assertThat(call("HEAD", "/a")).isEqualTo("from get");
        assertThat(call("HEAD", "/b")).isEqualTo("from head");
    }

    @Test
    void optionsIsAnsweredFromTheRouteTableUnlessARouteHandlesIt() throws Exception {
        router.get("/a", ctx -> ctx.text("get"));
        router.put("/a", ctx -> ctx.text("put"));
        router.options("/custom", ctx -> ctx.text("custom options"));

        Context ctx = context("OPTIONS", "/a");
        router.handle(ctx);
        assertThat(ctx.response().status()).isEqualTo(204);
        assertThat(ctx.response().headers().get("Allow")).isEqualTo("GET, HEAD, OPTIONS, PUT");
        assertThat(call("OPTIONS", "/custom")).isEqualTo("custom options");
        assertThat(failure("OPTIONS", "/missing").status()).isEqualTo(404);
    }

    @Test
    void anUnknownMethodIs501UnlessARouteRegistersIt() throws Exception {
        router.get("/a", ctx -> ctx.text("get"));
        assertThat(failure("PROPFIND", "/a").status()).isEqualTo(501);

        router.route("PROPFIND", "/dav", ctx -> ctx.text("propfind"));
        assertThat(call("PROPFIND", "/dav")).isEqualTo("propfind");
        assertThat(failure("PROPFIND", "/a").status()).as("now a known method, just not allowed here").isEqualTo(405);
    }

    // ------------------------------------------------------ groups, middleware

    @Test
    void groupsPrefixTheirRoutesAndNest() throws Exception {
        router.group("/api", api -> {
            api.get("/ping", ctx -> ctx.text("pong"));
            api.get("", ctx -> ctx.text("api root"));
            api.group("/v1", v1 -> v1.get("/users/{id}", ctx -> ctx.text("v1 user " + ctx.pathParam("id"))));
        });

        assertThat(call("GET", "/api/ping")).isEqualTo("pong");
        assertThat(call("GET", "/api")).isEqualTo("api root");
        assertThat(call("GET", "/api/v1/users/3")).isEqualTo("v1 user 3");
        assertThat(failure("GET", "/ping").status()).isEqualTo(404);
    }

    @Test
    void middlewareRunsInRegistrationOrderOnTheWayInAndReversedOnTheWayOut() throws Exception {
        List<String> trace = new ArrayList<>();
        router.use(tracing(trace, "global-1"));
        router.use(tracing(trace, "global-2"));
        router.group("/api", api -> {
            api.use(tracing(trace, "group"));
            api.get("/x", ctx -> {
                trace.add("handler");
                ctx.text("x");
            });
        });

        call("GET", "/api/x");

        assertThat(trace).containsExactly("global-1 in", "global-2 in", "group in", "handler",
                "group out", "global-2 out", "global-1 out");
    }

    @Test
    void groupMiddlewareAppliesOnlyToItsGroupAndOnlyToRoutesRegisteredAfterIt() throws Exception {
        List<String> trace = new ArrayList<>();
        router.get("/public", ctx -> ctx.text("public"));
        router.group("/admin", admin -> {
            admin.get("/before", ctx -> ctx.text("before"));
            admin.use(tracing(trace, "auth"));
            admin.get("/after", ctx -> ctx.text("after"));
            admin.group("/deep", deep -> deep.get("/x", ctx -> ctx.text("deep")));
        });

        call("GET", "/public");
        call("GET", "/admin/before");
        assertThat(trace).isEmpty();

        call("GET", "/admin/after");
        call("GET", "/admin/deep/x");
        assertThat(trace).containsExactly("auth in", "auth out", "auth in", "auth out");
    }

    @Test
    void middlewareCanShortCircuitAndCanChangeTheResponseOnTheWayOut() throws Exception {
        router.use((ctx, next) -> {
            if (ctx.header("Authorization").isEmpty()) {
                ctx.status(401).text("who are you?");
                return;
            }
            next.handle(ctx);
            ctx.header("X-Handled-By", "middleware");
        });
        router.get("/secret", ctx -> ctx.text("the secret"));

        Context anonymous = context("GET", "/secret");
        router.handle(anonymous);
        assertThat(anonymous.response().status()).isEqualTo(401);
        assertThat(body(anonymous)).isEqualTo("who are you?");

        Context authorised = context("GET", "/secret", "Authorization: Bearer t");
        router.handle(authorised);
        assertThat(body(authorised)).isEqualTo("the secret");
        assertThat(authorised.response().headers().get("X-Handled-By")).isEqualTo("middleware");
    }

    @Test
    void serverLevelMiddlewareAlsoSeesRequestsThatMatchNoRoute() {
        List<String> seen = new ArrayList<>();
        router.use((ctx, next) -> {
            seen.add(ctx.path());
            next.handle(ctx);
        });

        assertThat(failure("GET", "/nowhere").status()).isEqualTo(404);
        assertThat(seen).containsExactly("/nowhere");
    }

    @Test
    void middlewarePassesAttributesToTheHandler() throws Exception {
        router.use((ctx, next) -> {
            ctx.attribute("user", "ada");
            next.handle(ctx);
        });
        router.get("/whoami", ctx -> ctx.text(ctx.<String>attribute("user")));

        assertThat(call("GET", "/whoami")).isEqualTo("ada");
    }

    // ----------------------------------------------------------- registration

    @Test
    void rejectsInvalidRouteDefinitions() {
        router.get("/dup", ctx -> { });

        assertThatThrownBy(() -> router.get("/dup", ctx -> { })).hasMessageContaining("duplicate route");
        assertThatThrownBy(() -> router.get("/a/*/b", ctx -> { })).hasMessageContaining("wildcard must be the last");
        assertThatThrownBy(() -> router.get("/a/{x}/{x}", ctx -> { })).hasMessageContaining("duplicate path parameter");
        assertThatThrownBy(() -> router.get("no-slash", ctx -> { })).hasMessageContaining("must start with '/'");
        assertThatThrownBy(() -> router.get("/a/{broken", ctx -> { })).hasMessageContaining("invalid segment");
        assertThatThrownBy(() -> router.get("/a//b", ctx -> { })).hasMessageContaining("invalid segment");
    }

    @Test
    void theSamePathMayHaveDifferentHandlersPerMethod() throws Exception {
        router.get("/r", ctx -> ctx.text("get"));
        router.post("/r", ctx -> ctx.text("post"));
        router.put("/r", ctx -> ctx.text("put"));
        router.patch("/r", ctx -> ctx.text("patch"));
        router.delete("/r", ctx -> ctx.text("delete"));

        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            assertThat(call(method, "/r")).isEqualTo(method.toLowerCase());
        }
    }

    private static Middleware tracing(List<String> trace, String name) {
        return (ctx, next) -> {
            trace.add(name + " in");
            next.handle(ctx);
            trace.add(name + " out");
        };
    }
}
