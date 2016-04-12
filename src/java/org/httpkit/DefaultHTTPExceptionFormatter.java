package org.httpkit;

/**
 * Default implementation for HTTPExceptionFormatter. Returns the
 * exception message as-is.
 */
public class DefaultHTTPExceptionFormatter implements HTTPExceptionFormatter {
    public String errorMessage(int status, HTTPException e) {
        return e.getMessage();
    }
}
