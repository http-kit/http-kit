package me.shenfeng.http.client;

import me.shenfeng.http.DynamicBytes;

import java.util.Map;

public interface IBinaryHandler {
    void onSuccess(int status, Map<String, String> headers, DynamicBytes bytes);

    void onThrowable(Throwable t);
}
