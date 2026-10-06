package io.github.mgeladzerezo.sockethttp.demo;

import io.github.mgeladzerezo.sockethttp.json.Json;
import io.github.mgeladzerezo.sockethttp.server.HttpServer;
import io.github.mgeladzerezo.sockethttp.sse.SseEmitter;

import java.io.IOException;

/**
 * Pushes a metrics snapshot to one SSE client every second until the client goes away or the
 * server starts shutting down. Each client has its own loop (and, depending on the concurrency
 * model, its own thread), which is exactly the long-lived-connection workload that separates
 * the models.
 */
final class MetricsStream {

    private static final long INTERVAL_MILLIS = 1_000;

    private final HttpServer server;

    MetricsStream(HttpServer server) {
        this.server = server;
    }

    void run(SseEmitter emitter) throws IOException, InterruptedException {
        emitter.retry(2_000);
        long sequence = 0;
        while (!emitter.serverStopping()) {
            emitter.send("metrics", Json.write(server.metrics().snapshot().toMap()), Long.toString(++sequence));
            Thread.sleep(INTERVAL_MILLIS);
        }
    }
}
