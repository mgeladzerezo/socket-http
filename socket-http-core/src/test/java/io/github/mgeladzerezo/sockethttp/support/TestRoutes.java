package io.github.mgeladzerezo.sockethttp.support;

import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.server.HttpServer;

import java.util.LinkedHashMap;
import java.util.Map;

/** The routes the wire-level tests talk to. */
public final class TestRoutes {

    private TestRoutes() {
    }

    public static void install(HttpServer server) {
        server.get("/hello", ctx -> ctx.text("hello"));
        server.get("/users/{id}", ctx -> ctx.json(Map.of("id", ctx.pathParam("id"))));
        server.get("/query", ctx -> ctx.text(ctx.queryParam("q").orElse("none")));
        server.get("/host", ctx -> ctx.text(String.valueOf(ctx.request().host())));
        server.get("/empty", ctx -> ctx.noContent());
        server.get("/boom", ctx -> {
            throw new IllegalStateException("internal detail that must not leak");
        });
        server.get("/teapot", ctx -> {
            throw new HttpException(418, "short and stout");
        });
        server.post("/echo", ctx -> {
            ctx.bytes(ctx.body(), ctx.header("Content-Type").orElse("application/octet-stream"));
            ctx.request().trailers().forEach((name, value) -> ctx.header("X-Trailer-" + name, value));
        });
        server.put("/echo", ctx -> ctx.bytes(ctx.body(), "application/octet-stream"));
        server.get("/echo/{token}", ctx -> ctx.text(ctx.pathParam("token")));
        server.get("/stream", ctx -> ctx.stream("text/plain", sink -> {
            sink.write("one");
            sink.flush();
            sink.write("two");
            sink.write("");
            sink.write("three");
        }));
        server.get("/slow", ctx -> {
            Thread.sleep(ctx.queryParamAsInt("ms", 200));
            ctx.text("slow " + ctx.queryParam("id").orElse(""));
        });
        server.get("/big", ctx -> ctx.bytes(pattern(ctx.queryParamAsInt("size", 300_000)), "application/octet-stream"));
        server.get("/inspect", ctx -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("method", ctx.method());
            view.put("path", ctx.path());
            view.put("segments", ctx.request().target().segments());
            view.put("query", ctx.request().query());
            view.put("remote", ctx.remoteHost());
            ctx.json(view);
        });
    }

    /** Deterministic content, so a corrupted or shifted transfer cannot go unnoticed. */
    public static byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + (i >>> 8)) & 0xFF);
        }
        return data;
    }
}
