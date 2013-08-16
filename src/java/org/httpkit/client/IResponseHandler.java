package org.httpkit.client;

import java.util.Map;

public interface IResponseHandler {

    /**
     * get called when all the response are fully received from server
     *
     * @param status  HTTP status code, like 200
     * @param headers Response HTTP headers
     * @param body    Response body, for text is a String, for binary is a
     *                InputStream
     */
    void onSuccess(int status, Map<String, Object> headers, Object body);

    void onThrowable(Throwable t);
}
