package me.shenfeng.http.client;

import java.util.Map;

import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.HttpStatus;
import me.shenfeng.http.HttpVersion;

public class BinaryRespListener implements IRespListener {
    protected DynamicBytes body;
    protected Map<String, String> headers;
    protected HttpStatus status;
    protected IBinaryHandler handler;

    public BinaryRespListener(IBinaryHandler h) {
        body = new DynamicBytes(1024 * 8);
        this.handler = h;
    }

    public int onBodyReceived(byte[] buf, int length) {
        body.append(buf, 0, length);
        return CONTINUE;
    }

    public void onCompleted() {
        // TODO status maybe null
        // http://localhost:9090/fav?h=moc.elcaro.sgolb
        handler.onSuccess(status.getCode(), headers, body);
    }

    public int onHeadersReceived(Map<String, String> headers) {
        this.headers = headers;
        return CONTINUE;
    }

    public int onInitialLineReceived(HttpVersion version, HttpStatus status) {
        this.status = status;
        return CONTINUE;
    }

    public void onThrowable(Throwable t) {
        handler.onThrowable(t);
    }
}
