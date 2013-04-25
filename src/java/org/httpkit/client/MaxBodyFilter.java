package org.httpkit.client;

import org.httpkit.DynamicBytes;

import java.util.Map;

// if the response is too large, protect OOM
// For example, HTML expected, but a big mp4 file is returned
public class MaxBodyFilter implements IFilter {
    private final int length;

    public MaxBodyFilter(int maxLength) {
        this.length = maxLength;
    }

    public boolean accept(Map<String, String> headers) {
        return true;
    }

    public String toString() {
        return "Response Filter: ACCEPT when body's length <= " + length;
    }

    public boolean accept(DynamicBytes partialBody) {
        return partialBody.length() <= length;
    }
}
