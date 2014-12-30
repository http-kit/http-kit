package org.httpkit;

import java.io.IOException;
import java.io.PipedOutputStream;

public class TriggeredPipedOutputStream extends PipedOutputStream {

    private TriggeredPipedInputStream handler;

    public void connectHandler(TriggeredPipedInputStream h) {
        handler = h;
    }

    void handle() {
        if (handler != null) {
            handler.handle();
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
