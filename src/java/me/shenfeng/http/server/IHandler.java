package me.shenfeng.http.server;

public interface IHandler {
	void handle(HttpRequest request, IResponseCallback callback);
}
