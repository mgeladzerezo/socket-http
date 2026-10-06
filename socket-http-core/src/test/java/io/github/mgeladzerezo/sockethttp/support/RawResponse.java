package io.github.mgeladzerezo.sockethttp.support;

import io.github.mgeladzerezo.sockethttp.http.Headers;

import java.nio.charset.StandardCharsets;

/** A response as read off the wire by {@link RawClient}; chunked content is already decoded. */
public record RawResponse(int status, String reason, String version, Headers headers, byte[] body, Headers trailers) {

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public String header(String name) {
        return headers.get(name);
    }

    @Override
    public String toString() {
        return version + " " + status + " " + reason + "\n" + headers + "\n" + text();
    }
}
