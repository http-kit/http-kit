package me.shenfeng.http;

import org.junit.Test;

public class DynaimicBytesTest {

    @Test
    public void testEnsureCapacity() {
        DynamicBytes b = new DynamicBytes(0);
        for(int i = 0; i < 1000; ++i) {
            b.append((byte)1);
        }
        
        System.out.println(b.get().length);
        System.out.println(b);
    }
}
