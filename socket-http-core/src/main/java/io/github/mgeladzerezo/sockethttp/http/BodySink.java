package io.github.mgeladzerezo.sockethttp.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Where a streaming response writes its content. The server supplies an implementation that
 * applies the right framing: chunked for HTTP/1.1 responses of unknown length, a byte count
 * check for responses with a declared length, or plain bytes until close for HTTP/1.0.
 *
 * <p>Writes block until the bytes have been handed to the socket (or buffered), under every
 * concurrency model. An {@link IOException} means the client is gone; let it propagate.
 */
public interface BodySink {

    void write(byte[] data, int offset, int length) throws IOException;

    default void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    /** Writes text as UTF-8. */
    default void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends {@code count} bytes of a file starting at {@code position} using
     * {@link FileChannel#transferTo}, so the kernel copies from the page cache to the socket
     * without the bytes passing through the Java heap.
     */
    void transferFrom(FileChannel file, long position, long count) throws IOException;

    /** Pushes everything written so far onto the wire; needed for incremental delivery (SSE). */
    void flush() throws IOException;

    /** An {@link OutputStream} view of this sink; closing it flushes but does not end the response. */
    default OutputStream asOutputStream() {
        BodySink sink = this;
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                sink.write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                sink.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                sink.flush();
            }

            @Override
            public void close() throws IOException {
                sink.flush();
            }
        };
    }
}
