package io.github.mgeladzerezo.sockethttp.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The server's counters. Written from every connection thread, so the hot ones are
 * {@link LongAdder}s and the latency distribution is a striped {@link ConcurrentHistogram}.
 */
public final class ServerMetrics {

    private final long startedAtNanos = System.nanoTime();
    private final String concurrencyModel;

    private final AtomicLong connectionsActive = new AtomicLong();
    private final LongAdder connectionsAccepted = new LongAdder();
    private final LongAdder connectionsRejected = new LongAdder();
    private final LongAdder requestsTotal = new LongAdder();
    private final AtomicLong requestsInFlight = new AtomicLong();
    private final LongAdder requestsRejected = new LongAdder();
    private final LongAdder requestTimeouts = new LongAdder();
    private final LongAdder[] responsesByClass = new LongAdder[5];
    private final LongAdder bytesReceived = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();
    private final ConcurrentHistogram latencyMicros = new ConcurrentHistogram();

    public ServerMetrics(String concurrencyModel) {
        this.concurrencyModel = concurrencyModel;
        for (int i = 0; i < responsesByClass.length; i++) {
            responsesByClass[i] = new LongAdder();
        }
    }

    public void connectionOpened() {
        connectionsAccepted.increment();
        connectionsActive.incrementAndGet();
    }

    public void connectionClosed() {
        connectionsActive.decrementAndGet();
    }

    /** A connection was accepted and immediately turned away with 503. */
    public void connectionRejected() {
        connectionsRejected.increment();
    }

    public void requestStarted() {
        requestsTotal.increment();
        requestsInFlight.incrementAndGet();
    }

    public void requestCompleted(int status, long durationNanos) {
        requestsInFlight.decrementAndGet();
        responseSent(status);
        latencyMicros.record(durationNanos / 1_000);
    }

    /** A request was refused by the parser (or timed out) and answered without routing. */
    public void requestRejected(int status) {
        requestsRejected.increment();
        if (status == 408) {
            requestTimeouts.increment();
        }
        responseSent(status);
    }

    private void responseSent(int status) {
        int index = status / 100 - 1;
        if (index >= 0 && index < responsesByClass.length) {
            responsesByClass[index].increment();
        }
    }

    public void bytesReceived(long n) {
        bytesReceived.add(n);
    }

    public void bytesSent(long n) {
        bytesSent.add(n);
    }

    public long connectionsActive() {
        return connectionsActive.get();
    }

    public long requestsInFlight() {
        return requestsInFlight.get();
    }

    public MetricsSnapshot snapshot() {
        Histogram latency = latencyMicros.snapshot();
        return new MetricsSnapshot(
                concurrencyModel,
                (System.nanoTime() - startedAtNanos) / 1_000_000,
                connectionsActive.get(),
                connectionsAccepted.sum(),
                connectionsRejected.sum(),
                requestsTotal.sum(),
                requestsInFlight.get(),
                responsesByClass[0].sum(),
                responsesByClass[1].sum(),
                responsesByClass[2].sum(),
                responsesByClass[3].sum(),
                responsesByClass[4].sum(),
                requestsRejected.sum(),
                requestTimeouts.sum(),
                bytesReceived.sum(),
                bytesSent.sum(),
                latency.count(),
                latency.mean(),
                latency.percentile(50),
                latency.percentile(90),
                latency.percentile(99),
                latency.percentile(99.9),
                latency.max());
    }
}
