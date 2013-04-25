package org.httpkit;

import static org.httpkit.HttpUtils.ASCII;

public class HttpStatus {

    private final int code;
    private final String reasonPhrase;
    private final byte[] bytes;

    /**
     * 100 Continue
     */
    public static final HttpStatus CONTINUE = new HttpStatus(100, "Continue");

    /**
     * 101 Switching Protocols
     */
    public static final HttpStatus SWITCHING_PROTOCOLS = new HttpStatus(101, "Switching Protocols");

    /**
     * 102 Processing (WebDAV, RFC2518)
     */
    public static final HttpStatus PROCESSING = new HttpStatus(102, "Processing");

    /**
     * 200 OK
     */
    public static final HttpStatus OK = new HttpStatus(200, "OK");

    /**
     * 201 Created
     */
    public static final HttpStatus CREATED = new HttpStatus(201, "Created");

    /**
     * 202 Accepted
     */
    public static final HttpStatus ACCEPTED = new HttpStatus(202, "Accepted");

    /**
     * 203 Non-Authoritative Information (since HTTP/1.1)
     */
    public static final HttpStatus NON_AUTHORITATIVE_INFORMATION = new HttpStatus(203,
            "Non-Authoritative Information");

    /**
     * 204 No Content
     */
    public static final HttpStatus NO_CONTENT = new HttpStatus(204, "No Content");

    /**
     * 205 Reset Content
     */
    public static final HttpStatus RESET_CONTENT = new HttpStatus(205, "Reset Content");

    /**
     * 206 Partial Content
     */
    public static final HttpStatus PARTIAL_CONTENT = new HttpStatus(206, "Partial Content");

    /**
     * 207 Multi-Status (WebDAV, RFC2518)
     */
    public static final HttpStatus MULTI_STATUS = new HttpStatus(207, "Multi-Status");

    /**
     * 300 Multiple Choices
     */
    public static final HttpStatus MULTIPLE_CHOICES = new HttpStatus(300, "Multiple Choices");

    /**
     * 301 Moved Permanently
     */
    public static final HttpStatus MOVED_PERMANENTLY = new HttpStatus(301, "Moved Permanently");

    /**
     * 302 Found
     */
    public static final HttpStatus FOUND = new HttpStatus(302, "Found");

    /**
     * 303 See Other (since HTTP/1.1)
     */
    public static final HttpStatus SEE_OTHER = new HttpStatus(303, "See Other");

    /**
     * 304 Not Modified
     */
    public static final HttpStatus NOT_MODIFIED = new HttpStatus(304, "Not Modified");

    /**
     * 305 Use Proxy (since HTTP/1.1)
     */
    public static final HttpStatus USE_PROXY = new HttpStatus(305, "Use Proxy");

    /**
     * 307 Temporary Redirect (since HTTP/1.1)
     */
    public static final HttpStatus TEMPORARY_REDIRECT = new HttpStatus(307, "Temporary Redirect");

    /**
     * 400 Bad Request
     */
    public static final HttpStatus BAD_REQUEST = new HttpStatus(400, "Bad Request");

    /**
     * 401 Unauthorized
     */
    public static final HttpStatus UNAUTHORIZED = new HttpStatus(401, "Unauthorized");

    /**
     * 402 Payment Required
     */
    public static final HttpStatus PAYMENT_REQUIRED = new HttpStatus(402, "Payment Required");

    /**
     * 403 Forbidden
     */
    public static final HttpStatus FORBIDDEN = new HttpStatus(403, "Forbidden");

    /**
     * 404 Not Found
     */
    public static final HttpStatus NOT_FOUND = new HttpStatus(404, "Not Found");

    /**
     * 405 Method Not Allowed
     */
    public static final HttpStatus METHOD_NOT_ALLOWED = new HttpStatus(405,
            "Method Not Allowed");

    /**
     * 406 Not Acceptable
     */
    public static final HttpStatus NOT_ACCEPTABLE = new HttpStatus(406, "Not Acceptable");

    /**
     * 407 Proxy Authentication Required
     */
    public static final HttpStatus PROXY_AUTHENTICATION_REQUIRED = new HttpStatus(407,
            "Proxy Authentication Required");

    /**
     * 408 Request Timeout
     */
    public static final HttpStatus REQUEST_TIMEOUT = new HttpStatus(408, "Request Timeout");

    /**
     * 409 Conflict
     */
    public static final HttpStatus CONFLICT = new HttpStatus(409, "Conflict");

    /**
     * 410 Gone
     */
    public static final HttpStatus GONE = new HttpStatus(410, "Gone");

    /**
     * 411 Length Required
     */
    public static final HttpStatus LENGTH_REQUIRED = new HttpStatus(411, "Length Required");

    /**
     * 412 Precondition Failed
     */
    public static final HttpStatus PRECONDITION_FAILED = new HttpStatus(412,
            "Precondition Failed");

    /**
     * 413 Request Entity Too Large
     */
    public static final HttpStatus REQUEST_ENTITY_TOO_LARGE = new HttpStatus(413,
            "Request Entity Too Large");

    /**
     * 414 Request-URI Too Long
     */
    public static final HttpStatus REQUEST_URI_TOO_LONG = new HttpStatus(414,
            "Request-URI Too Long");

    /**
     * 415 Unsupported Media Type
     */
    public static final HttpStatus UNSUPPORTED_MEDIA_TYPE = new HttpStatus(415,
            "Unsupported Media Type");

    /**
     * 416 Requested Range Not Satisfiable
     */
    public static final HttpStatus REQUESTED_RANGE_NOT_SATISFIABLE = new HttpStatus(416,
            "Requested Range Not Satisfiable");

    /**
     * 417 Expectation Failed
     */
    public static final HttpStatus EXPECTATION_FAILED = new HttpStatus(417,
            "Expectation Failed");

    /**
     * 422 Unprocessable Entity (WebDAV, RFC4918)
     */
    public static final HttpStatus UNPROCESSABLE_ENTITY = new HttpStatus(422,
            "Unprocessable Entity");

    /**
     * 423 Locked (WebDAV, RFC4918)
     */
    public static final HttpStatus LOCKED = new HttpStatus(423, "Locked");

    /**
     * 424 Failed Dependency (WebDAV, RFC4918)
     */
    public static final HttpStatus FAILED_DEPENDENCY = new HttpStatus(424, "Failed Dependency");

    /**
     * 425 Unordered Collection (WebDAV, RFC3648)
     */
    public static final HttpStatus UNORDERED_COLLECTION = new HttpStatus(425, "Unordered Collection");

    /**
     * 426 Upgrade Required (RFC2817)
     */
    public static final HttpStatus UPGRADE_REQUIRED = new HttpStatus(426, "Upgrade Required");

    /**
     * 500 Internal Server Error
     */
    public static final HttpStatus INTERNAL_SERVER_ERROR = new HttpStatus(500, "Internal Server Error");

    /**
     * 501 Not Implemented
     */
    public static final HttpStatus NOT_IMPLEMENTED = new HttpStatus(501, "Not Implemented");

    /**
     * 502 Bad Gateway
     */
    public static final HttpStatus BAD_GATEWAY = new HttpStatus(502, "Bad Gateway");

    /**
     * 503 Service Unavailable
     */
    public static final HttpStatus SERVICE_UNAVAILABLE = new HttpStatus(503, "Service Unavailable");

    /**
     * 504 Gateway Timeout
     */
    public static final HttpStatus GATEWAY_TIMEOUT = new HttpStatus(504, "Gateway Timeout");

    /**
     * 505 HTTP Version Not Supported
     */
    public static final HttpStatus HTTP_VERSION_NOT_SUPPORTED = new HttpStatus(505,
            "HTTP Version Not Supported");

    /**
     * 506 Variant Also Negotiates (RFC2295)
     */
    public static final HttpStatus VARIANT_ALSO_NEGOTIATES = new HttpStatus(506,
            "Variant Also Negotiates");

    /**
     * 507 Insufficient Storage (WebDAV, RFC4918)
     */
    public static final HttpStatus INSUFFICIENT_STORAGE = new HttpStatus(507,
            "Insufficient Storage");

    /**
     * 510 Not Extended (RFC2774)
     */
    public static final HttpStatus NOT_EXTENDED = new HttpStatus(510, "Not Extended");

    public static HttpStatus valueOf(int code) {
        switch (code) {
            case 100:
                return CONTINUE;
            case 101:
                return SWITCHING_PROTOCOLS;
            case 102:
                return PROCESSING;
            case 200:
                return OK;
            case 201:
                return CREATED;
            case 202:
                return ACCEPTED;
            case 203:
                return NON_AUTHORITATIVE_INFORMATION;
            case 204:
                return NO_CONTENT;
            case 205:
                return RESET_CONTENT;
            case 206:
                return PARTIAL_CONTENT;
            case 207:
                return MULTI_STATUS;
            case 300:
                return MULTIPLE_CHOICES;
            case 301:
                return MOVED_PERMANENTLY;
            case 302:
                return FOUND;
            case 303:
                return SEE_OTHER;
            case 304:
                return NOT_MODIFIED;
            case 305:
                return USE_PROXY;
            case 307:
                return TEMPORARY_REDIRECT;
            case 400:
                return BAD_REQUEST;
            case 401:
                return UNAUTHORIZED;
            case 402:
                return PAYMENT_REQUIRED;
            case 403:
                return FORBIDDEN;
            case 404:
                return NOT_FOUND;
            case 405:
                return METHOD_NOT_ALLOWED;
            case 406:
                return NOT_ACCEPTABLE;
            case 407:
                return PROXY_AUTHENTICATION_REQUIRED;
            case 408:
                return REQUEST_TIMEOUT;
            case 409:
                return CONFLICT;
            case 410:
                return GONE;
            case 411:
                return LENGTH_REQUIRED;
            case 412:
                return PRECONDITION_FAILED;
            case 413:
                return REQUEST_ENTITY_TOO_LARGE;
            case 414:
                return REQUEST_URI_TOO_LONG;
            case 415:
                return UNSUPPORTED_MEDIA_TYPE;
            case 416:
                return REQUESTED_RANGE_NOT_SATISFIABLE;
            case 417:
                return EXPECTATION_FAILED;
            case 422:
                return UNPROCESSABLE_ENTITY;
            case 423:
                return LOCKED;
            case 424:
                return FAILED_DEPENDENCY;
            case 425:
                return UNORDERED_COLLECTION;
            case 426:
                return UPGRADE_REQUIRED;
            case 500:
                return INTERNAL_SERVER_ERROR;
            case 501:
                return NOT_IMPLEMENTED;
            case 502:
                return BAD_GATEWAY;
            case 503:
                return SERVICE_UNAVAILABLE;
            case 504:
                return GATEWAY_TIMEOUT;
            case 505:
                return HTTP_VERSION_NOT_SUPPORTED;
            case 506:
                return VARIANT_ALSO_NEGOTIATES;
            case 507:
                return INSUFFICIENT_STORAGE;
            case 510:
                return NOT_EXTENDED;
        }

        final String reasonPhrase;

        if (code < 100) {
            reasonPhrase = "Unknown Status";
        } else if (code < 200) {
            reasonPhrase = "Informational";
        } else if (code < 300) {
            reasonPhrase = "Successful";
        } else if (code < 400) {
            reasonPhrase = "Redirection";
        } else if (code < 500) {
            reasonPhrase = "Client Error";
        } else if (code < 600) {
            reasonPhrase = "Server Error";
        } else {
            reasonPhrase = "Unknown Status";
        }

        return new HttpStatus(code, reasonPhrase + " (" + code + ')');
    }

    public HttpStatus(int code, String reasonPhrase) {
        this.code = code;
        this.reasonPhrase = reasonPhrase;
        bytes = ("HTTP/1.1 " + code + " " + reasonPhrase + "\r\n").getBytes(ASCII);
    }

    public int getCode() {
        return code;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public byte[] getInitialLineBytes() {
        return bytes;
    }
}
