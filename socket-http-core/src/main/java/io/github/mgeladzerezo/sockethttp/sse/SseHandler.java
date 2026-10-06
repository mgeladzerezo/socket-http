package io.github.mgeladzerezo.sockethttp.sse;

/** Produces the events of one Server-Sent Events stream; the stream ends when this returns. */
@FunctionalInterface
public interface SseHandler {
    void handle(SseEmitter emitter) throws Exception;
}
