package me.shenfeng.http.codec;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map.Entry;

public class HttpUtils {

    public static final byte CR = 13;
    public static final byte LF = 10;

    // Colon ':'
    static final byte COLON = 58;

    // space ' '
    static final byte SP = 32;

    public static final Charset ASCII = Charset.forName("US-ASCII");

    public static ByteBuffer encodeResponseHeader(IHttpResponse resp) {
        DynamicBytes bytes = new DynamicBytes(196);
        bytes.write(resp.getStatus().getResponseIntialLineBytes());
        Iterator<Entry<String, String>> ite = resp.getHeaders().entrySet().iterator();
        while (ite.hasNext()) {
            Entry<String, String> e = ite.next();
            bytes.write(e.getKey());
            bytes.write(COLON);
            bytes.write(SP);
            bytes.write(e.getValue());
            bytes.write(CR);
            bytes.write(LF);
        }

        bytes.write(CR);
        bytes.write(LF);
        return ByteBuffer.wrap(bytes.get(), 0, bytes.getCount());
    }
}
