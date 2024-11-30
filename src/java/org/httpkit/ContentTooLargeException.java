package org.httpkit;

import java.lang.RuntimeException;

public class ContentTooLargeException extends RuntimeException {
    public ContentTooLargeException(String message) {
        super(message);
    }
}
