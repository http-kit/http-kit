package org.httpkit.client;

import org.httpkit.DynamicBytes;

import java.util.Map;

/**
 * allow to abort the connection. for example, a crawler may abort the
 * connection if not text
 *
 * @author feng
 */
public interface IFilter {
    public final static IFilter ACCEPT_ALL = new IFilter() {
        public boolean accept(DynamicBytes partialBody) {
            return true;
        }

        public boolean accept(Map<String, Object> headers) {
            return true;
        }

        public String toString() {
            return "Response Filter: ACCEPT all response";
        }
    };

    // if the response is too large, protect OOM
    // For example, HTML expected, but a big mp4 file is returned
    public static class MaxBodyFilter implements IFilter {
        private final int length;

        public MaxBodyFilter(int maxLength) {
            this.length = maxLength;
        }

        public boolean accept(Map<String, Object> headers) {
            return true;
        }

        public String toString() {
            return "Response Filter: ACCEPT when body's length <= " + length;
        }

        public boolean accept(DynamicBytes partialBody) {
            return partialBody.length() <= length;
        }
    }

    public boolean accept(Map<String, Object> headers);

    public boolean accept(DynamicBytes partialBody);
}
