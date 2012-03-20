package me.shenfeng.http.codec;

public class HttpReqeustDecoder extends HttpMessageDecoder {

    protected IHttpMessage createMessage(String s[]) {
        HttpMethod method = HttpMethod.GET;
        String m = s[0].toUpperCase();
        if (m.equals("GET")) {
            method = HttpMethod.GET;
        } else if (m.equals("POST")) {
            method = HttpMethod.POST;
        } else if (m.equals("PUT")) {
            method = HttpMethod.PUT;
        } else if (m.equals("DELETE")) {
            method = HttpMethod.DELETE;
        }
        HttpVersion version = HttpVersion.HTTP_1_1;
        if ("HTTP/1.0".equals(s[2])) {
            version = HttpVersion.HTTP_1_0;
        }
        return new DefaultHttpRequest(version, method, s[1]);
    }

    protected boolean isDecodingRequest() {
        return true;
    }

    public IHttpRequest getMessage() {
        return (IHttpRequest) message;
    }
}
