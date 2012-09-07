package me.shenfeng.http.server;

import me.shenfeng.http.HttpMethod;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.HttpVersion;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

import static me.shenfeng.http.HttpUtils.*;
import static me.shenfeng.http.HttpVersion.HTTP_1_1;

public class HttpRequest {
    private int serverPort;
    private String serverName;
    private InetSocketAddress remoteAddr;
    private String queryString;
    private String uri;
    private HttpMethod method;
    private Map<String, String> headers;
    private HttpVersion version;
    private int contentLength = 0;
    private byte[] body;
    private String contentType;
    private String charset = "utf8";
    private boolean isKeepAlive = false;

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
            return new ByteArrayInputStream(body, 0, contentLength);
        }
        return null;
    }

    public String getCharactorEncoding() {
        return charset;
    }

    public int getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getServerName() {
        return serverName;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public String getQueryString() {
        return queryString;
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

    public String getScheme() {
        return "http";
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getUri() {
        return uri;
    }

    public boolean isKeepAlive() {
        // header keys are all lowercased
        return isKeepAlive;
    }

    public void setBody(byte[] body, int count) {
        this.body = body;
        this.contentLength = count;
    }

    public void setRemoteAddr(SocketAddress addr) {
        this.remoteAddr = (InetSocketAddress) addr;
    }

    public void setHeaders(Map<String, String> headers) {
        String h = headers.get(HttpUtils.HOST);
        if (h != null) {
            int idx = h.indexOf(':');
            if (idx != -1) {
                this.serverName = h.substring(0, idx);
                serverPort = Integer.valueOf(h.substring(idx + 1));
            } else {
                this.serverName = h;
            }
        }
        String con = headers.get(CONNECTION);
        if (con != null) {
            con = con.toLowerCase();
        }
        String ct = headers.get(CONTENT_TYPE);
        if (ct != null) {
            int idx = ct.indexOf(";");
            if (idx != -1) {
                contentType = ct.substring(0, idx);
                idx = ct.indexOf(CHARSET, idx);
                if (idx != -1) {
                    charset = ct.substring(idx + CHARSET.length());
                }
            } else {
                contentType = ct;
            }
        }

        isKeepAlive = (version == HTTP_1_1 && !"close".equals(con))
                || "keep-alive".equals(con);

        this.headers = headers;
    }
}
