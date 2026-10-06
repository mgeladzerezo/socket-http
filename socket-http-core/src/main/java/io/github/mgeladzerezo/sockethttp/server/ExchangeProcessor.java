package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.http.Headers;
import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.http.HttpVersion;
import io.github.mgeladzerezo.sockethttp.http.Response;
import io.github.mgeladzerezo.sockethttp.metrics.ServerMetrics;
import io.github.mgeladzerezo.sockethttp.routing.Context;
import io.github.mgeladzerezo.sockethttp.routing.Handler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Turns one parsed request into one written response. This is the part every concurrency
 * model shares: an engine's only jobs are to get bytes into the parser and to provide an
 * {@link OutputBuffer} that can be written to with blocking semantics.
 */
final class ExchangeProcessor {

    private static final System.Logger LOG = System.getLogger(ExchangeProcessor.class.getName());

    /** Headers describing a representation the handler started to build before it failed. */
    private static final String[] REPRESENTATION_HEADERS = {
            "Content-Type", "Content-Encoding", "Content-Range", "Content-Disposition",
            "ETag", "Last-Modified", "Accept-Ranges", "Cache-Control"};

    private final ServerConfig config;
    private final Handler handler;
    private final ServerMetrics metrics;
    private final AccessLog accessLog;
    private final BooleanSupplier stopping;

    ExchangeProcessor(ServerConfig config, Handler handler, ServerMetrics metrics, AccessLog accessLog,
                      BooleanSupplier stopping) {
        this.config = config;
        this.handler = handler;
        this.metrics = metrics;
        this.accessLog = accessLog;
        this.stopping = stopping;
    }

    /**
     * Runs the handler chain and writes the response.
     *
     * @param requestsOnConnection how many requests this connection has carried, including this one
     * @return {@code true} if the connection may carry another request
     * @throws IOException if the response could not be written completely; the caller must
     *                     close the connection without trying to reuse it
     */
    boolean process(OutputBuffer out, HttpRequest request, SocketAddress remote, int requestsOnConnection)
            throws IOException {
        long start = System.nanoTime();
        metrics.requestStarted();
        Context ctx = new Context(request, remote, stopping);
        Response response = ctx.response();
        try {
            handler.handle(ctx);
        } catch (HttpException e) {
            renderError(response, e.status(), e.getMessage(), null);
            e.headers().forEach(response::header);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.ERROR, "handler failed for " + request.requestLine(), e);
            renderError(response, HttpStatus.INTERNAL_SERVER_ERROR, "The server failed to handle the request.", null);
        }

        boolean head = request.method().equals("HEAD");
        boolean keepAlive = clientAllowsReuse(request)
                && !(config.maxRequestsPerConnection() > 0 && requestsOnConnection >= config.maxRequestsPerConnection())
                && !stopping.getAsBoolean()
                && !response.headers().hasToken("Connection", "close")
                && !ResponseWriter.requiresClose(request.version(), head, response);

        int status = response.status();
        long bytesBefore = out.bytesWritten();
        long contentBytes = 0;
        try {
            contentBytes = ResponseWriter.write(out, request.version(), head, response, keepAlive, config.serverName());
        } finally {
            long duration = System.nanoTime() - start;
            metrics.requestCompleted(status, duration);
            metrics.bytesSent(out.bytesWritten() - bytesBefore);
            if (accessLog != null) {
                accessLog.log(new AccessLogEntry(host(remote), Instant.now(), request.requestLine(), status,
                        contentBytes, duration));
            }
        }
        return keepAlive;
    }

    /**
     * Answers a request that never reached routing: a parse error, a read timeout, or an
     * overloaded server. The response always carries {@code Connection: close}.
     *
     * @param version the request's version if the parser got that far, else {@code null}
     */
    void reject(OutputBuffer out, HttpVersion version, int status, String message, String reference,
                SocketAddress remote) throws IOException {
        Response response = new Response();
        renderError(response, status, message, reference);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header("Retry-After", "1");
        }
        long bytesBefore = out.bytesWritten();
        long contentBytes = 0;
        try {
            contentBytes = ResponseWriter.write(out, version == null ? HttpVersion.HTTP_1_1 : version, false,
                    response, false, config.serverName());
        } finally {
            metrics.requestRejected(status);
            metrics.bytesSent(out.bytesWritten() - bytesBefore);
            if (accessLog != null) {
                accessLog.log(new AccessLogEntry(host(remote), Instant.now(), null, status, contentBytes, 0));
            }
        }
    }

    ServerMetrics metrics() {
        return metrics;
    }

    /** RFC 9112 §9.3: HTTP/1.1 persists unless "close"; HTTP/1.0 only with "keep-alive". */
    private static boolean clientAllowsReuse(HttpRequest request) {
        Headers headers = request.headers();
        if (headers.get("Connection") == null) {
            return request.version() == HttpVersion.HTTP_1_1;
        }
        if (headers.hasToken("Connection", "close")) {
            return false;
        }
        return request.version() == HttpVersion.HTTP_1_1 || headers.hasToken("Connection", "keep-alive");
    }

    private static void renderError(Response response, int status, String message, String reference) {
        for (String name : REPRESENTATION_HEADERS) {
            response.headers().remove(name);
        }
        StringBuilder body = new StringBuilder(96);
        body.append(status).append(' ').append(HttpStatus.reason(status)).append('\n');
        if (message != null && !message.isBlank()) {
            body.append(message).append('\n');
        }
        if (reference != null) {
            body.append('(').append(reference).append(")\n");
        }
        response.status(status).body(body.toString(), "text/plain; charset=utf-8");
    }

    private static String host(SocketAddress remote) {
        return remote instanceof InetSocketAddress inet && inet.getAddress() != null
                ? inet.getAddress().getHostAddress() : "-";
    }
}
