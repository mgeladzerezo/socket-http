package io.github.mgeladzerezo.sockethttp.parser;

/**
 * A request could not be parsed, or was parsed and must be refused before any handler runs.
 *
 * <p>Carries the status code the server answers with and, where one applies, the RFC section
 * that requires or permits the rejection. The section is included in the error body and in
 * the README table of smuggling defences, so each refusal can be traced to the text behind it.
 * After any such error the connection is closed: once the framing of one message is in doubt,
 * no later byte on the connection can be trusted to start a new message (RFC 9112 §2.2).
 */
public final class HttpParseException extends Exception {

    private final int status;
    private final String reference;

    public HttpParseException(int status, String message, String reference) {
        super(message);
        this.status = status;
        this.reference = reference;
    }

    public HttpParseException(int status, String message) {
        this(status, message, null);
    }

    /** The response status: 400, 413, 414, 417, 431, 501 or 505. */
    public int status() {
        return status;
    }

    /** The governing RFC section, for example {@code "RFC 9112 §6.1"}, or {@code null}. */
    public String reference() {
        return reference;
    }
}
