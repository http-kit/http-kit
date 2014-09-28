package org.httpkit;

/**
 * Author: noam
 * Date: 9/29/14
 */
public interface IActivityCollection<E>
{
	E getLastInactive(long interval);

	boolean add(E e);

	boolean remove(E e);

	boolean update(E e);

	void clear();

	int size();

	boolean isEmpty();
}
