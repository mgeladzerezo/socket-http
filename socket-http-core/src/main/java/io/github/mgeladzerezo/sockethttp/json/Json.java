package io.github.mgeladzerezo.sockethttp.json;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small JSON writer and reader (RFC 8259), here because the core module takes no
 * dependencies and a routing API without {@code ctx.json(...)} is not pleasant to use.
 *
 * <p>Writing handles {@code null}, strings, numbers, booleans, enums, {@link Map}s,
 * {@link Iterable}s, arrays, {@link Optional}s and records (by component, recursively).
 * Reading produces {@code LinkedHashMap}, {@code ArrayList}, {@code String}, {@code Long} or
 * {@code Double}, {@code Boolean} and {@code null}. It is not a data-binding library: there
 * is no mapping from JSON back onto records.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    /** Serialises a value. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(64);
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("value is nested deeper than " + MAX_DEPTH + " levels (cycle?)");
        }
        switch (value) {
            case null -> sb.append("null");
            case CharSequence s -> writeString(sb, s);
            case Boolean b -> sb.append(b.booleanValue());
            case Double d -> writeFloating(sb, d);
            case Float f -> writeFloating(sb, f.doubleValue());
            case Number n -> sb.append(n);
            case Enum<?> e -> writeString(sb, e.name());
            case Character c -> writeString(sb, String.valueOf(c));
            case Optional<?> o -> write(sb, o.orElse(null), depth);
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    write(sb, entry.getValue(), depth + 1);
                }
                sb.append('}');
            }
            case Iterable<?> items -> {
                sb.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, item, depth + 1);
                }
                sb.append(']');
            }
            case Record record -> writeRecord(sb, record, depth);
            default -> {
                if (value.getClass().isArray()) {
                    sb.append('[');
                    int length = Array.getLength(value);
                    for (int i = 0; i < length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        write(sb, Array.get(value, i), depth + 1);
                    }
                    sb.append(']');
                } else {
                    writeString(sb, value.toString());
                }
            }
        }
    }

    private static void writeFloating(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            // JSON has no representation for these; null is what JavaScript's JSON.stringify emits.
            sb.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeRecord(StringBuilder sb, Record record, int depth) {
        sb.append('{');
        boolean first = true;
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, component.getName());
            sb.append(':');
            try {
                component.getAccessor().setAccessible(true);
                write(sb, component.getAccessor().invoke(record), depth + 1);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalArgumentException("cannot read record component " + component.getName(), e);
            }
        }
        sb.append('}');
    }

    private static void writeString(StringBuilder sb, CharSequence s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // U+2028/2029 are legal JSON but break when the text is embedded in a
                    // script; '<' is escaped so a JSON body can never close a script element.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == '<') {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Parses a JSON text.
     *
     * @throws JsonException if the text is not valid JSON
     */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.skipWhitespace();
        Object value = reader.readValue(0);
        reader.skipWhitespace();
        if (reader.pos != text.length()) {
            throw reader.error("unexpected content after the JSON value");
        }
        return value;
    }

    /** Thrown by {@link #parse} for malformed input. */
    public static final class JsonException extends RuntimeException {
        JsonException(String message) {
            super(message);
        }
    }

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        JsonException error(String message) {
            return new JsonException(message + " at offset " + pos);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    break;
                }
                pos++;
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw error("invalid literal");
            }
            pos += literal.length();
            return value;
        }

        private Map<String, Object> readObject(int depth) {
            Map<String, Object> result = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("expected ':'");
                }
                pos++;
                skipWhitespace();
                result.put(key, readValue(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray(int depth) {
            List<Object> result = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(readValue(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or ']'");
                }
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private String readString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("truncated unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("invalid unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("invalid escape");
                }
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private Number readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            int intStart = pos;
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == intStart || (text.charAt(intStart) == '0' && pos - intStart > 1)) {
                throw error("invalid number");
            }
            boolean floating = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                floating = true;
                pos++;
                int fractionStart = pos;
                while (pos < text.length() && isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == fractionStart) {
                    throw error("invalid number");
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                int exponentStart = pos;
                while (pos < text.length() && isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == exponentStart) {
                    throw error("invalid number");
                }
            }
            String number = text.substring(start, pos);
            if (!floating && number.length() <= 18) {
                return Long.parseLong(number);
            }
            return Double.parseDouble(number);
        }
    }
}
