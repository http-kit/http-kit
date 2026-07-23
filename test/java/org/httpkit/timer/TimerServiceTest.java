package org.httpkit.timer;

import clojure.lang.AFn;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class TimerServiceTest {

    @Test
    public void ordersDistantTasksWithoutOverflow() {
        CancelableFutureTask immediate = new CancelableFutureTask(0, new AFn() {}, null);
        while (System.currentTimeMillis() <= immediate.timeoutTs) {
            Thread.yield();
        }
        CancelableFutureTask distant =
                new CancelableFutureTask(Integer.MAX_VALUE, new AFn() {}, null);

        assertTrue(immediate.compareTo(distant) < 0);
        assertTrue(distant.compareTo(immediate) > 0);
    }

    @Test
    public void acceptsTasksAfterIdleShutdown() throws Exception {
        TimerService service = new TimerService(2);

        for (int i = 0; i < 20; i++) {
            final CountDownLatch ran = new CountDownLatch(1);
            service.scheduleTask(0, new AFn() {
                @Override
                public Object invoke() {
                    ran.countDown();
                    return null;
                }
            });
            assertTrue(ran.await(1, TimeUnit.SECONDS));
            Thread.sleep(6);
        }
    }

    @Test
    public void earlierTaskWakesWaitingTimer() throws Exception {
        TimerService service = new TimerService(5);
        CancelableFutureTask late = service.scheduleTask(1000, new AFn() {});
        Thread.sleep(20);

        final CountDownLatch ran = new CountDownLatch(1);
        service.scheduleTask(10, new AFn() {
            @Override
            public Object invoke() {
                ran.countDown();
                return null;
            }
        });

        assertTrue(ran.await(500, TimeUnit.MILLISECONDS));
        late.cancel();
    }

    @Test
    public void taskErrorsDoNotStopLaterTasks() throws Exception {
        TimerService service = new TimerService(1000);
        final CountDownLatch failed = new CountDownLatch(1);
        final CountDownLatch ran = new CountDownLatch(1);

        service.scheduleTask(0, new AFn() {
            @Override
            public Object invoke() {
                failed.countDown();
                throw new AssertionError("expected test error");
            }
        });
        assertTrue(failed.await(1, TimeUnit.SECONDS));

        service.scheduleTask(0, new AFn() {
            @Override
            public Object invoke() {
                ran.countDown();
                return null;
            }
        });
        assertTrue(ran.await(1, TimeUnit.SECONDS));
    }
}
