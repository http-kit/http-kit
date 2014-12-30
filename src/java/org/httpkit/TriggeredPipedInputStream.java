package org.httpkit;

import clojure.lang.IFn;

import java.io.IOException;
import java.io.PipedInputStream;

public class TriggeredPipedInputStream extends PipedInputStream implements TriggeredInputStream {

    IFn handler;
    boolean ended = false;

    public TriggeredPipedInputStream(TriggeredPipedOutputStream s, int pipeSize) throws IOException {
        super(s, pipeSize);
        s.connectHandler(this);
    }

    synchronized public void setHandler(IFn h) {
        handler = h;
    }

    synchronized public void handle() {
        if (handler != null) {
            handler.invoke();
        }
    }

    void end() {
        ended = true;
        handle();
    }

    public boolean ended() {
        return ended;
    }
}
