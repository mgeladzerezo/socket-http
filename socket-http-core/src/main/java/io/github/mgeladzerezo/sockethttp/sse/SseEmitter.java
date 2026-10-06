package io.github.mgeladzerezo.sockethttp.sse;

import io.github.mgeladzerezo.sockethttp.http.BodySink;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Writes a Server-Sent Events stream (the {@code text/event-stream} format of the WHATWG
 * HTML standard). Each send is flushed, so the client sees the event immediately.
 *
 * <p>A send throws {@link IOException} once the client has disconnected; that is how a
 * long-running producer loop learns it should stop. Idle streams should send a
 * {@link #comment} now and then for the same reason, because a server that never writes
 * never notices a vanished client.
 */
public final class SseEmitter {

    private final BodySink sink;
    private final BooleanSupplier serverStopping;

    public SseEmitter(BodySink sink, BooleanSupplier serverStopping) {
        this.sink = sink;
        this.serverStopping = serverStopping;
    }

    /** Sends an unnamed event, which browsers deliver to {@code onmessage}. */
    public void send(String data) throws IOException {
        send(null, data, null);
    }

    /** Sends a named event, delivered to listeners for {@code event}. */
    public void send(String event, String data) throws IOException {
        send(event, data, null);
    }

    /**
     * @param event event name, or {@code null} for the default {@code message} type
     * @param data  payload; a multi-line payload becomes several {@code data:} lines, which
     *              the client joins with newlines again
     * @param id    sets the client's last-event-id for reconnection, or {@code null}
     */
    public void send(String event, String data, String id) throws IOException {
        StringBuilder sb = new StringBuilder(data.length() + 32);
        if (event != null) {
            sb.append("event: ").append(singleLine(event)).append('\n');
        }
        if (id != null) {
            sb.append("id: ").append(singleLine(id)).append('\n');
        }
        for (String line : data.split("\r\n|\r|\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        sink.write(sb.toString());
        sink.flush();
    }

    /** Sends a comment line, which clients ignore: a heartbeat that also detects dead connections. */
    public void comment(String text) throws IOException {
        sink.write(": " + singleLine(text) + "\n\n");
        sink.flush();
    }

    /** Tells the client how long to wait before reconnecting after the stream drops. */
    public void retry(long millis) throws IOException {
        sink.write("retry: " + millis + "\n\n");
        sink.flush();
    }

    /**
     * Whether the server has begun a graceful shutdown. A stream never finishes by itself, so
     * a producer loop should check this and return; otherwise the stream is cut when the
     * shutdown grace period runs out.
     */
    public boolean serverStopping() {
        return serverStopping.getAsBoolean();
    }

    private static String singleLine(String s) {
        return s.replace('\r', ' ').replace('\n', ' ');
    }
}
