package org.httpkit.server;

import org.httpkit.*;

import java.io.InputStream;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.Map;

import static org.httpkit.HttpUtils.*;
import static org.httpkit.HttpVersion.HTTP_1_1;

public class HttpRequest {
    public final String queryString;
    public final String uri;
    public final HttpMethod method;
    public final HttpVersion version;

    private byte[] body;
    private final boolean legacyUnsafeRemoteAddr;
    private String proxyRemoteAddr;

    // package visible
    int serverPort = 80;
    String serverName;
    public String protocol() {
        if (version == HttpVersion.HTTP_1_0) {
            return "HTTP/1.0";
        }
        return "HTTP/1.1";
    }
    Map<String, Object> headers;
    int contentLength = 0;
    String contentType;
    String charset = "utf8";
    boolean isKeepAlive = false;
    boolean isWebSocket = false;
    long startTime;
    boolean sentContinue = false;

    SocketAddress remoteAddr;
    AsyncChannel channel;

    public HttpRequest(HttpMethod method, String url, HttpVersion version, boolean legacyUnsafeRemoteAddr) {
        this.method = method;
        this.version = version;
        this.legacyUnsafeRemoteAddr = legacyUnsafeRemoteAddr;
        int idx = url.indexOf('?');
        if (idx > 0) {
            uri = url.substring(0, idx);
            queryString = url.substring(idx + 1);
        } else {
            uri = url;
            queryString = null;
        }
    }

    public void setStartTime(long time) {
        this.startTime = time;
    }

    public InputStream getBody() {
        if (body != null) {
            return new BytesInputStream(body, contentLength);
        }
        return null;
    }

    public String getRemoteAddr() {
        if (proxyRemoteAddr != null) {
            return proxyRemoteAddr;
        }
        if (legacyUnsafeRemoteAddr) {
            // legacy behavior: use X-Forwarded-For if present (INSECURE - allows spoofing)
            String h = getStringValue(headers, X_FORWARDED_FOR);
            if (null != h) {
                int idx = h.indexOf(',');
                if (idx == -1) {
                    return h;
                } else {
                    // X-Forwarded-For: client, proxy1, proxy2
                    return h.substring(0, idx);
                }
            }
        }
        // secure behavior: always return actual socket address
        if (remoteAddr instanceof InetSocketAddress) {
            return ((InetSocketAddress) remoteAddr).getAddress().getHostAddress();
        } else {
            return null;
        }
    }

    public void setBody(byte[] body, int count) {
        this.body = body;
        this.contentLength = count;
    }

    void setProxyRemoteAddr(String remoteAddr) {
        this.proxyRemoteAddr = remoteAddr;
    }

    public void setHeaders(Map<String, Object> headers) throws ProtocolException {
        String h = getStringValue(headers, "host");
        if (version == HTTP_1_1 && (h == null || h.isEmpty())) {
            throw new ProtocolException("HTTP/1.1 request is missing Host header");
        }
        if (h != null) {
            if (h.isEmpty() || h.indexOf('\n') >= 0 || h.indexOf('@') >= 0
                    || h.indexOf('/') >= 0 || h.indexOf('\\') >= 0
                    || h.indexOf(' ') >= 0 || h.indexOf('\t') >= 0) {
                throw new ProtocolException("Invalid host header: " + h);
            }
            // the port is an integer following the last ':'
            // *unless* the last : is prior to the last ] which marks the end of an ipv6 address
            // https://en.wikipedia.org/wiki/IPv6_address#Literal_IPv6_addresses_in_network_resource_identifiers
            int ipv6end = (h.charAt(0) == '[') ? h.lastIndexOf(']') : 0;
            int idx = h.lastIndexOf(':');
            if (idx != -1 && idx > ipv6end) {
                this.serverName = h.substring(0, idx);
                String serverPortCandidate = h.substring(idx + 1);
                if (!serverPortCandidate.isEmpty()) {
                    try {
                        for (int i = 0; i < serverPortCandidate.length(); i++) {
                            char c = serverPortCandidate.charAt(i);
                            if (c < '0' || c > '9') {
                                throw new NumberFormatException("port is not decimal");
                            }
                        }
                        serverPort = Integer.parseInt(serverPortCandidate);
                        if (serverPort < 0 || serverPort > 65535) {
                            throw new NumberFormatException("port out of range");
                        }
                    } catch (NumberFormatException e) {
                        throw new ProtocolException("Invalid host header (bad port): " + h);
                    }
                }
            } else {
                this.serverName = h;
            }
        }

        String ct = getStringValue(headers, CONTENT_TYPE);
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

        String con = getStringValue(headers, CONNECTION);
        isKeepAlive = version == HTTP_1_1
                ? !hasHeaderToken(con, "close")
                : hasHeaderToken(con, "keep-alive") && !hasHeaderToken(con, "close");
        isWebSocket = hasHeaderToken(getStringValue(headers, "upgrade"), "websocket");
        this.headers = headers;
    }
}
