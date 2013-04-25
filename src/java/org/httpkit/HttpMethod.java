package org.httpkit;

import clojure.lang.Keyword;

import static clojure.lang.Keyword.intern;

public enum HttpMethod {

    GET(intern("get")), HEAD(intern("head")), POST(intern("post")), PUT(intern("put")),
    DELETE(intern("delete")), TRACE(intern("trace")), OPTIONS(intern("options")),
    CONNECT(intern("connect")), PATCH(intern("patch"));

    public final Keyword KEY;

    private HttpMethod(Keyword key) {
        this.KEY = key;
    }

    public static HttpMethod fromKeyword(Keyword k) {
        HttpMethod[] values = HttpMethod.values();
        for (HttpMethod m : values) {
            if (m.KEY.equals(k)) {
                return m;
            }
        }
        throw new RuntimeException("unsupported method " + k);
    }
}
