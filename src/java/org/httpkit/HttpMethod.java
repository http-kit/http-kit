package org.httpkit;

import static org.httpkit.server.ClojureRing.*;

import clojure.lang.Keyword;

public enum HttpMethod {

    GET(M_GET), HEAD(M_HEAD), POST(M_POST), PUT(M_PUT), DELETE(M_DELETE), TRACE(M_TRACE), OPTIONS(
            M_OPTIONS), CONNECT(M_CONNECT), PATCH(M_PATCH);

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
