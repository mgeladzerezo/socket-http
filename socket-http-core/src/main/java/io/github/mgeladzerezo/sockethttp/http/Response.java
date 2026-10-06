package io.github.mgeladzerezo.sockethttp.http;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The response a handler builds: status, headers and a description of the body.
 *
 * <p>Framing headers ({@code Content-Length}, {@code Transfer-Encoding}, {@code Connection})
 * are owned by the server and overwritten when the response is written, so a handler cannot
 * produce a response whose declared length disagrees with its bytes.
 */
public final class Response {

    private int status = HttpStatus.OK;
    private final Headers headers = new Headers();
    private ResponseBody body = ResponseBody.EMPTY;

    public int status() {
        return status;
    }

    public Response status(int status) {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("status must have three digits: " + status);
        }
        this.status = status;
        return this;
    }

    public Headers headers() {
        return headers;
    }

    /** Sets a header, replacing any previous value. */
    public Response header(String name, String value) {
        checkField(name, value);
        headers.set(name, value);
        return this;
    }

    /** Adds a header without removing existing fields of the same name (for {@code Set-Cookie}). */
    public Response addHeader(String name, String value) {
        checkField(name, value);
        headers.add(name, value);
        return this;
    }

    public ResponseBody body() {
        return body;
    }

    public Response body(ResponseBody body) {
        this.body = Objects.requireNonNull(body, "body");
        return this;
    }

    /** Sets an in-memory body and its media type. */
    public Response body(byte[] data, String contentType) {
        this.body = new ResponseBody.Bytes(Objects.requireNonNull(data, "data"));
        if (contentType != null) {
            header("Content-Type", contentType);
        }
        return this;
    }

    /** Sets a UTF-8 text body. */
    public Response body(String text, String contentType) {
        return body(text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    /** Clears status, headers and body; used before an error response replaces a half-built one. */
    public Response reset() {
        status = HttpStatus.OK;
        for (String name : headers.names()) {
            headers.remove(name);
        }
        body = ResponseBody.EMPTY;
        return this;
    }

    /**
     * Refuses header names that are not tokens and values containing CR, LF or NUL. A value
     * built from request data must never be able to end the header line and start a new one
     * (response splitting), so this is enforced at the API rather than left to each handler.
     */
    private static void checkField(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("header name is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean token = c > 0x20 && c < 0x7F && "()<>@,;:\\\"/[]?={}".indexOf(c) < 0;
            if (!token) {
                throw new IllegalArgumentException("invalid character in header name: " + name);
            }
        }
        Objects.requireNonNull(value, "header value");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException("header value for " + name + " contains CR, LF or NUL");
            }
        }
    }
}
