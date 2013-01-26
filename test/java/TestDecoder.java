public class TestDecoder {

    public static void main(String[] args) {
        short t = (short) 0xfffe;

        int i = (short) 0xfffe & 0xffff;
        System.out.println(t + "\t" + i);
    }

}
