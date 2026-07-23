package org.httpkit;

import org.junit.Test;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;

import static org.httpkit.HttpUtils.HttpEncode;

public class HttpUtilsTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInjectedResponseHeaders() {
        HeaderMap headers = new HeaderMap();
        headers.put("X-Test", "safe\r\nInjected: yes");
        HttpEncode(200, headers, "body");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidResponseHeaderNames() {
        HeaderMap headers = new HeaderMap();
        headers.put("Bad Header", "value");
        HttpEncode(200, headers, "body");
    }

    @Test(expected = EOFException.class)
    public void reportsFileTruncationInsteadOfLooping() throws Exception {
        File file = File.createTempFile("http-kit-short-file", ".txt");
        try {
            FileOutputStream output = new FileOutputStream(file);
            try {
                output.write(1);
            } finally {
                output.close();
            }
            HttpUtils.readContent(file, 2);
        } finally {
            file.delete();
        }
    }
}
