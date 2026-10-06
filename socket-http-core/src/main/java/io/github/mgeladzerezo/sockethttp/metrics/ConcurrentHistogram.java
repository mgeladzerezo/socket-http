package io.github.mgeladzerezo.sockethttp.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * A {@link Histogram} that many threads can record into at once.
 *
 * <p>Counters are striped: each thread picks one of {@value #STRIPES} counter arrays by its
 * thread id, so two request threads usually increment different cache lines even when their
 * latencies land in the same bucket. A snapshot sums the stripes. It is not an atomic cut
 * (a value recorded during the scan may or may not be included), which is fine for metrics.
 */
public final class ConcurrentHistogram {

    private static final int STRIPES = 8;

    private final AtomicLongArray[] stripes = new AtomicLongArray[STRIPES];
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong();
    private final DoubleAdder sum = new DoubleAdder();

    public ConcurrentHistogram() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new AtomicLongArray(Histogram.BUCKETS);
        }
    }

    public void record(long value) {
        long v = Math.max(0, value);
        stripes[(int) (Thread.currentThread().threadId() & (STRIPES - 1))].incrementAndGet(Histogram.bucketOf(v));
        sum.add(v);
        if (v > max.get()) {
            max.accumulateAndGet(v, Math::max);
        }
        if (v < min.get()) {
            min.accumulateAndGet(v, Math::min);
        }
    }

    /** A plain histogram holding everything recorded so far. */
    public Histogram snapshot() {
        Histogram result = new Histogram();
        for (AtomicLongArray stripe : stripes) {
            for (int i = 0; i < Histogram.BUCKETS; i++) {
                result.addBucket(i, stripe.get(i));
            }
        }
        result.setExtremes(min.get(), max.get(), sum.sum());
        return result;
    }
}
