package io.github.mgeladzerezo.sockethttp.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One finished exchange, as recorded by the access log.
 *
 * @param remoteHost    client IP address, or {@code "-"}
 * @param time          when the response was completed
 * @param requestLine   the request line as received, or {@code null} when the request could
 *                      not be parsed that far
 * @param status        response status
 * @param bytesSent     response content bytes, excluding headers and chunk framing
 * @param durationNanos time from the complete request to the last response byte; 0 for
 *                      requests rejected by the parser
 */
public record AccessLogEntry(String remoteHost, Instant time, String requestLine, int status,
                             long bytesSent, long durationNanos) {

    private static final DateTimeFormatter CLF_TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.US).withZone(ZoneId.systemDefault());

    /**
     * The entry in NCSA Common Log Format:
     * {@code host ident authuser [date] "request" status bytes}. Identity and user are always
     * {@code -}; a zero-byte body is logged as {@code -}, as Apache does. Quotes and
     * backslashes in the request line are escaped so a crafted request cannot forge a second
     * log record.
     */
    public String toCommonLogFormat() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(remoteHost).append(" - - [").append(CLF_TIME.format(time)).append("] \"");
        if (requestLine == null) {
            sb.append('-');
        } else {
            for (int i = 0; i < requestLine.length(); i++) {
                char c = requestLine.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c < 0x20 || c >= 0x7F) {
                    sb.append("\\x").append(String.format("%02x", (int) c & 0xFF));
                } else {
                    sb.append(c);
                }
            }
        }
        sb.append("\" ").append(status).append(' ');
        if (bytesSent > 0) {
            sb.append(bytesSent);
        } else {
            sb.append('-');
        }
        return sb.toString();
    }
}
