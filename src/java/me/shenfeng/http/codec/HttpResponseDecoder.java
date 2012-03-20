package me.shenfeng.http.codec;

public class HttpResponseDecoder extends HttpMessageDecoder {
    protected IHttpMessage createMessage(String s[]) {
        HttpVersion version = HttpVersion.HTTP_1_1;
        if ("HTTP/1.0".equals(s[0])) {
            version = HttpVersion.HTTP_1_1;
        }

        return new DefaultHttpResponse(
                HttpResponseStatus.valueOf(Integer.valueOf(s[1])), version);
    }

    protected boolean isDecodingRequest() {
        return false;
    }

    public IHttpResponse getMessage() {
        return (IHttpResponse) message;
    }
}
