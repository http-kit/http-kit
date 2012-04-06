package me.shenfeng.http.client;

import java.util.Map;

/**
 * Will be invoked once the response/request has been fully read
 */
public interface IEventListener {

	public static final int ABORT = -1;

	public int onBodyReceived(byte[] buf, int length);

	public void onCompleted();

	public int onHeadersReceived(Map<String, String> headers);

	public int onInitialLineReceived(String line);

	/**
	 * protocol error
	 */
	public void onThrowable(Throwable t);

}
