package org.httpkit.server;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

class Task implements Runnable {
    public Task(AtomicInteger c) {
        this.counter = c;
    }

    final AtomicInteger counter;

    public void run() {
        counter.incrementAndGet();
    }
}

public class ThreadPoolTest {
    final int total = 10000 * 400;
    final int thread = 100;
    Object pool;
    AtomicInteger counter;
    long start = System.currentTimeMillis();

    @Before
    public void setup() {
        counter = new AtomicInteger(0);
        start = System.currentTimeMillis();
    }

    @After
    public void tearDown() {
        long time = System.currentTimeMillis() - start;
        System.out
                .println(pool.getClass().getName() + "\t" + counter.get() + " " + time + "ms");
    }

    @Test
    public void testHomeMakeQueue() throws InterruptedException {
        ThreadPool3 p = new ThreadPool3(thread, total);
        pool = p;
        int c = 0;
        while (c++ < total) {
            p.submit(new Task(counter));
        }
        p.coseAndwait();
    }

    @Test
    public void testPool2() throws InterruptedException {
        ThreadPool2 p = new ThreadPool2(thread, total);
        pool = p;
        int c = 0;

        while (c++ < total) {
            p.submit(new Task(counter));
        }

        p.coseAndwait();
    }

    @Test
    public void testPool22() throws InterruptedException {
        ThreadPool2 p = new ThreadPool2(thread, total);
        pool = p;
        int c = 0;

        while (c++ < total) {
            p.submit(new Task(counter));
        }

        p.coseAndwait();
    }

    @Test
    public void testHomeMade() throws InterruptedException {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(total);
        ThreadPool p = new ThreadPool(thread, queue);
        pool = p;
        int c = 0;

        while (c++ < total) {
            p.submit(new Task(counter));
        }

        p.coseAndwait();

    }

    @Test
    public void testJDKS() throws InterruptedException {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(total);
        ExecutorService exes = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS,
                queue);
        pool = exes;
        int c = 0;

        while (c++ < total) {
            exes.submit(new Task(counter));
        }
        exes.shutdown();
        exes.awaitTermination(1000, TimeUnit.SECONDS);

    }

    @Test
    public void testHomeMade2() throws InterruptedException {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(total);
        ThreadPool p = new ThreadPool(thread, queue);
        int c = 0;
        pool = p;
        while (c++ < total) {
            p.submit(new Task(counter));
        }

        p.coseAndwait();
    }

    @Test
    public void testJDKS2() throws InterruptedException {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(total);
        ExecutorService exes = new ThreadPoolExecutor(thread, thread, 0, TimeUnit.MILLISECONDS,
                queue);
        int c = 0;
        pool = exes;
        while (c++ < total) {
            exes.submit(new Task(counter));
        }
        exes.shutdown();
        exes.awaitTermination(1000, TimeUnit.SECONDS);
    }

    @Test
    public void testHomeMakeQueue2() throws InterruptedException {
        ThreadPool3 p = new ThreadPool3(thread, total);
        int c = 0;
        pool = p;
        while (c++ < total) {
            p.submit(new Task(counter));
        }

        p.coseAndwait();
    }
}