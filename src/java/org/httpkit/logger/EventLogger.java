package org.httpkit.logger;

import java.util.Map;

public interface EventLogger<T> {

    public void log(T event);

    public void log(Object mdc, Map<String, String> context, String event);

    public static final EventLogger<String> NOP = new EventLogger<String>() {
        @Override
        public void log(String event) {
            // do nothing
        }

		@Override
		public void log(Object mdc, Map<String, String> context, String event) {
		    // do nothing
		}
    };

}
