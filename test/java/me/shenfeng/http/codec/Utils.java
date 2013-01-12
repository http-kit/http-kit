package me.shenfeng.http.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class Utils {

    public static byte[] readAll(String resource) throws IOException {
        InputStream is = Utils.class.getClassLoader().getResourceAsStream(resource);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b = 0;
        while ((b = is.read()) != -1) {
            bos.write(b);
        }
        is.close();
        return bos.toByteArray();
    }
}
