package io.github.mgeladzerezo.sockethttp.server;

import io.github.mgeladzerezo.sockethttp.parser.RequestParser.Phase;

/**
 * Decides how long a connection may take to deliver the next part of a request. Both the
 * blocking engines and the event loop use this class, so the timeout rules cannot drift apart.
 *
 * <p>Each phase has an <em>absolute</em> deadline that is set when the phase begins and is not
 * extended by further bytes:
 * <ul>
 *   <li>idle (between requests): the keep-alive timeout, after which the connection is closed
 *       silently; a brand-new connection gets the header timeout instead;</li>
 *   <li>head: the header timeout, counted from the first byte of the request;</li>
 *   <li>body: the body timeout, counted from the end of the header section.</li>
 * </ul>
 * A per-read timeout would not stop a slowloris client, which keeps a connection busy for
 * hours by sending one header byte just before each timeout expires. With an absolute
 * deadline the whole header section has to arrive within the limit however it is paced.
 */
final class ReadDeadlines {

    private final long keepAliveNanos;
    private final long headerNanos;
    private final long bodyNanos;

    private Phase phase = Phase.IDLE;
    private long deadlineNanos;

    ReadDeadlines(ServerConfig config) {
        this.keepAliveNanos = config.keepAliveTimeout().toNanos();
        this.headerNanos = config.headerTimeout().toNanos();
        this.bodyNanos = config.bodyTimeout().toNanos();
    }

    /** A connection was accepted and nothing has been received yet. */
    void connectionOpened(long now) {
        phase = Phase.IDLE;
        deadlineNanos = now + headerNanos;
    }

    /** A response was completed and the connection is waiting for its next request. */
    void awaitingNextRequest(long now) {
        phase = Phase.IDLE;
        deadlineNanos = now + keepAliveNanos;
    }

    /** Call after feeding the parser: starts the deadline of a newly entered phase. */
    void advance(Phase parserPhase, long now) {
        if (parserPhase == phase) {
            return;
        }
        if (parserPhase == Phase.HEAD) {
            deadlineNanos = now + headerNanos;
        } else if (parserPhase == Phase.BODY) {
            deadlineNanos = now + bodyNanos;
        }
        phase = parserPhase;
    }

    Phase phase() {
        return phase;
    }

    long remainingNanos(long now) {
        return deadlineNanos - now;
    }

    boolean expired(long now) {
        return deadlineNanos - now <= 0;
    }
}
