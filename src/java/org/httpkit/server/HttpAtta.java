package org.httpkit.server;

public class HttpAtta extends ServerAtta {

    public HttpAtta(int maxBody, int maxLine) {
        decoder = new HttpDecoder(maxBody, maxLine);
    }

    public void setResponseComplete(boolean value) {
        responsecomplete = value;
    }

    public final HttpDecoder decoder;
}
