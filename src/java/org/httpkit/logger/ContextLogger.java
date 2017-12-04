package org.httpkit.logger;

import java.util.Map;

import org.httpkit.HttpUtils;

public interface ContextLogger<X, Y> {

    public void log(X event, Y context);
    
    public void log(Object logMDC, Map<String, String> logContext, X event, Y context);

    public ContextLogger<String, Throwable> ERROR_PRINTER = new ContextLogger<String, Throwable>() {
        @Override
        public void log(String event, Throwable e) {
            HttpUtils.printError(event, e);
        }

        @Override
        public void log(Object logMDC, Map<String, String> logContext, String event, Throwable e) {
            HttpUtils.printError(event, e);
        }
    };

}
