package org.httpkit;

/**
 * Author: Noam Ben Ari
 * Github: @NoamB
 * Twitter: @nbenari
 * Date: 9/29/14
 */
public interface ActivityTracker<E>
{
	E getLastInactive(long interval, long now);

	boolean add(E e, long now);

	boolean remove(E e);

	boolean update(E e, long now);

	void clear();

	int size();

	boolean isEmpty();
}
