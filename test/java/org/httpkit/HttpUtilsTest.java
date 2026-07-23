package org.httpkit;

import org.junit.Test;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;

public class HttpUtilsTest {

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
