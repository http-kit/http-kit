import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

public class EncodingBenchmark {

    static byte[] getBytes(String s) {
        byte[] bytes = new byte[s.length()];
        for (int i = 0; i < bytes.length; i++) {
            char c = s.charAt(i);
            if (c >= 128) {
                bytes[i] = (byte) '?';
            } else {
                bytes[i] = (byte) s.charAt(i);
            }
        }
        return bytes;
    }

    final static Charset ASCII = Charset.forName("ISO-8859-1");

    public static byte[] jdkGetBytes(String s) {
        try {
            return s.getBytes("ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }
    }

    private static void makeSureSame(String s) {
        byte[] home = getBytes(s);
        byte[] jdkGetBytes = jdkGetBytes(s);

        if (home.length != jdkGetBytes.length) {
            System.out.println("error");
        }

        for (int i = 0; i < jdkGetBytes.length; i++) {
            if (home[i] != jdkGetBytes[i]) {
                System.out.println("-----------");
            }
        }
    }

    private static String newString(byte[] bytes) {
        char[] chars = new char[bytes.length];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) bytes[i];
        }
        return new String(chars);
    }

    private static String jdkNewString(byte[] bytes) {
        try {
            return new String(bytes, "ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }
    }

    private static void testEncoding(String s) {
        byte[] bytes = jdkGetBytes(s);
        if (!newString(bytes).equals(jdkNewString(bytes))) {
            System.out.println("error");
        }

        for (int ji = 0; ji < LOOP; ji++) {
            long time = System.currentTimeMillis();
            for (int i = 0; i < TIMES; i++) {
                newString(bytes);
            }
            System.out.println("decoding: home " + (System.currentTimeMillis() - time));
        }

        for (int ji = 0; ji < LOOP; ji++) {
            long time = System.currentTimeMillis();
            for (int i = 0; i < TIMES; i++) {
                jdkNewString(bytes);
            }
            System.out.println("decoding: jdk string " + (System.currentTimeMillis() - time));
        }
        
        for (int ji = 0; ji < LOOP; ji++) {
            long time = System.currentTimeMillis();
            for (int i = 0; i < TIMES; i++) {
                new String(bytes, ASCII);
            }
            System.out.println("decoding: jdk charset " + (System.currentTimeMillis() - time));
        }

    }

    public static void main(String[] args) {
        String s = "[2013-02-24 22:45:05 - BrowserActivity] Installing BrowserActivity.apk[2013-02-24 22:45:01 - BrowserActivity] Performing com.meiweisq.BrowserActivity activity launch[2013-02-24 22:45:18 - BrowserActivity] ActivityManager: Starting: Intent { act=android.intent.action.MAIN cat=[ xxx ] cmp=xxxx }";

        makeSureSame(s);
        testEncoding(s);
        testDecoding(s);
    }

    final static int TIMES = 1000000;
    final static int LOOP = 4;

    private static void testDecoding(String s) {

        for (int ji = 0; ji < LOOP; ji++) {
            long time = System.currentTimeMillis();
            for (int i = 0; i < TIMES; i++) {
                jdkGetBytes(s);
            }
            System.out.println("encoding: jdk getBytes string " + (System.currentTimeMillis() - time));
        }

        for (int ji = 0; ji < LOOP; ji++) {
            long time = System.currentTimeMillis();
            for (int i = 0; i < TIMES; i++) {
                getBytes(s);
            }
            System.out.println("encoding: home getBytes " + (System.currentTimeMillis() - time));
        }
    }
}
