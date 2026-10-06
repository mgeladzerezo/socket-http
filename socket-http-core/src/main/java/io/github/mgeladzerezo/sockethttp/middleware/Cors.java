package io.github.mgeladzerezo.sockethttp.middleware;

import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.http.ResponseBody;
import io.github.mgeladzerezo.sockethttp.routing.Context;
import io.github.mgeladzerezo.sockethttp.routing.Handler;
import io.github.mgeladzerezo.sockethttp.routing.Middleware;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-Origin Resource Sharing (the Fetch standard's CORS protocol).
 *
 * <p>Requests without an {@code Origin} header, and requests from origins that are not
 * allowed, pass through untouched: the browser enforces the policy by the absence of the
 * {@code Access-Control-Allow-Origin} header. Preflight requests ({@code OPTIONS} with
 * {@code Access-Control-Request-Method}) are answered here and never reach a route.
 *
 * <p>The response headers are set before the rest of the chain runs, so they are also present
 * on error responses; a browser would otherwise hide the real status behind a CORS failure.
 */
public final class Cors implements Middleware {

    private final Set<String> allowedOrigins;
    private final boolean anyOrigin;
    private final String allowedMethods;
    private final String allowedHeaders;
    private final String exposedHeaders;
    private final boolean allowCredentials;
    private final long maxAgeSeconds;

    private Cors(Builder builder) {
        this.allowedOrigins = Set.copyOf(builder.origins);
        this.anyOrigin = builder.anyOrigin;
        this.allowedMethods = String.join(", ", builder.methods);
        this.allowedHeaders = builder.headers.isEmpty() ? null : String.join(", ", builder.headers);
        this.exposedHeaders = builder.exposed.isEmpty() ? null : String.join(", ", builder.exposed);
        this.allowCredentials = builder.credentials;
        this.maxAgeSeconds = builder.maxAge.toSeconds();
        if (anyOrigin && allowCredentials) {
            // Browsers reject "*" together with credentials; fail at start-up, not in production.
            throw new IllegalArgumentException("credentials cannot be allowed for every origin; list the origins");
        }
    }

    /** Allows any origin to read responses, without credentials. */
    public static Cors permissive() {
        return builder().allowAnyOrigin().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void handle(Context ctx, Handler next) throws Exception {
        String origin = ctx.header("Origin").orElse(null);
        if (origin == null || !(anyOrigin || allowedOrigins.contains(origin))) {
            next.handle(ctx);
            return;
        }
        ctx.header("Access-Control-Allow-Origin", anyOrigin ? "*" : origin);
        if (!anyOrigin) {
            // The response differs per origin, so shared caches must key on it.
            ctx.response().addHeader("Vary", "Origin");
        }
        if (allowCredentials) {
            ctx.header("Access-Control-Allow-Credentials", "true");
        }

        boolean preflight = ctx.method().equals("OPTIONS") && ctx.header("Access-Control-Request-Method").isPresent();
        if (preflight) {
            ctx.header("Access-Control-Allow-Methods", allowedMethods);
            String requested = ctx.header("Access-Control-Request-Headers").orElse(null);
            if (allowedHeaders != null) {
                ctx.header("Access-Control-Allow-Headers", allowedHeaders);
            } else if (requested != null) {
                ctx.header("Access-Control-Allow-Headers", requested);
            }
            ctx.header("Access-Control-Max-Age", Long.toString(maxAgeSeconds));
            ctx.response().status(HttpStatus.NO_CONTENT).body(ResponseBody.EMPTY);
            return;
        }
        if (exposedHeaders != null) {
            ctx.header("Access-Control-Expose-Headers", exposedHeaders);
        }
        next.handle(ctx);
    }

    /** Configuration for {@link Cors}. */
    public static final class Builder {
        private final Set<String> origins = new LinkedHashSet<>();
        private boolean anyOrigin;
        private List<String> methods = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
        private List<String> headers = List.of();
        private List<String> exposed = List.of();
        private boolean credentials;
        private Duration maxAge = Duration.ofMinutes(10);

        private Builder() {
        }

        /** Origins such as {@code https://app.example.org}, compared exactly. */
        public Builder allowOrigins(String... origins) {
            this.origins.addAll(List.of(origins));
            return this;
        }

        public Builder allowAnyOrigin() {
            this.anyOrigin = true;
            return this;
        }

        public Builder allowMethods(String... methods) {
            this.methods = List.of(methods);
            return this;
        }

        /** Request headers scripts may send; when unset, whatever the preflight asks for is granted. */
        public Builder allowHeaders(String... headers) {
            this.headers = List.of(headers);
            return this;
        }

        /** Response headers scripts may read beyond the safelisted ones. */
        public Builder exposeHeaders(String... headers) {
            this.exposed = List.of(headers);
            return this;
        }

        public Builder allowCredentials(boolean allow) {
            this.credentials = allow;
            return this;
        }

        /** How long a browser may cache a preflight result. */
        public Builder maxAge(Duration maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        public Cors build() {
            return new Cors(this);
        }
    }
}
