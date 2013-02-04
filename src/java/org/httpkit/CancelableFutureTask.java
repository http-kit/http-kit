package org.httpkit;

import java.util.concurrent.atomic.AtomicBoolean;

import org.httpkit.client.PriorityQueue;

import clojure.lang.IFn;

public class CancelableFutureTask implements Comparable<CancelableFutureTask> {

    public final int timeout;
    public final IFn futureTask;
    public final long timeoutTs;

    private AtomicBoolean runned = new AtomicBoolean(false);
    private final PriorityQueue<CancelableFutureTask> queue;

    public CancelableFutureTask(int timeout, IFn task, PriorityQueue<CancelableFutureTask> queue) {
        this.timeoutTs = System.currentTimeMillis() + timeout;
        this.timeout = timeout;
        this.futureTask = task;
        this.queue = queue;
    }

    public String toString() {
        long now = System.currentTimeMillis();
        if (runned.get() == true) {
            return "timeout=" + timeout + "ms, runned or canceled";
        } else {
            return "timeout=" + timeout + "ms, due in " + (timeoutTs - now) + "ms";
        }
    }

    public void runTask() {
        if (runned.compareAndSet(false, true)) {
            futureTask.invoke();
        }
    }

    public boolean cancel() {
        boolean b = runned.compareAndSet(false, true);
        if (b) {// ok, not runned
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