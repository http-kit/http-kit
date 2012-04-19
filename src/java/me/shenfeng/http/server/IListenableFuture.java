package me.shenfeng.http.server;

/**
 * An async extension to the Ring SPEC.
 * 
 * The ring adapter will add an runnable, expect it to be called when things are
 * ready, then it will call the get method to get the real response
 * 
 * @author feng
 * 
 */
public interface IListenableFuture {
    /**
     * register a listener, should be called when things become ready
     * 
     * @param listener
     */
    void addListener(Runnable listener);

    /**
     * @return ring SPEC response
     */
    public Object get();
}
