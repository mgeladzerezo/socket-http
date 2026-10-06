package io.github.mgeladzerezo.sockethttp.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * HTTP date handling (RFC 9110 §5.6.7).
 *
 * <p>Dates are always <em>written</em> in the preferred IMF-fixdate form. All three forms the
 * RFC requires recipients to accept are <em>read</em>: IMF-fixdate, the obsolete RFC 850
 * form and C's {@code asctime()} form.
 */
public final class HttpDates {

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter RFC_850 = new DateTimeFormatterBuilder()
            .appendPattern("EEEE, dd-MMM-")
            // Two-digit years: RFC 9110 says a date more than 50 years ahead means the past.
            .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
            .appendPattern(" HH:mm:ss 'GMT'")
            .toFormatter(Locale.US).withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter ASCTIME =
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(ZoneOffset.UTC);

    /** The formatted current second, cached because every response carries a Date header. */
    private record Cached(long epochSecond, String text) {
    }

    private static volatile Cached cached = new Cached(-1, "");

    private HttpDates() {
    }

    public static String format(Instant instant) {
        return IMF_FIXDATE.format(instant);
    }

    public static String format(long epochMillis) {
        return IMF_FIXDATE.format(Instant.ofEpochMilli(epochMillis));
    }

    /** The current time as an HTTP date; formatted at most once per second. */
    public static String now() {
        long second = System.currentTimeMillis() / 1000;
        Cached c = cached;
        if (c.epochSecond != second) {
            c = new Cached(second, IMF_FIXDATE.format(Instant.ofEpochSecond(second)));
            cached = c;
        }
        return c.text;
    }

    /**
     * Parses an HTTP date in any of the three accepted forms.
     *
     * @return the instant, or {@code null} if the text is not a valid HTTP date; callers treat
     *         an unparseable conditional header as absent, as RFC 9110 §13.1.3 requires
     */
    public static Instant parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{IMF_FIXDATE, RFC_850, ASCTIME}) {
            try {
                return Instant.from(formatter.parse(s));
            } catch (DateTimeParseException e) {
                // try the next form
            }
        }
        return null;
    }
}
