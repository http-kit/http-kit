package org.httpkit;

import java.util.concurrent.atomic.AtomicBoolean;

import org.httpkit.client.PriorityQueue;

import clojure.lang.IFn;

public class TimerService implements Runnable {

    private final PriorityQueue<CancelableFutureTask> queue = new PriorityQueue<CancelableFutureTask>();
    private AtomicBoolean started = new AtomicBoolean(false);

    public CancelableFutureTask timeout(int timeout, IFn task) {
        if (started.compareAndSet(false, true)) {
            // start the timer thread, if not started
            Thread t = new Thread(this, "timer-service");
            t.start();
        }

        CancelableFutureTask t = new CancelableFutureTask(timeout, task, queue);
        synchronized (queue) {
            queue.offer(t);
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
        CancelableFutureTask task;
        while (true) {
            synchronized (queue) {
                task = queue.peek();
            }
            if (task == null) {
                synchronized (queue) {
                    try {
                        // wait 2 minute before kill self
                        queue.wait(1000 * 60);
                        if (emptyQueueWaited == true) {
                            started.compareAndSet(true, false);
                            break; // die, will start
                        } else {
                            emptyQueueWaited = true; // queue is empty
                        }
                    } catch (InterruptedException ignore) {
                    }
                }
            } else {
                emptyQueueWaited = false;
                long due = task.timeoutTs - System.currentTimeMillis();
                // schedule to run in 1000ms, maybe run in 1000ms, 1001ms, ...
                if (due <= 0) {
                    try {
                        task.runTask();
                    } catch (Exception e) {
                        HttpUtils.printError("In timer: " + task, e);
                    }
                    queue.poll(); // remove
                } else {
                    synchronized (queue) {
                        try {
                            queue.wait(due); // others may notify you
                        } catch (InterruptedException ignore) {
                            // maybe more urgent job come in
                        }
                    }
                }
            }
        }
    }
}
