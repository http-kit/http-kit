package org.httpkit.timer;

import clojure.lang.AFn;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class TimerServiceTest {

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
}
