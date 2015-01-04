package org.httpkit;

import clojure.lang.IFn;

import java.io.IOException;

public interface CallbackInputStream {

    public void setCallback(IFn h);

    public void callback();

    public abstract int read() throws IOException;

    public boolean ended();
}
