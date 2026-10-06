package io.github.mgeladzerezo.sockethttp.staticfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses a {@code Range} header against a representation of known length (RFC 9110 §14.1.2).
 *
 * <p>The three outcomes map to three responses: a header that is not a valid byte-range set
 * is <em>ignored</em> and the whole representation is sent with 200; a valid set of which no
 * range overlaps the content gives 416; otherwise the satisfiable ranges are served with 206.
 */
final class ByteRanges {

    /** More ranges than this in one request is treated as abuse and the header is ignored. */
    private static final int MAX_RANGES = 16;

    /** An inclusive range of byte offsets, already clamped to the representation. */
    record Range(long first, long last) {
        long length() {
            return last - first + 1;
        }
    }

    enum Kind { IGNORED, UNSATISFIABLE, SATISFIABLE }

    record Result(Kind kind, List<Range> ranges) {
        static final Result IGNORED = new Result(Kind.IGNORED, List.of());
        static final Result UNSATISFIABLE = new Result(Kind.UNSATISFIABLE, List.of());
    }

    private ByteRanges() {
    }

    static Result parse(String header, long length) {
        String value = header.trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            // Unknown range units are ignored (§14.2).
            return Result.IGNORED;
        }
        List<Range> ranges = new ArrayList<>(2);
        int specs = 0;
        long requestedBytes = 0;
        for (String part : value.substring(6).split(",")) {
            String spec = part.trim();
            if (spec.isEmpty()) {
                continue;
            }
            if (++specs > MAX_RANGES) {
                return Result.IGNORED;
            }
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return Result.IGNORED;
            }
            String firstText = spec.substring(0, dash).trim();
            String lastText = spec.substring(dash + 1).trim();
            Range range;
            if (firstText.isEmpty()) {
                // "-N": the final N bytes.
                long suffix = parse(lastText);
                if (suffix < 0) {
                    return Result.IGNORED;
                }
                if (suffix == 0 || length == 0) {
                    continue;
                }
                range = new Range(Math.max(0, length - suffix), length - 1);
            } else {
                long first = parse(firstText);
                long last = lastText.isEmpty() ? Long.MAX_VALUE : parse(lastText);
                if (first < 0 || last < 0 || last < first) {
                    return Result.IGNORED;
                }
                if (first >= length) {
                    continue;
                }
                range = new Range(first, Math.min(last, length - 1));
            }
            ranges.add(range);
            requestedBytes += range.length();
        }
        if (specs == 0) {
            return Result.IGNORED;
        }
        if (ranges.isEmpty()) {
            return Result.UNSATISFIABLE;
        }
        if (requestedBytes > length) {
            // Overlapping ranges that add up to more than the file: a response larger than
            // the resource itself is an amplification vector, so send the plain resource.
            return Result.IGNORED;
        }
        return new Result(Kind.SATISFIABLE, List.copyOf(ranges));
    }

    /** Digits only; -1 for anything else, including values too large for a long. */
    private static long parse(String digits) {
        if (digits.isEmpty() || digits.length() > 18) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
