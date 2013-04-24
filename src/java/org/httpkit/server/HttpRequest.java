package org.httpkit.server;

import static org.httpkit.HttpUtils.CHARSET;
import static org.httpkit.HttpUtils.CONNECTION;
import static org.httpkit.HttpUtils.CONTENT_TYPE;
import static org.httpkit.HttpVersion.HTTP_1_1;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Map;

import org.httpkit.*;

public class HttpRequest {
    public final String queryString;
    public final String uri;
    public final HttpMethod method;
    public final HttpVersion version;

    private byte[] body;

    // package visible
    int serverPort = 80;
    String serverName;
    Map<String, String> headers;
    int contentLength = 0;
    String contentType;
    String charset = "utf8";
    boolean isKeepAlive = false;
    boolean isWebSocket = false;
    
    InetSocketAddress remoteAddr;
    AsyncChannel channel;

    public HttpRequest(HttpMethod method, String url, HttpVersion version) {
        this.method = method;
        this.version = version;
        int idx = url.indexOf('?');
        if (idx > 0) {
            uri = url.substring(0, idx);
            queryString = url.substring(idx + 1);
        } else {
            uri = url;
            queryString = null;
        }
    }

    public InputStream getBody() {
        if (body != null) {
            return new BytesInputStream(body, contentLength);
        }
        return null;
    }

    public String getRemoteAddr() {
        String h = headers.get(HttpUtils.X_FORWARDED_FOR);
        if (null != h) {
            int idx = h.indexOf(',');
            if (idx == -1) {
                return h;
            } else {
                // X-Forwarded-For: client, proxy1, proxy2
                return h.substring(0, idx);
            }
        } else {
            return remoteAddr.getAddress().getHostAddress();
        }
    }

    public void setBody(byte[] body, int count) {
        this.body = body;
        this.contentLength = count;
    }

    public void setHeaders(Map<String, String> headers) {
        String h = headers.get("host");
        if (h != null) {
            int idx = h.lastIndexOf(':');
            if (idx != -1) {
                this.serverName = h.substring(0, idx);
                serverPort = Integer.valueOf(h.substring(idx + 1));
            } else {
                this.serverName = h;
            }
        }

        String ct = headers.get(CONTENT_TYPE);
        if (ct != null) {
            int idx = ct.indexOf(";");
            if (idx != -1) {
                int cidx = ct.indexOf(CHARSET, idx);
                if (cidx != -1) {
                    contentType = ct.substring(0, idx);
                    charset = ct.substring(cidx + CHARSET.length());
                } else {
                    contentType = ct;
                }
            } else {
                contentType = ct;
            }
        }

        String con = headers.get(CONNECTION);
        if (con != null) {
            con = con.toLowerCase();
        }

        isKeepAlive = (version == HTTP_1_1 && !"close".equals(con)) || "keep-alive".equals(con);
        isWebSocket = "websocket".equalsIgnoreCase(headers.get("upgrade"));
        this.headers = headers;
    }
}
