package org.httpkit;

import clojure.lang.IFn;

public interface TriggeredInputStream {

    public void setHandler(IFn h);

    public void handle();

    public boolean ended();
}
