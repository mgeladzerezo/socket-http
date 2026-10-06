package io.github.mgeladzerezo.sockethttp.http;

import java.io.IOException;

/**
 * The content of a response, as the handler described it. The handler does not write to the
 * socket itself: it leaves a {@code ResponseBody} on the {@link Response}, and the server
 * writes it after the handler and every middleware have returned. That is what lets
 * middleware still change headers, lets HEAD be answered by simply not running the writer,
 * and keeps framing decisions (Content-Length or chunked) in one place.
 */
public sealed interface ResponseBody {

    /** A body that is already in memory; sent with {@code Content-Length}. */
    record Bytes(byte[] data) implements ResponseBody {
    }

    /**
     * A body produced by a callback while the response is being written.
     *
     * @param length the exact number of bytes the writer will produce, or {@code -1} when
     *               unknown, in which case the response is chunked (HTTP/1.1) or delimited by
     *               closing the connection (HTTP/1.0)
     */
    record Stream(long length, Writer writer) implements ResponseBody {
    }

    /** Produces a streaming body. */
    @FunctionalInterface
    interface Writer {
        void writeTo(BodySink sink) throws IOException;
    }

    ResponseBody EMPTY = new Bytes(new byte[0]);
}
