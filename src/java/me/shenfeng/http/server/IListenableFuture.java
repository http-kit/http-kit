package me.shenfeng.http.server;

/**
 * An async extension to the Ring SPEC. You give me an runnable, You call it
 * when things are ready. Then I call the <get> method to get the real response.
 * 
 * @author feng
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
