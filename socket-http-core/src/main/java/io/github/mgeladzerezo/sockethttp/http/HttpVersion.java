package io.github.mgeladzerezo.sockethttp.http;

/**
 * The two protocol versions this server speaks. A request with a higher minor version
 * ({@code HTTP/1.2}) is processed as HTTP/1.1, as RFC 9110 §2.5 asks.
 */
public enum HttpVersion {
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1");

    private final String text;

    HttpVersion(String text) {
        this.text = text;
    }

    /** The version as it appears on the wire. */
    public String text() {
        return text;
    }
}
