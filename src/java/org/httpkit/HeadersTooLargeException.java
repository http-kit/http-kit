package org.httpkit;

public class HeadersTooLargeException extends ProtocolException {
    public HeadersTooLargeException(String message) {
        super(message);
    }
}
