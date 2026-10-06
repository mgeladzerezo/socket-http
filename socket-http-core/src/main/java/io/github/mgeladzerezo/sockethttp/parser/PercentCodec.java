package io.github.mgeladzerezo.sockethttp.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Percent-decoding (RFC 3986 §2.1) and query-string parsing.
 *
 * <p>Decoding is strict: a {@code %} not followed by two hex digits is an error, and the
 * decoded octets must be well-formed UTF-8. Lenient decoders that pass "%zz" through or
 * replace bad sequences with U+FFFD let two components of a system disagree about what a
 * path means, which is how filter bypasses happen; refusing is cheaper than reasoning about it.
 */
public final class PercentCodec {

    private static final String REF = "RFC 3986 §2.1";

    private PercentCodec() {
    }

    /**
     * Decodes one path segment or query component.
     *
     * @param plusAsSpace treat {@code +} as a space, as HTML form encoding does in queries;
     *                    must be {@code false} for paths, where {@code +} is a literal plus
     * @throws HttpParseException 400 on a malformed escape or on octets that are not UTF-8
     */
    public static String decode(String s, boolean plusAsSpace) throws HttpParseException {
        int firstSpecial = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' || (plusAsSpace && c == '+')) {
                firstSpecial = i;
                break;
            }
        }
        if (firstSpecial < 0) {
            return s;
        }
        byte[] out = new byte[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new HttpParseException(400, "truncated percent-escape in request target", REF);
                }
                int hi = hex(s.charAt(i + 1));
                int lo = hex(s.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw new HttpParseException(400, "invalid percent-escape in request target", REF);
                }
                out[n++] = (byte) (hi << 4 | lo);
                i += 2;
            } else if (c == '+' && plusAsSpace) {
                out[n++] = ' ';
            } else {
                out[n++] = (byte) c;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out, 0, n))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new HttpParseException(400, "percent-escapes in request target are not valid UTF-8", REF);
        }
    }

    /**
     * Parses {@code a=1&b=2&a=3} into an ordered multimap. A component without {@code =} maps
     * to the empty string; empty components ({@code a=1&&b=2}) are skipped.
     */
    public static Map<String, List<String>> parseQuery(String rawQuery) throws HttpParseException {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        int start = 0;
        while (start <= rawQuery.length()) {
            int amp = rawQuery.indexOf('&', start);
            int end = amp < 0 ? rawQuery.length() : amp;
            if (end > start) {
                int eq = rawQuery.indexOf('=', start);
                String name;
                String value;
                if (eq < 0 || eq > end) {
                    name = decode(rawQuery.substring(start, end), true);
                    value = "";
                } else {
                    name = decode(rawQuery.substring(start, eq), true);
                    value = decode(rawQuery.substring(eq + 1, end), true);
                }
                result.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
            }
            if (amp < 0) {
                break;
            }
            start = amp + 1;
        }
        result.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(result);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
