package org.httpkit;

import clojure.lang.IFn;

import java.io.IOException;
import java.io.PipedInputStream;

public class CallbackPipedInputStream extends PipedInputStream implements CallbackInputStream {

    IFn handler;
    boolean ended = false;

    public CallbackPipedInputStream(CallbackPipedOutputStream s, int pipeSize) throws IOException {
        super(s, pipeSize);
        s.connectHandler(this);
    }

    synchronized public void setCallback(IFn h) {
        handler = h;
    }

    synchronized public void callback() {
        if (handler != null) {
            handler.invoke();
        }
    }

    void end() {
        ended = true;
        callback();
    }

    public boolean ended() {
        return ended;
    }
}
