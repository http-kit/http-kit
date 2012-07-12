package me.shenfeng.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.client.TextRespListener;

import org.junit.Assert;
import org.junit.Test;

public class GuessCharsetTest {

    public static DynamicBytes getData(String resource) throws IOException {
        InputStream is = GuessCharsetTest.class.getClassLoader()
                .getResourceAsStream(resource);

        DynamicBytes dy = new DynamicBytes(1000);
        byte[] buffer = new byte[8912];
        int read = 0;
        while ((read = is.read(buffer)) != -1) {
            dy.append(buffer, 0, read);
        }
        is.close();

        return dy;

    }

    @Test
    public void testGuess() throws IOException {
        Map<String, String> headers = new TreeMap<String, String>();
        Charset gb2312 = Charset.forName("gb2312");
        Charset c = TextRespListener.detectCharset(headers, getData("xml_gb2312"));
        System.out.println(c);

        Assert.assertTrue(gb2312.equals(c));
        c = TextRespListener.detectCharset(headers, getData("html_gb2312"));
        Assert.assertTrue(gb2312.equals(c));

        c = TextRespListener.detectCharset(headers, getData("beta_shield"));
        System.out.println(c);

    }

}
