package io.github.mgeladzerezo.sockethttp.concurrent;

/** What {@link BoundedThreadPool#execute} does when every worker is busy and the queue is full. */
public enum RejectionPolicy {

    /** Throw {@link java.util.concurrent.RejectedExecutionException}: shed load, fail fast. */
    ABORT,

    /**
     * Run the task on the submitting thread. Slows the producer down by making it do the
     * work, at the price of stalling whatever else that thread was responsible for.
     */
    CALLER_RUNS,

    /**
     * Block the submitter until a slot frees up. This is backpressure: a producer that cannot
     * hand off work stops producing. The server's acceptor uses it, so when the pool is
     * saturated new connections wait in the kernel's listen backlog instead of in the heap.
     */
    BLOCK
}
