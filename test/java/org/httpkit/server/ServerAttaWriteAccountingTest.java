package org.httpkit.server;

import clojure.lang.AFn;
import clojure.lang.IFn;
import org.junit.Test;

import java.util.LinkedList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ServerAttaWriteAccountingTest {

    private static IFn callback() {
        return new AFn() {
            @Override
            public Object invoke() {
                return null;
            }

            @Override
            public Object invoke(Object error) {
                return null;
            }
        };
    }

    @Test
    public void synchronousPrefixAndAsyncCompletionsStayInFifoByteOrder() {
        ServerAtta atta = new ServerAtta() {};
        IFn success1 = callback();
        IFn failure1 = callback();
        IFn success2 = callback();
        IFn failure2 = callback();

        synchronized (atta) {
            // Consecutive synchronous writes have no observable boundary and
            // are coalesced, but their bytes still precede async completion.
            atta.queuedWrite(3, null, null);
            atta.queuedWrite(7, null, null);
            assertEquals(1, atta.writeRequests.size());
            assertEquals(10, atta.writeRequests.getFirst().remaining);

            atta.queuedWrite(5, success1, failure1);
            atta.queuedWrite(7, success2, failure2);

            assertNull(atta.completedWrites(9));
            assertNull(atta.completedWrites(2));

            LinkedList<IFn> first = atta.completedWrites(4);
            assertEquals(1, first.size());
            assertSame(success1, first.getFirst());

            LinkedList<IFn> second = atta.completedWrites(7);
            assertEquals(1, second.size());
            assertSame(success2, second.getFirst());
            assertEquals(0, atta.writeRequests.size());
        }
    }

    @Test
    public void discardReturnsEachRetainedFailureExactlyOnce() {
        ServerAtta atta = new ServerAtta() {};
        IFn failure1 = callback();
        IFn failure2 = callback();

        synchronized (atta) {
            atta.queuedWrite(3, callback(), failure1);
            atta.queuedWrite(4, callback(), failure2);
        }

        LinkedList<IFn> failures = atta.discardQueued();
        assertEquals(2, failures.size());
        assertSame(failure1, failures.get(0));
        assertSame(failure2, failures.get(1));
        assertEquals(0, atta.writeRequests.size());
        assertNull(atta.discardQueued());
    }
}
