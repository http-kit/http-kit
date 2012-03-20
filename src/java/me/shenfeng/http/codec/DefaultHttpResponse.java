package me.shenfeng.http.codec;


public class DefaultHttpResponse extends DefaultHttpMessage implements IHttpResponse {

    private HttpResponseStatus status;

    public DefaultHttpResponse(HttpResponseStatus status, HttpVersion version) {
        this.status = status;
        this.version = version;
    }

    public DefaultHttpResponse(HttpResponseStatus status, HttpVersion version,
            byte[] body) {
        this(status, version);
        this.content = body;
    }

    public static final DefaultHttpResponse BAD_REQUEST = new DefaultHttpResponse(
            HttpResponseStatus.BAD_REQUEST, HttpVersion.HTTP_1_1,
            "400 bad request".getBytes());

    public HttpResponseStatus getStatus() {
        return status;
    }
}
