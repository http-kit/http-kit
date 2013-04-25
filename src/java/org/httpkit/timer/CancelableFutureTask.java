package org.httpkit.timer;

import clojure.lang.IFn;
import org.httpkit.PriorityQueue;

import java.util.concurrent.atomic.AtomicBoolean;

public class CancelableFutureTask implements Comparable<CancelableFutureTask> {

    private final int timeout;
    private final IFn futureTask;
    public final long timeoutTs;

    private final AtomicBoolean done = new AtomicBoolean(false);
    private final PriorityQueue<CancelableFutureTask> queue;

    public CancelableFutureTask(int timeout, IFn task, PriorityQueue<CancelableFutureTask> queue) {
        this.timeoutTs = System.currentTimeMillis() + timeout;
        this.timeout = timeout;
        this.futureTask = task;
        this.queue = queue;
    }

    public String toString() {
        long now = System.currentTimeMillis();
        if (done.get()) {
            return "timeout=" + timeout + "ms, done or canceled";
        } else {
            return "timeout=" + timeout + "ms, due in " + (timeoutTs - now) + "ms";
        }
    }

    public void runTask() {
        if (done.compareAndSet(false, true)) {
            futureTask.invoke();
        }
    }

    public boolean cancel() {
        boolean b = done.compareAndSet(false, true);
        if (b) {// ok, not done
            synchronized (queue) {
                queue.remove(this);
            }
        }
        return b;
    }

    public int compareTo(CancelableFutureTask o) {
        return (int) (timeoutTs - o.timeoutTs);
    }
}