package me.shenfeng.http;

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
}
