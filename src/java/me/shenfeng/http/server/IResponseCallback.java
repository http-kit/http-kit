package me.shenfeng.http.server;

import java.util.Map;

public interface IResponseCallback {
	public void run(int status, Map<String, Object> headers, Object body);
}
