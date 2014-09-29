package org.httpkit;

/**
 * Author: Noam Ben Ari
 * Github: @NoamB
 * Twitter: @nbenari
 * Date: 9/29/14
 */
public class NoopLRUCache<E> implements LRUCache<E>
{
	@Override
	public E getLastInactive(long interval, long now)
	{
		return null;
	}

	@Override
	public boolean add(E e, long now)
	{
		return false;
	}

	@Override
	public boolean remove(E e)
	{
		return false;
	}

	@Override
	public boolean update(E e, long now)
	{
		return false;
	}

	@Override
	public void clear()
	{

	}

	@Override
	public int size()
	{
		return 0;
	}

	@Override
	public boolean isEmpty()
	{
		return true;
	}
}
