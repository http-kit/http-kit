package org.httpkit;

/**
 * Author: noam
 * Date: 9/29/14
 */
public class NoopActivityCollection<E> implements IActivityCollection<E>
{
	@Override
	public E getLastInactive(long interval)
	{
		return null;
	}

	@Override
	public boolean add(E e)
	{
		return false;
	}

	@Override
	public boolean remove(E e)
	{
		return false;
	}

	@Override
	public boolean update(E e)
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
