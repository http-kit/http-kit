import java.io.UnsupportedEncodingException;

public class TestDecoder {

    public static void main(String[] args) throws UnsupportedEncodingException {
        short t = (short) 0xfffe;

        int i = (short) 0xfffe & 0xffff;
        System.out.println(t + "\t" + i);

        byte[] bytes = "去年".getBytes("ISO-8859-1");
        System.out.println(bytes.length);
        for (byte b : bytes) {
            System.out.println(b + "\t" + (char)b);
        }
    }

}
