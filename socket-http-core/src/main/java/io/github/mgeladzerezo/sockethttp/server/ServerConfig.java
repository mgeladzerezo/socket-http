package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.concurrent.RejectionPolicy;
import io.github.mgeladzerezo.sockethttp.parser.ParserLimits;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable server settings. Build one with {@link #builder()}; every value has a default.
 *
 * @param host                     interface to bind
 * @param port                     port to bind; 0 picks a free one (see {@link HttpServer#port()})
 * @param concurrencyModel         how connections map to threads
 * @param workerThreads            size of the worker pool (ignored by {@link ConcurrencyModel#VIRTUAL_THREADS})
 * @param queueCapacity            work that may wait for a worker: connections under
 *                                 {@link ConcurrencyModel#THREAD_POOL}, requests under
 *                                 {@link ConcurrencyModel#NIO_EVENT_LOOP}
 * @param overloadPolicy           what happens when workers and queue are full: {@code BLOCK}
 *                                 stops accepting (backpressure into the listen backlog),
 *                                 {@code ABORT} answers 503 immediately
 * @param maxConnections           open connections allowed at once; at the limit the server
 *                                 stops calling {@code accept()} until one closes
 * @param backlog                  listen backlog passed to the operating system
 * @param keepAliveTimeout         how long an idle persistent connection is kept open
 * @param maxRequestsPerConnection requests served on one connection before the server closes
 *                                 it; 0 means no limit
 * @param headerTimeout            total time allowed from the first byte of a request to the
 *                                 end of its header section (slowloris protection), and the
 *                                 time a new connection may stay silent
 * @param bodyTimeout              total time allowed for the request content after the headers
 * @param writeTimeout             how long a response write may make no progress before the
 *                                 connection is dropped (slow-reader protection)
 * @param shutdownGrace            how long {@link HttpServer#stop()} waits for in-flight requests
 * @param limits                   parser size limits
 * @param serverName               value of the {@code Server} response header; empty to omit it
 */
public record ServerConfig(String host,
                           int port,
                           ConcurrencyModel concurrencyModel,
                           int workerThreads,
                           int queueCapacity,
                           RejectionPolicy overloadPolicy,
                           int maxConnections,
                           int backlog,
                           Duration keepAliveTimeout,
                           int maxRequestsPerConnection,
                           Duration headerTimeout,
                           Duration bodyTimeout,
                           Duration writeTimeout,
                           Duration shutdownGrace,
                           ParserLimits limits,
                           String serverName) {

    public ServerConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(concurrencyModel, "concurrencyModel");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(serverName, "serverName");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (workerThreads < 1 || queueCapacity < 0 || maxConnections < 1 || backlog < 1 || maxRequestsPerConnection < 0) {
            throw new IllegalArgumentException("workerThreads, maxConnections and backlog must be positive; "
                    + "queueCapacity and maxRequestsPerConnection must not be negative");
        }
        if (overloadPolicy != RejectionPolicy.BLOCK && overloadPolicy != RejectionPolicy.ABORT) {
            // CALLER_RUNS would run a whole connection on the acceptor or the event loop.
            throw new IllegalArgumentException("overloadPolicy must be BLOCK or ABORT");
        }
        for (Duration d : new Duration[]{keepAliveTimeout, headerTimeout, bodyTimeout, writeTimeout}) {
            if (d == null || d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("timeouts must be positive");
            }
        }
        if (shutdownGrace == null || shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("shutdownGrace must not be negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A config with every default, bound to a free port on the loopback interface. */
    public static ServerConfig localEphemeral(ConcurrencyModel model) {
        return builder().host("127.0.0.1").port(0).concurrencyModel(model).build();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.host = host;
        b.port = port;
        b.concurrencyModel = concurrencyModel;
        b.workerThreads = workerThreads;
        b.queueCapacity = queueCapacity;
        b.overloadPolicy = overloadPolicy;
        b.maxConnections = maxConnections;
        b.backlog = backlog;
        b.keepAliveTimeout = keepAliveTimeout;
        b.maxRequestsPerConnection = maxRequestsPerConnection;
        b.headerTimeout = headerTimeout;
        b.bodyTimeout = bodyTimeout;
        b.writeTimeout = writeTimeout;
        b.shutdownGrace = shutdownGrace;
        b.limits = limits;
        b.serverName = serverName;
        return b;
    }

    /** Mutable builder; see the record components for what each setting means. */
    public static final class Builder {
        private String host = "0.0.0.0";
        private int port = 8080;
        private ConcurrencyModel concurrencyModel = ConcurrencyModel.VIRTUAL_THREADS;
        private int workerThreads = 200;
        private int queueCapacity = 256;
        private RejectionPolicy overloadPolicy = RejectionPolicy.BLOCK;
        private int maxConnections = 8192;
        private int backlog = 1024;
        private Duration keepAliveTimeout = Duration.ofSeconds(15);
        private int maxRequestsPerConnection = 1000;
        private Duration headerTimeout = Duration.ofSeconds(10);
        private Duration bodyTimeout = Duration.ofSeconds(30);
        private Duration writeTimeout = Duration.ofSeconds(30);
        private Duration shutdownGrace = Duration.ofSeconds(10);
        private ParserLimits limits = ParserLimits.DEFAULT;
        private String serverName = "socket-http";

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder concurrencyModel(ConcurrencyModel model) {
            this.concurrencyModel = model;
            return this;
        }

        public Builder workerThreads(int threads) {
            this.workerThreads = threads;
            return this;
        }

        public Builder queueCapacity(int capacity) {
            this.queueCapacity = capacity;
            return this;
        }

        public Builder overloadPolicy(RejectionPolicy policy) {
            this.overloadPolicy = policy;
            return this;
        }

        public Builder maxConnections(int max) {
            this.maxConnections = max;
            return this;
        }

        public Builder backlog(int backlog) {
            this.backlog = backlog;
            return this;
        }

        public Builder keepAliveTimeout(Duration timeout) {
            this.keepAliveTimeout = timeout;
            return this;
        }

        public Builder maxRequestsPerConnection(int max) {
            this.maxRequestsPerConnection = max;
            return this;
        }

        public Builder headerTimeout(Duration timeout) {
            this.headerTimeout = timeout;
            return this;
        }

        public Builder bodyTimeout(Duration timeout) {
            this.bodyTimeout = timeout;
            return this;
        }

        public Builder writeTimeout(Duration timeout) {
            this.writeTimeout = timeout;
            return this;
        }

        public Builder shutdownGrace(Duration grace) {
            this.shutdownGrace = grace;
            return this;
        }

        public Builder limits(ParserLimits limits) {
            this.limits = limits;
            return this;
        }

        public Builder serverName(String name) {
            this.serverName = name;
            return this;
        }

        public ServerConfig build() {
            return new ServerConfig(host, port, concurrencyModel, workerThreads, queueCapacity, overloadPolicy,
                    maxConnections, backlog, keepAliveTimeout, maxRequestsPerConnection, headerTimeout, bodyTimeout,
                    writeTimeout, shutdownGrace, limits, serverName);
        }
    }
}
