package me.shenfeng.http.server;

public interface IListenableFuture {
    void addListener(Runnable listener);

    public Object get();
}
