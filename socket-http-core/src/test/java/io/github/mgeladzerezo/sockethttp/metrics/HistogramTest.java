package io.github.mgeladzerezo.sockethttp.metrics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class HistogramTest {

    @Test
    void smallValuesAreExact() {
        Histogram h = new Histogram();
        for (int i = 0; i < 32; i++) {
            h.record(i);
        }
        assertThat(h.count()).isEqualTo(32);
        assertThat(h.min()).isZero();
        assertThat(h.max()).isEqualTo(31);
        assertThat(h.percentile(50)).isEqualTo(15);
        assertThat(h.percentile(100)).isEqualTo(31);
    }

    @Test
    void percentilesStayWithinTheDocumentedRelativeError() {
        Random random = new Random(42);
        long[] values = new long[200_000];
        Histogram h = new Histogram();
        for (int i = 0; i < values.length; i++) {
            values[i] = 1 + (long) Math.exp(random.nextDouble() * 18);
            h.record(values[i]);
        }
        Arrays.sort(values);
        for (double p : new double[]{50, 90, 99, 99.9}) {
            long exact = values[(int) Math.ceil(p / 100 * values.length) - 1];
            long approx = h.percentile(p);
            assertThat((double) approx).as("p%s", p)
                    .isBetween(exact * (1 - 1.0 / 32), exact * (1 + 1.0 / 32) + 1);
        }
    }

    @Test
    void mergingEqualsRecordingEverythingInOne() {
        Histogram a = new Histogram();
        Histogram b = new Histogram();
        Histogram both = new Histogram();
        for (int i = 1; i <= 10_000; i++) {
            (i % 2 == 0 ? a : b).record(i * 7L);
            both.record(i * 7L);
        }
        a.add(b);
        assertThat(a.count()).isEqualTo(both.count());
        assertThat(a.max()).isEqualTo(both.max());
        assertThat(a.min()).isEqualTo(both.min());
        assertThat(a.percentile(99)).isEqualTo(both.percentile(99));
        assertThat(a.mean()).isEqualTo(both.mean());
    }

    @Test
    void emptyHistogramReportsZeros() {
        Histogram h = new Histogram();
        assertThat(h.count()).isZero();
        assertThat(h.percentile(99)).isZero();
        assertThat(h.min()).isZero();
        assertThat(h.mean()).isZero();
    }

    @Test
    void hugeAndNegativeValuesDoNotBreakIndexing() {
        Histogram h = new Histogram();
        h.record(Long.MAX_VALUE);
        h.record(-5);
        assertThat(h.count()).isEqualTo(2);
        assertThat(h.max()).isEqualTo(Long.MAX_VALUE);
        assertThat(h.percentile(100)).isEqualTo(Long.MAX_VALUE);
    }
}
