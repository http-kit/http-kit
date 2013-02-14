package org.httpkit;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class PrefixThreadFactory implements ThreadFactory {
    final AtomicInteger id = new AtomicInteger(0);
    private final String prefix;

    public PrefixThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    public Thread newThread(Runnable r) {
        int i = id.incrementAndGet();
        Thread t = new Thread(r, prefix + i);
        t.setDaemon(true);
        return t;
    }
}
