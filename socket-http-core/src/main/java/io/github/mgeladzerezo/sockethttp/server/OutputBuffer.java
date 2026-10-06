package io.github.mgeladzerezo.sockethttp.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * A small write buffer in front of a {@link Transport}, one per connection.
 *
 * <p>Its job is to make the common response (status line, headers and a short body) leave in
 * one {@code write} call and therefore usually one TCP segment, instead of one syscall per
 * header. Data larger than the buffer bypasses it.
 */
final class OutputBuffer {

    private static final int CAPACITY = 16 * 1024;

    private final Transport transport;
    private final ByteBuffer buffer = ByteBuffer.allocate(CAPACITY);
    private long bytesWritten;

    OutputBuffer(Transport transport) {
        this.transport = transport;
    }

    void write(byte[] data, int offset, int length) throws IOException {
        bytesWritten += length;
        if (length <= buffer.remaining()) {
            buffer.put(data, offset, length);
            return;
        }
        flush();
        if (length >= CAPACITY) {
            transport.write(ByteBuffer.wrap(data, offset, length));
        } else {
            buffer.put(data, offset, length);
        }
    }

    void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    /** Writes text as ISO-8859-1, the charset of HTTP header fields; other characters become '?'. */
    void writeLatin1(String s) throws IOException {
        int length = s.length();
        bytesWritten += length;
        for (int i = 0; i < length; i++) {
            if (!buffer.hasRemaining()) {
                flush();
            }
            char c = s.charAt(i);
            buffer.put(c < 256 ? (byte) c : (byte) '?');
        }
    }

    void writeByte(int b) throws IOException {
        if (!buffer.hasRemaining()) {
            flush();
        }
        buffer.put((byte) b);
        bytesWritten++;
    }

    void transferFrom(FileChannel file, long position, long count) throws IOException {
        flush();
        transport.transferFrom(file, position, count);
        bytesWritten += count;
    }

    void flush() throws IOException {
        if (buffer.position() > 0) {
            buffer.flip();
            try {
                transport.write(buffer);
            } finally {
                buffer.clear();
            }
        }
    }

    /** Drops buffered bytes; used when a connection is abandoned after a failed write. */
    void discard() {
        buffer.clear();
    }

    /** Total bytes accepted since the connection opened, flushed or not. */
    long bytesWritten() {
        return bytesWritten;
    }
}
