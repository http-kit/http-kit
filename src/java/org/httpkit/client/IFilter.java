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

        public boolean accept(Map<String, String> headers) {
            return true;
        }

        public String toString() {
            return "Response Filter: ACCEPT all response";
        }
    };

    public boolean accept(Map<String, String> headers);

    public boolean accept(DynamicBytes partialBody);
}
