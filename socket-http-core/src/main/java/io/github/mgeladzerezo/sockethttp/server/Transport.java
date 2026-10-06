package io.github.mgeladzerezo.sockethttp.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * The write side of one client connection, as the response writer sees it.
 *
 * <p>This is the seam between the protocol code and the concurrency models. Both methods have
 * blocking semantics: they return only when every byte has been accepted by the socket. A
 * blocking engine implements that with a blocking channel; the event-loop engine implements
 * it on a non-blocking channel by parking the worker until the selector reports the socket
 * writable again. Either way a stalled client is cut off after the configured write timeout.
 */
interface Transport {

    /** Writes all remaining bytes of {@code src}. */
    void write(ByteBuffer src) throws IOException;

    /** Sends {@code count} bytes of {@code file} from {@code position} with {@code transferTo}. */
    void transferFrom(FileChannel file, long position, long count) throws IOException;

    SocketAddress remoteAddress();
}
