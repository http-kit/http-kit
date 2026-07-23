package org.httpkit.server;

import org.httpkit.ProtocolException;

public class WebSocketException extends ProtocolException {
    private final int closeStatus;

    public WebSocketException(int closeStatus, String message) {
        super(message);
        this.closeStatus = closeStatus;
    }

    public int getCloseStatus() {
        return closeStatus;
    }
}
