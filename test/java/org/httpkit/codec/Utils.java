package org.httpkit.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    public static List<byte[]> readAll(String... resources) throws IOException {
        List<byte[]> bytes = new ArrayList<byte[]>();
       for (String resource : resources)
           bytes.add(readAll(resource));
       return bytes;
    }
}
