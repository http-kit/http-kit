package org.httpkit.server;

import clojure.lang.IFn;
import java.nio.ByteBuffer;
import java.util.LinkedList;

public abstract class ServerAtta {
    final LinkedList<ByteBuffer> toWrites = new LinkedList<ByteBuffer>();

    /**
     * One entry per logical write that left bytes in {@link #toWrites}. Entries
     * without callbacks are retained too: a later asynchronous write cannot
     * complete until all earlier synchronous bytes have left the socket.
     *
     * <p>Guarded by {@code synchronized (this)}, like {@code toWrites}.
     */
    final LinkedList<WriteRequest> writeRequests = new LinkedList<WriteRequest>();

    static final class WriteRequest {
        long remaining;
        final IFn succeed;
        final IFn fail;

        WriteRequest(long remaining, IFn succeed, IFn fail) {
            this.remaining = remaining;
            this.succeed = succeed;
            this.fail = fail;
        }
    }

    /**
     * Bytes currently queued in {@link #toWrites}, i.e. accepted from the
     * application but not yet written to the socket.
     *
     * <p>This exists because {@code toWrites} is unbounded and, until it was
     * counted, invisible: a peer that stops reading makes the queue grow
     * without limit, {@code send!} keeps returning {@code true}, and the
     * channel keeps reporting open. One such connection was measured taking
     * 1.36 GB of heap in 8 seconds.
     *
     * <p>Maintained in O(1): incremented by what is enqueued, decremented by
     * the return value of {@code SocketChannel.write}, which is exactly the
     * number of bytes that left the queue.
     *
     * <p>Guarded by {@code synchronized (atta)}, like {@code toWrites} itself.
     */
    long queuedBytes = 0;

    /**
     * Set once when {@code maxQueuedBytes} is first exceeded, so the overflow
     * is logged and the close enqueued exactly once rather than on every
     * subsequent send until the IO thread closes the socket.
     *
     * <p>Guarded by {@code synchronized (atta)}.
     */
    boolean overflowClosed = false;

    /**
     * Whether the peer is behind: set when {@code queuedBytes} rises above the
     * HIGH mark, cleared only when it falls below the LOW mark.
     *
     * <p>Two marks rather than one, for hysteresis. With a single threshold a
     * connection parked at the boundary flips state on every write, so an
     * application that pauses on the signal resumes immediately and pauses
     * again. Netty's {@code WriteBufferWaterMark} exists for exactly this and
     * says so; Envoy independently chose low = high/2 "to avoid thrashing".
     *
     * <p>Guarded by {@code synchronized (atta)}.
     */
    boolean unwritable = false;

    /**
     * Snapshot of {@link #queuedBytes}, for metrics and flow control. Stale on
     * return by construction.
     */
    public long queuedBytes() {
        synchronized (this) {
            return queuedBytes;
        }
    }

    /** Is the peer keeping up? See {@link #unwritable}. */
    public boolean writable() {
        synchronized (this) {
            return !unwritable;
        }
    }

    /**
     * Discard the queue's accounting. Called when the connection is closed and
     * {@code toWrites} is abandoned: a gauge that never returns to zero is a
     * monitoring bug, and Netty likewise decrements on remove/failFlushed.
     */
    LinkedList<IFn> discardQueued() {
        synchronized (this) {
            LinkedList<IFn> failures = null;
            for (WriteRequest request : writeRequests) {
                if (request.fail != null) {
                    if (failures == null) failures = new LinkedList<IFn>();
                    failures.add(request.fail);
                }
            }
            toWrites.clear();
            writeRequests.clear();
            queuedBytes = 0;
            unwritable = false;
            return failures;
        }
    }

    /** Record one logical write's still-queued bytes. Called under this lock. */
    void queuedWrite(long bytes, IFn succeed, IFn fail) {
        if (bytes <= 0) return;

        // Synchronous writes have no observable boundary. Coalesce adjacent
        // ones so a fast producer does not replace one unbounded queue with
        // another merely to account for later asynchronous completion.
        WriteRequest previous = writeRequests.peekLast();
        if (succeed == null && fail == null && previous != null
                && previous.succeed == null && previous.fail == null) {
            previous.remaining += bytes;
        } else {
            writeRequests.add(new WriteRequest(bytes, succeed, fail));
        }
    }

    /**
     * Apply bytes written to logical writes in FIFO order and return success
     * callbacks whose complete frame has now reached the socket.
     * Called under this lock.
     */
    LinkedList<IFn> completedWrites(long bytes) {
        LinkedList<IFn> successes = null;
        long remaining = bytes;
        while (remaining > 0 && !writeRequests.isEmpty()) {
            WriteRequest request = writeRequests.getFirst();
            long consumed = Math.min(remaining, request.remaining);
            request.remaining -= consumed;
            remaining -= consumed;
            if (request.remaining == 0) {
                writeRequests.removeFirst();
                if (request.succeed != null) {
                    if (successes == null) successes = new LinkedList<IFn>();
                    successes.add(request.succeed);
                }
            }
        }
        if (remaining != 0) {
            throw new IllegalStateException("write completion accounting underflow: " + remaining);
        }
        return successes;
    }

    ByteBuffer pendingInput;
    boolean requestInProgress;

    protected AsyncChannel channel;

    // close the connection after write?

    /* HTTP: greedy, if client support it( HTTP/1.1 without keep-alive: close),
             http-kit only close the socket after client first close it
       WebSocket: When a close frame is received, the socket get closed after the response close frame is sent
     */
    protected boolean keepalive = true;

    public boolean isKeepAlive() {
        return keepalive || chunkedResponseInprogress;
    }

    // Needed in the following situation, thanks @rufoa
    // https://github.com/http-kit/http-kit/pull/84
    // 1. client sent Connection: Close => server
    // 2. server try to streaming the response
    // 3. server close the connection after first write, which makes a bad streaming

    // only apply to HTTP
    protected boolean chunkedResponseInprogress = false;

    public void chunkedResponseInprogress(boolean b) {
        chunkedResponseInprogress = b;
    }
}
