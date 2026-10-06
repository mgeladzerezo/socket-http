package io.github.mgeladzerezo.sockethttp.support;

import io.github.mgeladzerezo.sockethttp.http.Headers;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * A test client on a plain socket. Tests decide exactly which bytes go on the wire and in
 * what pieces, and get back exactly what the server sent; nothing is normalised on the way.
 * The response reader is deliberately independent of the server's own parser.
 */
public final class RawClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public RawClient(int port) throws IOException {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        in = new java.io.BufferedInputStream(socket.getInputStream(), 64 * 1024);
        out = socket.getOutputStream();
    }

    public RawClient timeoutMillis(int millis) throws IOException {
        socket.setSoTimeout(millis);
        return this;
    }

    /** Sends text as ISO-8859-1 in one write. */
    public RawClient send(String text) throws IOException {
        return send(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    public RawClient send(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
        return this;
    }

    /** Half-closes: the server sees end of input but can still answer. */
    public RawClient shutdownOutput() throws IOException {
        socket.shutdownOutput();
        return this;
    }

    /** Reads one response to a request that was not HEAD. */
    public RawResponse readResponse() throws IOException {
        return readResponse(false);
    }

    /**
     * Reads one response.
     *
     * @param toHead whether the request was HEAD, in which case no content follows the header
     *               section whatever the headers say
     */
    public RawResponse readResponse(boolean toHead) throws IOException {
        String statusLine = readLine();
        if (statusLine == null) {
            throw new EOFException("connection closed before a status line");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new IOException("malformed status line: " + statusLine);
        }
        int status = Integer.parseInt(parts[1]);
        Headers headers = new Headers();
        String line;
        while (!(line = requireLine()).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("malformed header line: " + line);
            }
            headers.add(line.substring(0, colon), line.substring(colon + 1).trim());
        }

        byte[] body;
        Headers trailers = new Headers();
        boolean chunked = headers.hasToken("Transfer-Encoding", "chunked");
        if (toHead || status / 100 == 1 || status == 204 || status == 304) {
            body = new byte[0];
        } else if (chunked) {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = requireLine();
                int semicolon = sizeLine.indexOf(';');
                int size = Integer.parseInt(semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon), 16);
                if (size == 0) {
                    break;
                }
                content.write(readExactly(size));
                if (!requireLine().isEmpty()) {
                    throw new IOException("chunk data not followed by CRLF");
                }
            }
            while (!(line = requireLine()).isEmpty()) {
                int colon = line.indexOf(':');
                trailers.add(line.substring(0, colon), line.substring(colon + 1).trim());
            }
            body = content.toByteArray();
        } else if (headers.contains("Content-Length")) {
            body = readExactly(Integer.parseInt(headers.get("Content-Length")));
        } else {
            body = in.readAllBytes();
        }
        return new RawResponse(status, parts.length > 2 ? parts[2] : "", parts[0], headers, body, trailers);
    }

    /** Reads everything until the server closes the connection. */
    public String readUntilClose() throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Whether the server has closed the connection: true if end-of-stream (or a reset) is seen
     * within {@code withinMillis}, false if the connection is still open and silent.
     */
    public boolean isClosedByServer(int withinMillis) throws IOException {
        int previous = socket.getSoTimeout();
        socket.setSoTimeout(withinMillis);
        try {
            return in.read() < 0;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (SocketException e) {
            return true;
        } finally {
            socket.setSoTimeout(previous);
        }
    }

    /** Reads one CRLF-terminated line; {@code null} at end of stream before any byte. */
    public String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new EOFException("connection closed in the middle of a line: " + line);
            }
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') {
                    throw new IOException("line terminated by bare LF: " + line);
                }
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
    }

    private String requireLine() throws IOException {
        String line = readLine();
        if (line == null) {
            throw new EOFException("connection closed inside a response");
        }
        return line;
    }

    public byte[] readExactly(int n) throws IOException {
        byte[] bytes = in.readNBytes(n);
        if (bytes.length != n) {
            throw new EOFException("expected " + n + " bytes, got " + bytes.length);
        }
        return bytes;
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
