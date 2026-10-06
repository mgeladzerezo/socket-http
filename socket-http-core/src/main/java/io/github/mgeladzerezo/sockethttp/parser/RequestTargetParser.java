package io.github.mgeladzerezo.sockethttp.parser;

import io.github.mgeladzerezo.sockethttp.http.RequestTarget;
import io.github.mgeladzerezo.sockethttp.http.RequestTarget.Form;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses and validates the request-target of a request line (RFC 9112 §3.2).
 *
 * <p>Validation is by allow-list. A path may contain only RFC 3986 {@code pchar} characters
 * and {@code /}; a query may contain any visible ASCII character except {@code #} (browsers
 * send {@code [ ] { } |} unescaped in queries, so the stricter RFC 3986 set would reject real
 * traffic). Raw backslashes, spaces, control characters and non-ASCII bytes never get past
 * this class, which removes a whole family of path-confusion tricks before routing starts.
 */
public final class RequestTargetParser {

    private static final String REF = "RFC 9112 §3.2";

    /** Characters allowed in a path: unreserved, sub-delims, ':', '@', '%', '/'. */
    private static final boolean[] PATH_CHAR = new boolean[128];

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            PATH_CHAR[c] = true;
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            PATH_CHAR[c] = true;
        }
        for (char c = '0'; c <= '9'; c++) {
            PATH_CHAR[c] = true;
        }
        for (char c : "-._~!$&'()*+,;=:@%/".toCharArray()) {
            PATH_CHAR[c] = true;
        }
    }

    private RequestTargetParser() {
    }

    /**
     * @param raw    the target bytes as an ISO-8859-1 string
     * @param method the request method, which decides whether authority-form and asterisk-form
     *               are legal
     * @throws HttpParseException 400 for any syntax error
     */
    public static RequestTarget parse(String raw, String method) throws HttpParseException {
        if (raw.isEmpty()) {
            throw new HttpParseException(400, "empty request target", REF);
        }
        if (raw.equals("*")) {
            if (!method.equals("OPTIONS")) {
                throw new HttpParseException(400, "asterisk-form target is only valid with OPTIONS", "RFC 9112 §3.2.4");
            }
            return new RequestTarget(Form.ASTERISK, raw, null, null, "*", "*", List.of(), null, Collections.emptyMap());
        }
        if (raw.charAt(0) == '/') {
            return withPathAndQuery(Form.ORIGIN, raw, null, null, raw);
        }
        if (method.equals("CONNECT")) {
            checkAuthority(raw);
            return new RequestTarget(Form.AUTHORITY, raw, null, raw, "", "", List.of(), null, Collections.emptyMap());
        }
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd <= 0) {
            throw new HttpParseException(400, "request target is neither origin-form nor absolute-form", REF);
        }
        String scheme = raw.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new HttpParseException(400, "unsupported scheme in absolute-form target", "RFC 9112 §3.2.2");
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = raw.length();
        for (int i = authorityStart; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '/' || c == '?') {
                authorityEnd = i;
                break;
            }
        }
        String authority = raw.substring(authorityStart, authorityEnd);
        checkAuthority(authority);
        String rest = raw.substring(authorityEnd);
        if (rest.isEmpty() || rest.charAt(0) == '?') {
            // RFC 9112 §3.2.2 via RFC 9110 §4.2.3: an empty path is equivalent to "/".
            rest = "/" + rest;
        }
        return withPathAndQuery(Form.ABSOLUTE, raw, scheme, authority, rest);
    }

    private static RequestTarget withPathAndQuery(Form form, String raw, String scheme, String authority,
                                                  String pathAndQuery) throws HttpParseException {
        int q = pathAndQuery.indexOf('?');
        String rawPath = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String rawQuery = q < 0 ? null : pathAndQuery.substring(q + 1);

        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c >= 128 || !PATH_CHAR[c]) {
                throw new HttpParseException(400, "illegal character in request path", "RFC 3986 §3.3");
            }
        }
        if (rawQuery != null) {
            for (int i = 0; i < rawQuery.length(); i++) {
                char c = rawQuery.charAt(i);
                if (c <= 0x20 || c >= 0x7F || c == '#') {
                    throw new HttpParseException(400, "illegal character in request query", "RFC 3986 §3.4");
                }
            }
        }

        List<String> segments = new ArrayList<>(4);
        StringBuilder decodedPath = new StringBuilder(rawPath.length());
        int start = 1;
        while (true) {
            int slash = rawPath.indexOf('/', start);
            int end = slash < 0 ? rawPath.length() : slash;
            String segment = PercentCodec.decode(rawPath.substring(start, end), false);
            if (segment.indexOf('\0') >= 0) {
                throw new HttpParseException(400, "NUL in request path", "RFC 3986 §7.3");
            }
            segments.add(segment);
            decodedPath.append('/').append(segment);
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        Map<String, List<String>> query = PercentCodec.parseQuery(rawQuery);
        return new RequestTarget(form, raw, scheme, authority, rawPath, decodedPath.toString(),
                Collections.unmodifiableList(segments), rawQuery, query);
    }

    /**
     * Checks {@code host[:port]}. Userinfo ({@code user@host}) is refused: RFC 9110 §4.2.4
     * deprecates it and asks recipients to treat it as an error, because it is used to make a
     * URL look like it points somewhere else.
     */
    static void checkAuthority(String authority) throws HttpParseException {
        if (authority.isEmpty()) {
            throw new HttpParseException(400, "empty host", "RFC 9110 §4.2.1");
        }
        int portColon = -1;
        boolean inBrackets = false;
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c == '[' && i == 0) {
                inBrackets = true;
            } else if (c == ']' && inBrackets) {
                inBrackets = false;
            } else if (c == ':' && !inBrackets) {
                if (portColon >= 0) {
                    throw new HttpParseException(400, "malformed host", "RFC 3986 §3.2.2");
                }
                portColon = i;
            } else if (c == ':' || c == '.' || c == '-' || c == '_' || c == '~' || c == '%'
                    || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                // host character, or a colon inside an IPv6 literal
            } else if (c == '@') {
                throw new HttpParseException(400, "userinfo is not allowed in the request authority", "RFC 9110 §4.2.4");
            } else {
                throw new HttpParseException(400, "illegal character in host", "RFC 3986 §3.2.2");
            }
        }
        if (inBrackets || portColon == 0) {
            throw new HttpParseException(400, "malformed host", "RFC 3986 §3.2.2");
        }
        if (portColon >= 0) {
            String port = authority.substring(portColon + 1);
            if (port.length() > 5) {
                throw new HttpParseException(400, "malformed port", "RFC 3986 §3.2.3");
            }
            for (int i = 0; i < port.length(); i++) {
                if (port.charAt(i) < '0' || port.charAt(i) > '9') {
                    throw new HttpParseException(400, "malformed port", "RFC 3986 §3.2.3");
                }
            }
        }
    }
}
