package org.httpkit.client;

import java.util.Map;

import org.httpkit.DynamicBytes;


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
