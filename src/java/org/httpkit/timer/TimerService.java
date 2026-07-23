package org.httpkit.timer;

import clojure.lang.IFn;
import org.httpkit.HttpUtils;
import org.httpkit.PriorityQueue;

import java.util.concurrent.atomic.AtomicBoolean;

public class TimerService implements Runnable {

    private final PriorityQueue<CancelableFutureTask> queue = new PriorityQueue<CancelableFutureTask>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final long idleTimeoutMs;

    public TimerService() {
        this(1000 * 120);
    }

    TimerService(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public CancelableFutureTask scheduleTask(int timeout, IFn task) {
        CancelableFutureTask t = new CancelableFutureTask(timeout, task, queue);
        synchronized (queue) {
            queue.offer(t);

            // start the timer thread, if not started
            if (started.compareAndSet(false, true)) {
                // the timer thread will kill itself when no job to schedule for too
                // much time. restart if new job come it
                new Thread(this, "timer-service").start();
            }

            queue.notify();
        }
        return t;
    }

    public static final TimerService SERVICE = new TimerService();

    @Override
    public String toString() {
        return "pending=" + queue.size() + ", thread started:" + started.get();
    }

    public void run() {
        // if 2 checks of the queue, find it empty, stop self
        boolean emptyQueueWaited = false;
        while (true) {
            CancelableFutureTask task;
            synchronized (queue) {
                while (true) {
                    task = queue.peek();
                    if (task == null) {
                        // wait 2 minute before kill self
                        if (emptyQueueWaited) {
                            started.set(false);
                            return; // die, will restart
                        }
                        try {
                            queue.wait(idleTimeoutMs);
                            emptyQueueWaited = true; // queue is empty
                        } catch (InterruptedException ignore) {
                        }
                        continue;
                    }

                    emptyQueueWaited = false;
                    long due = task.timeoutTs - System.currentTimeMillis();
                    if (due <= 0) {
                        queue.poll();
                        break;
                    } else {
                        try {
                            queue.wait(due); // others may notify you
                        } catch (InterruptedException ignore) {
                            // maybe more urgent job come in
                        }
                    }
                }
            }

            try {
                task.runTask();
            } catch (Throwable e) {
                try {
                    HttpUtils.printError("In timer: " + task, e);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
