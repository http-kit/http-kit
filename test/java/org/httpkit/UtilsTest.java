package org.httpkit;

import org.junit.Assert;
import org.junit.Test;

public class UtilsTest {

    @Test
    public void testCamelCase() {
        Assert.assertEquals("Accept", HttpUtils.camelCase("accept"));
        Assert.assertEquals("Accept", HttpUtils.camelCase("Accept"));
        Assert.assertEquals("User-Agent", HttpUtils.camelCase("user-agent"));
        Assert.assertEquals("User-Agent", HttpUtils.camelCase("User-agent"));
        Assert.assertEquals("User-Agent", HttpUtils.camelCase("User-Agent"));
        Assert.assertEquals("User-Agent", HttpUtils.camelCase("user-Agent"));
        Assert.assertEquals("If-Modified-Since", HttpUtils.camelCase("if-modified-since"));
        Assert.assertEquals("If-Modified-Since", HttpUtils.camelCase("if-Modified-Since"));
        Assert.assertEquals("If-Modified-Since", HttpUtils.camelCase("If-modified-Since"));
    }

    private String encodeURI(String str) {
        return HttpUtils.encodeURI(str);
    }

    @Test
    public void testEncodeURL() {
        Assert.assertEquals("%E6%B2%88%E9%94%8B0", encodeURI("沈锋0"));

        String all = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";
        String expected = "%20!%22#$%25&'()*+,-./0123456789:;%3C=%3E?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[%5C]%5E_%60abcdefghijklmnopqrstuvwxyz%7B%7C%7D~";

        Assert.assertEquals(expected, encodeURI(all));
    }
}
