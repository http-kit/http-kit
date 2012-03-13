package me.shenfeng.http.codec;

public interface HttpResponse extends HttpMessage {

    /**
     * Returns the status of this response.
     */
    HttpResponseStatus getStatus();

    /**
     * Sets the status of this response.
     */
    void setStatus(HttpResponseStatus status);
}
