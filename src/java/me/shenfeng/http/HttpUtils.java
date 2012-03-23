package me.shenfeng.http;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map.Entry;

import me.shenfeng.http.codec.DynamicBytes;
import me.shenfeng.http.codec.IHttpRequest;
import me.shenfeng.http.codec.IHttpResponse;

public class HttpUtils {

    public static final Charset ASCII = Charset.forName("US-ASCII");
    // Colon ':'
    public static final byte COLON = 58;

    public static final byte CR = 13;

    public static final byte LF = 10;

    public static final int MAX_LINE = 2048;

    // space ' '
    public static final byte SP = 32;

    public static void closeQuiety(SocketChannel c) {
        try {
            c.close();
        } catch (Exception ignore) {
        }
    }

    public static ByteBuffer encodeRequest(IHttpRequest req) {
        return null;
    }

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

    public static int findEndOfString(String sb) {
        int result;
        for (result = sb.length(); result > 0; result--) {
            if (!Character.isWhitespace(sb.charAt(result - 1))) {
                break;
            }
        }
        return result;
    }

    public static int findNonWhitespace(String sb, int offset) {
        int result;
        for (result = offset; result < sb.length(); result++) {
            if (!Character.isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    public static int findWhitespace(String sb, int offset) {
        int result;
        for (result = offset; result < sb.length(); result++) {
            if (Character.isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    public static String[] splitInitialLine(String sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        aStart = findNonWhitespace(sb, 0);
        aEnd = findWhitespace(sb, aStart);

        bStart = findNonWhitespace(sb, aEnd);
        bEnd = findWhitespace(sb, bStart);

        cStart = findNonWhitespace(sb, bEnd);
        cEnd = findEndOfString(sb);

        return new String[] { sb.substring(aStart, aEnd), sb.substring(bStart, bEnd),
                cStart < cEnd ? sb.substring(cStart, cEnd) : "" };
    }

    public static int getChunkSize(String hex) {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }

        return Integer.parseInt(hex, 16);
    }

}
