package org.httpkit.client;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RespListenerTest {

    @Test
    public void boundsUnreadStreamingData() throws Exception {
        ResponseStream stream = new ResponseStream();
        byte[] chunk = new byte[64 * 1024];
        boolean bounded = false;

        for (int i = 0; i < 1024; i++) {
            if (!stream.add(chunk, chunk.length)) {
                bounded = true;
                break;
            }
        }

        assertTrue(bounded);
        assertTrue(stream.read(chunk) > 0);
        assertTrue(stream.add(chunk, chunk.length));
        stream.close();
        assertFalse(stream.add(chunk, chunk.length));
    }
}
