package io.github.mgeladzerezo.sockethttp.metrics;

/**
 * A log-linear histogram of non-negative {@code long} values, in the style of HdrHistogram.
 *
 * <p>Each power of two is divided into {@value #SUB_BUCKETS} equal sub-buckets, so a value is
 * recorded with a relative error of at most 1/32 (about 3 %) across the whole range of a
 * {@code long}, in a fixed {@value #BUCKETS} counters. Values below 64 are exact. Recording
 * is a couple of shifts and an array increment, with no allocation.
 *
 * <p>This class is not thread-safe; it is the single-writer form used by the load generator
 * (one histogram per connection, merged at the end) and as the snapshot type of
 * {@link ConcurrentHistogram}.
 */
public final class Histogram {

    static final int SUB_BITS = 5;
    static final int SUB_BUCKETS = 1 << SUB_BITS;
    /** Highest index is for bit 62 set: shift 57, mantissa 63. */
    static final int BUCKETS = 57 * SUB_BUCKETS + 2 * SUB_BUCKETS;

    private final long[] counts = new long[BUCKETS];
    private long total;
    private long min = Long.MAX_VALUE;
    private long max;
    private double sum;

    /** Index of the bucket that holds {@code value}; negative values are treated as zero. */
    static int bucketOf(long value) {
        if (value < SUB_BUCKETS) {
            return value < 0 ? 0 : (int) value;
        }
        int shift = 63 - Long.numberOfLeadingZeros(value) - SUB_BITS;
        return shift * SUB_BUCKETS + (int) (value >>> shift);
    }

    /** Largest value that falls into {@code bucket}. */
    static long upperBound(int bucket) {
        if (bucket < 2 * SUB_BUCKETS) {
            return bucket;
        }
        int shift = bucket / SUB_BUCKETS - 1;
        long mantissa = bucket - (long) shift * SUB_BUCKETS;
        return ((mantissa + 1) << shift) - 1;
    }

    public void record(long value) {
        long v = Math.max(0, value);
        counts[bucketOf(v)]++;
        total++;
        sum += v;
        if (v < min) {
            min = v;
        }
        if (v > max) {
            max = v;
        }
    }

    /** Adds every recorded value of {@code other} to this histogram. */
    public void add(Histogram other) {
        for (int i = 0; i < BUCKETS; i++) {
            counts[i] += other.counts[i];
        }
        total += other.total;
        sum += other.sum;
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
    }

    /** Used by {@link ConcurrentHistogram} to build a snapshot. */
    void addBucket(int bucket, long n) {
        if (n > 0) {
            counts[bucket] += n;
            total += n;
        }
    }

    void setExtremes(long min, long max, double sum) {
        this.min = min;
        this.max = max;
        this.sum = sum;
    }

    public long count() {
        return total;
    }

    /** Smallest recorded value, or 0 when empty. */
    public long min() {
        return total == 0 ? 0 : min;
    }

    /** Largest recorded value (exact, not rounded to a bucket). */
    public long max() {
        return max;
    }

    public double mean() {
        return total == 0 ? 0 : sum / total;
    }

    /**
     * The value at or below which {@code percentile} percent of the recorded values fall,
     * reported as the upper edge of its bucket and never above {@link #max()}.
     *
     * @param percentile between 0 and 100
     */
    public long percentile(double percentile) {
        if (total == 0) {
            return 0;
        }
        long rank = Math.max(1, (long) Math.ceil(percentile / 100.0 * total));
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return Math.min(upperBound(i), max);
            }
        }
        return max;
    }
}
