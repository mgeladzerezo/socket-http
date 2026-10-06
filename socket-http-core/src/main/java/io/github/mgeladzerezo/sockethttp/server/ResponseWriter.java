package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.http.BodySink;
import io.github.mgeladzerezo.sockethttp.http.Headers;
import io.github.mgeladzerezo.sockethttp.http.HttpDates;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.http.HttpVersion;
import io.github.mgeladzerezo.sockethttp.http.Response;
import io.github.mgeladzerezo.sockethttp.http.ResponseBody;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Serialises a {@link Response} onto the wire and decides its framing (RFC 9112 §6).
 *
 * <ul>
 *   <li>1xx, 204 and 304 never have content, whatever the handler set.</li>
 *   <li>In-memory content and streams of known length get {@code Content-Length}.</li>
 *   <li>A stream of unknown length is sent chunked to an HTTP/1.1 client. HTTP/1.0 has no
 *       chunked coding, so there the content is delimited by closing the connection.</li>
 *   <li>A HEAD response has the headers of the corresponding GET and no content; a streaming
 *       writer is simply never invoked.</li>
 * </ul>
 * The status line always says HTTP/1.1, also to an HTTP/1.0 client: the version states what
 * the server is capable of, and the response itself only uses features the client's version
 * has (RFC 9110 §2.5).
 */
final class ResponseWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private ResponseWriter() {
    }

    /**
     * Whether this response can only be delimited by closing the connection, which rules out
     * keep-alive: an HTTP/1.0 client and content of unknown length.
     */
    static boolean requiresClose(HttpVersion version, boolean headRequest, Response response) {
        return version == HttpVersion.HTTP_1_0
                && !headRequest
                && !HttpStatus.forbidsBody(response.status())
                && response.body() instanceof ResponseBody.Stream stream
                && stream.length() < 0;
    }

    /** Sends the interim response that tells a client waiting on {@code Expect: 100-continue} to go on. */
    static void writeContinue(OutputBuffer out) throws IOException {
        out.write(CONTINUE);
        out.flush();
    }

    /**
     * Writes head and content and flushes.
     *
     * @return the number of content bytes sent, not counting headers or chunk framing
     * @throws IOException if the client went away, or the handler's stream failed or broke its
     *                     declared length; the connection must then be closed, because the
     *                     client can no longer tell where this response ends
     */
    static long write(OutputBuffer out, HttpVersion version, boolean headRequest, Response response,
                      boolean keepAlive, String serverName) throws IOException {
        int status = response.status();
        Headers headers = response.headers();
        ResponseBody body = response.body();
        boolean bodyAllowed = !HttpStatus.forbidsBody(status);
        boolean chunked = false;

        headers.remove("Transfer-Encoding");
        headers.remove("Connection");
        if (!bodyAllowed) {
            if (status != HttpStatus.NOT_MODIFIED) {
                headers.remove("Content-Length");
            }
        } else if (body instanceof ResponseBody.Bytes bytes) {
            headers.set("Content-Length", Integer.toString(bytes.data().length));
        } else if (body instanceof ResponseBody.Stream stream && stream.length() >= 0) {
            headers.set("Content-Length", Long.toString(stream.length()));
        } else {
            headers.remove("Content-Length");
            chunked = version == HttpVersion.HTTP_1_1;
            if (chunked) {
                headers.set("Transfer-Encoding", "chunked");
            }
        }
        if (!keepAlive) {
            headers.set("Connection", "close");
        } else if (version == HttpVersion.HTTP_1_0) {
            // An HTTP/1.0 client assumes close unless told otherwise (RFC 9112 §9.3).
            headers.set("Connection", "keep-alive");
        }

        out.writeLatin1("HTTP/1.1 ");
        out.writeLatin1(Integer.toString(status));
        out.writeByte(' ');
        out.writeLatin1(HttpStatus.reason(status));
        out.write(CRLF);
        if (!headers.contains("Date")) {
            writeField(out, "Date", HttpDates.now());
        }
        if (!serverName.isEmpty() && !headers.contains("Server")) {
            writeField(out, "Server", serverName);
        }
        for (int i = 0; i < headers.size(); i++) {
            writeField(out, headers.nameAt(i), headers.valueAt(i));
        }
        out.write(CRLF);

        long contentBytes = 0;
        if (bodyAllowed && !headRequest) {
            switch (body) {
                case ResponseBody.Bytes bytes -> {
                    out.write(bytes.data());
                    contentBytes = bytes.data().length;
                }
                case ResponseBody.Stream stream -> {
                    CountingSink sink = stream.length() >= 0 ? new FixedLengthSink(out, stream.length())
                            : chunked ? new ChunkedSink(out) : new CountingSink(out);
                    try {
                        stream.writer().writeTo(sink);
                    } catch (RuntimeException e) {
                        throw new IOException("response stream failed after the head was sent", e);
                    }
                    sink.finish();
                    contentBytes = sink.count;
                }
            }
        }
        out.flush();
        return contentBytes;
    }

    private static void writeField(OutputBuffer out, String name, String value) throws IOException {
        out.writeLatin1(name);
        out.writeByte(':');
        out.writeByte(' ');
        out.writeLatin1(value);
        out.write(CRLF);
    }

    /** Pass-through sink: content delimited by connection close. Also the base of the framed sinks. */
    private static class CountingSink implements BodySink {
        final OutputBuffer out;
        long count;

        CountingSink(OutputBuffer out) {
            this.out = out;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
            count += length;
        }

        @Override
        public void transferFrom(FileChannel file, long position, long length) throws IOException {
            out.transferFrom(file, position, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        void finish() throws IOException {
        }
    }

    /** Enforces that a stream with a declared length produces exactly that many bytes. */
    private static final class FixedLengthSink extends CountingSink {
        private final long declared;

        FixedLengthSink(OutputBuffer out, long declared) {
            super(out);
            this.declared = declared;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            check(length);
            super.write(data, offset, length);
        }

        @Override
        public void transferFrom(FileChannel file, long position, long length) throws IOException {
            check(length);
            super.transferFrom(file, position, length);
        }

        private void check(long length) throws IOException {
            if (count + length > declared) {
                throw new IOException("response stream wrote more than its declared Content-Length of " + declared);
            }
        }

        @Override
        void finish() throws IOException {
            if (count != declared) {
                // The client is waiting for bytes that will never come; only closing the
                // connection tells it so.
                throw new IOException("response stream wrote " + count + " bytes, declared " + declared);
            }
        }
    }

    /** Frames each write as one chunk (RFC 9112 §7.1) and ends with the zero-length last chunk. */
    private static final class ChunkedSink extends CountingSink {

        ChunkedSink(OutputBuffer out) {
            super(out);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (length == 0) {
                // A zero-length chunk is the terminator; an empty write must not end the body.
                return;
            }
            chunkHeader(length);
            super.write(data, offset, length);
            out.write(CRLF);
        }

        @Override
        public void transferFrom(FileChannel file, long position, long length) throws IOException {
            if (length == 0) {
                return;
            }
            chunkHeader(length);
            super.transferFrom(file, position, length);
            out.write(CRLF);
        }

        private void chunkHeader(long length) throws IOException {
            byte[] digits = new byte[18];
            int pos = 16;
            long remaining = length;
            do {
                digits[--pos] = HEX[(int) (remaining & 0xF)];
                remaining >>>= 4;
            } while (remaining != 0);
            digits[16] = '\r';
            digits[17] = '\n';
            out.write(digits, pos, 18 - pos);
        }

        @Override
        void finish() throws IOException {
            out.write(LAST_CHUNK);
        }
    }
}
