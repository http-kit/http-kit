package org.httpkit.server;

import clojure.lang.AFn;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AsyncChannelCloseTest {

    @Test
    public void lateCloseHandlerRunsOnce() {
        AsyncChannel channel = new AsyncChannel(null, null);
        final AtomicInteger calls = new AtomicInteger();

        channel.onClose(-1);
        channel.setCloseHandler(new AFn() {
            @Override
            public Object invoke(Object status) {
                calls.incrementAndGet();
                return null;
            }
        });
        channel.onClose(-1);

        assertTrue(channel.isClosed());
        assertEquals(1, calls.get());
    }

    @Test
    public void registrationAndCloseRaceRunsHandlerOnce() throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            final AsyncChannel channel = new AsyncChannel(null, null);
            final AtomicInteger calls = new AtomicInteger();
            final CountDownLatch start = new CountDownLatch(1);
            Thread register = new Thread(new Runnable() {
                @Override
                public void run() {
                    await(start);
                    channel.setCloseHandler(new AFn() {
                        @Override
                        public Object invoke(Object status) {
                            calls.incrementAndGet();
                            return null;
                        }
                    });
                }
            });
            Thread close = new Thread(new Runnable() {
                @Override
                public void run() {
                    await(start);
                    channel.onClose(-1);
                }
            });

            register.start();
            close.start();
            start.countDown();
            register.join();
            close.join();

            assertTrue(channel.isClosed());
            assertEquals(1, calls.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
