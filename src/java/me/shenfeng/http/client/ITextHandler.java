package me.shenfeng.http.client;

import java.util.Map;

public interface ITextHandler {
    void onSuccess(int status, Map<String, String> headers, String body);

    void onThrowable(Throwable t);
}
