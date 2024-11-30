package org.httpkit;

import java.lang.RuntimeException;

public class ArrayTooLargeException extends RuntimeException {
    public ArrayTooLargeException(String message) {
        super(message);
    }
}
