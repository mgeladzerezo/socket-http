package io.github.mgeladzerezo.sockethttp.server;

/**
 * How connections are mapped onto threads. All three run the same parser, router and response
 * writer; only the code that moves bytes between the socket and those parts differs.
 */
public enum ConcurrencyModel {

    /**
     * One platform thread from a hand-written bounded pool per connection, for the life of
     * the connection. Simple and fast per request, but an idle keep-alive connection pins a
     * whole thread (about 1 MB of stack reserved), so the number of connections that can be
     * served at once is the pool size.
     */
    THREAD_POOL,

    /**
     * One virtual thread per connection. Same blocking code as {@link #THREAD_POOL}, but a
     * connection waiting on the socket costs a few hundred bytes of heap instead of a
     * platform thread, so there is no pool to size and blocking handlers scale with
     * connections. The cost is more scheduler work per I/O operation (park, unpark, remount).
     */
    VIRTUAL_THREADS,

    /**
     * A single selector thread reads from every connection with non-blocking I/O and feeds
     * the incremental parser; complete requests are handed to a worker pool, which runs the
     * handler and writes the response. Idle connections cost only a buffer, and workers are
     * busy only while a request is actually being handled. The cost is two thread hand-offs
     * per request and one thread through which all reads pass.
     */
    NIO_EVENT_LOOP
}
