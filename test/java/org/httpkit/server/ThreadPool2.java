package org.httpkit.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadPool2 {
    private final Worker[] workers;
    private volatile boolean closed = false;
    private final CountDownLatch latch;
    private final int size;

    class BlockingQueue {
        final Runnable[] items;
        /** items index for next take, poll, peek or remove */
        int takeIndex;

        /** items index for next put, offer, or add */
        int putIndex;

        /** Number of elements in the queue */
        int count;

        /** Main lock guarding all access */
        final ReentrantLock lock;
        /** Condition for waiting takes */
        private final Condition notEmpty;

        public BlockingQueue(int capacity) {
            this.items = new Runnable[capacity];
            lock = new ReentrantLock();
            notEmpty = lock.newCondition();
        }

        public boolean offer(Runnable r) {
            lock.lock();
            try {
                if (count == items.length) {
                    return false;
                } else {
                    items[putIndex] = r;
                    putIndex = (++putIndex == items.length) ? 0 : putIndex;
                    ++count;
                    if (count == 1) {
                        notEmpty.signal();
                    }
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }

        public Runnable take() {
            lock.lock();
            try {
                for (;;) {
                    if (count == 0) {
                        if (closed) {
                            return null;
                        } else {
                            try {
                                while (count == 0) {
                                    notEmpty.await();
                                }
                            } catch (InterruptedException ignore) {
                            }
                        }
                    } else {
                        break;
                    }
                }

                final Runnable[] items = this.items;
                Runnable r = items[takeIndex];
                items[takeIndex] = null;
                takeIndex = (++takeIndex == items.length) ? 0 : takeIndex;
                --count;
                return r;
            } finally {
                lock.unlock();
            }
        }
    }

    class Worker implements Runnable {
        final BlockingQueue queue;
        final Thread t;

        public Worker(int size) {
            this.queue = new BlockingQueue(size);
            t = new Thread(this);
        }

        public void run() {
            Runnable r;
            while ((r = queue.take()) != null) {
                r.run();
            }
            latch.countDown();
        }
    }

    public ThreadPool2(int size, int total) {
        this.size = size;
        this.workers = new Worker[size];
        this.latch = new CountDownLatch(size);
        for (int i = 0; i < size; i++) {
            workers[i] = new Worker(total / size);
            workers[i].t.start();
        }
    }

    // private volatile int c = 0;

    public void submit(Runnable task) {
        if (!workers[task.hashCode() % size].queue.offer(task)) {
            // System.out.println("oveload");
        }
    }

    // public void submit()

    public void coseAndwait() throws InterruptedException {
        closed = true;
        for (Worker w : workers) {
            w.t.interrupt();
        }
        latch.await();
    }
}