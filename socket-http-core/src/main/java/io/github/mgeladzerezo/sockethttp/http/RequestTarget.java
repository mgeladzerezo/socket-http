package io.github.mgeladzerezo.sockethttp.http;

import java.util.List;
import java.util.Map;

/**
 * A parsed request-target (RFC 9112 §3.2).
 *
 * @param form      which of the four syntactic forms the client used
 * @param raw       the target exactly as received
 * @param scheme    lower-cased scheme for absolute-form, otherwise {@code null}
 * @param authority {@code host[:port]} for absolute-form and authority-form, otherwise {@code null}
 * @param rawPath   the path as received, still percent-encoded; {@code "/"} when absolute-form
 *                  has no path; {@code "*"} for asterisk-form
 * @param path      the path with each segment percent-decoded
 * @param segments  the decoded path split on {@code /}, without the leading empty segment.
 *                  {@code "/a/b/"} gives {@code ["a", "b", ""]}; the root path gives {@code [""]}.
 *                  Segments are decoded <em>after</em> splitting, so an encoded slash
 *                  ({@code %2F}) stays inside its segment instead of creating a new one
 * @param rawQuery  the query as received without the {@code ?}, or {@code null} if there was none
 * @param query     decoded query parameters in order of first appearance
 */
public record RequestTarget(Form form,
                            String raw,
                            String scheme,
                            String authority,
                            String rawPath,
                            String path,
                            List<String> segments,
                            String rawQuery,
                            Map<String, List<String>> query) {

    /** The four request-target forms of RFC 9112 §3.2. */
    public enum Form {
        /** {@code /path?query}: the normal case. */
        ORIGIN,
        /** {@code http://host/path?query}: sent to proxies, and servers must accept it too. */
        ABSOLUTE,
        /** {@code host:port}: only with CONNECT. */
        AUTHORITY,
        /** {@code *}: only with OPTIONS. */
        ASTERISK
    }
}
