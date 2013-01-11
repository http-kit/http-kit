package me.shenfeng.http.server;

import static me.shenfeng.http.HttpUtils.CHARSET;
import static me.shenfeng.http.HttpUtils.CONNECTION;
import static me.shenfeng.http.HttpUtils.CONTENT_TYPE;
import static me.shenfeng.http.HttpVersion.HTTP_1_1;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

import me.shenfeng.http.BytesInputStream;
import me.shenfeng.http.HttpMethod;
import me.shenfeng.http.HttpUtils;
import me.shenfeng.http.HttpVersion;
import me.shenfeng.http.ws.WsCon;

public class HttpRequest {
    private int serverPort;
    private String serverName;
    private InetSocketAddress remoteAddr;
    public final String queryString;
    public final String uri;
    public final HttpMethod method;
    private Map<String, String> headers;
    public final HttpVersion version;
    private int contentLength = 0;
    private byte[] body;
    private String contentType;
    private String charset = "utf8";
    private boolean isKeepAlive = false;
    private boolean isWebSocket = false;
    private WsCon webSocketCon;

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
            return new BytesInputStream(body, 0, contentLength);
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

    public void setWebSocketCon(WsCon con) {
        this.webSocketCon = con;
    }

    public WsCon getWebSocketCon() {
        return webSocketCon;
    }

    public boolean isWs() {
        return isWebSocket;
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

        isKeepAlive = (version == HTTP_1_1 && !"close".equals(con)) || "keep-alive".equals(con);
        isWebSocket = "websocket".equals(headers.get("Upgrade"));
        this.headers = headers;
    }
}
