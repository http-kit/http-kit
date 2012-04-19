package me.shenfeng.http.client;

import java.util.Map;

import me.shenfeng.http.DynamicBytes;

public interface IBinaryHandler {
    void onSuccess(int status, Map<String, String> headers, DynamicBytes bytes);

    void onThrowable(Throwable t);
}
