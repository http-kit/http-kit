package org.httpkit.server;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

public class ThreadPool {
    private final Worker[] workers;
    private final BlockingQueue<Runnable> queue;
    private volatile boolean closed = false;
    private final CountDownLatch latch;

    class Worker implements Runnable {
        final BlockingQueue<Runnable> queue;
        final Thread t;

        public Worker(BlockingQueue<Runnable> queue) {
            this.queue = queue;
            t = new Thread(this);
        }

        public void run() {
            Runnable r;
            for (;;) {
                try {
                    if (queue.size() == 0 && closed) {
                        synchronized (latch) {
                            latch.notify();
                        }
                        latch.countDown();
                        return;
                    }
                    while ((r = queue.take()) != null) {
                        r.run();
                    }
                } catch (InterruptedException e) { // ignore
                }
            }
        }
    }

    public ThreadPool(int size, BlockingQueue<Runnable> queue) {
        this.workers = new Worker[size];
        this.queue = queue;
        this.latch = new CountDownLatch(size);
        for (int i = 0; i < size; i++) {
            workers[i] = new Worker(queue);
            workers[i].t.start();
        }
    }

    public void submit(Runnable task) {
        queue.offer(task);
    }

    public void coseAndwait() throws InterruptedException {
        closed = true;
        for (;;) {
            for (Worker w : workers) {
                w.t.interrupt();
            }

            synchronized (latch) {
                while (latch.getCount() == 0) {
                    return;
                }
                return;
//                latch.await();
            }
        }
    }
}
