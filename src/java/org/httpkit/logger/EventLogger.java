package org.httpkit.logger;

public interface EventLogger<T> {

    public void log(T event);

    public static final EventLogger<String> NOP = new EventLogger<String>() {
        @Override
        public void log(String event) {
            // do nothing
        }
    };

}
