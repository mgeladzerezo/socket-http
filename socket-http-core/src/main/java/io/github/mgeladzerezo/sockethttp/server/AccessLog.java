package io.github.mgeladzerezo.sockethttp.server;

import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * Receives one entry per finished exchange, including requests the parser rejected. Called on
 * the thread that wrote the response, after the last byte, so implementations must be
 * thread-safe and quick.
 *
 * <p>This is a server-level hook rather than middleware because the two things an access log
 * needs, the final status and the number of bytes actually sent, are only known after the
 * response has been written, which happens after the middleware chain has returned.
 */
@FunctionalInterface
public interface AccessLog {

    void log(AccessLogEntry entry);

    /** Writes Common Log Format lines to a stream. */
    static AccessLog commonLogFormat(PrintStream out) {
        return entry -> out.println(entry.toCommonLogFormat());
    }

    /** Hands Common Log Format lines to a consumer, for example a logger or a test's list. */
    static AccessLog commonLogFormat(Consumer<String> sink) {
        return entry -> sink.accept(entry.toCommonLogFormat());
    }
}
