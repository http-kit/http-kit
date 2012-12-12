package me.shenfeng.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;

public class PiplelineTest {

    public static void main(String[] args) throws UnknownHostException, IOException {

        Socket socket = new Socket("127.0.0.1", 9091);

        InputStream is = socket.getInputStream();
        OutputStream os = socket.getOutputStream();

        String req = "GET / HTTP/1.0\r\n\r\n";

        os.write((req + req + req).getBytes());
        os.flush();

        byte buffer[] = new byte[8096000];
        int read = is.read(buffer);

        System.out.println(new String(buffer, 0, read));

    }
}
