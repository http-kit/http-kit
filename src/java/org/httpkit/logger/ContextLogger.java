package org.httpkit.logger;

import org.httpkit.HttpUtils;

public interface ContextLogger<X, Y> {

    public void log(X event, Y context);

    public ContextLogger<String, Throwable> ERROR_PRINTER = new ContextLogger<String, Throwable>() {
        @Override
        public void log(String event, Throwable e) {
            HttpUtils.printError(event, e);
        }
    };

}
