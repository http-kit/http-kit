package org.httpkit;

import java.io.IOException;
import java.io.PipedOutputStream;

public class CallbackPipedOutputStream extends PipedOutputStream {

    private CallbackPipedInputStream handler;

    public void connectHandler(CallbackPipedInputStream h) {
        handler = h;
    }

    void handle() {
        if (handler != null) {
            handler.callback();
        }
    }

    public void write(int b)  throws IOException {
        super.write(b);
        handle();
    }

    public void write(byte b[], int off, int len) throws IOException {
        super.write(b, off, len);
        handle();
    }

    public void close()  throws IOException {
        super.close();
        if (handler != null) {
            handler.end();
        }
    }
}
