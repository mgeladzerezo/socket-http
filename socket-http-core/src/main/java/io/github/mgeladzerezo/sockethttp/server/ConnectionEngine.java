package io.github.mgeladzerezo.sockethttp.server;

import java.time.Duration;

/**
 * One concurrency model: owns the listening socket's accept loop and every connection's
 * lifecycle. The server picks an implementation from {@link ServerConfig#concurrencyModel()}.
 */
interface ConnectionEngine {

    /** Starts accepting connections. */
    void start();

    /**
     * Shuts down gracefully: stops accepting, closes connections that are idle between
     * requests, lets requests that are in flight finish (their responses carry
     * {@code Connection: close}), and after {@code grace} closes whatever is left.
     *
     * @return {@code true} if every connection finished within the grace period
     */
    boolean shutdown(Duration grace);
}
