package org.httpkit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Author: Noam Ben Ari
 * Github: @NoamB
 * Twitter: @nbenari
 * Date: 9/28/14
 *
 * A data structure composed of a List and a Map that performs add and remove in constant time.
 * It holds its values inside the list in insertion order, adding to each its insertion time.
 * This way it can remove easily from its tail a node that is too old.
 */
public class ActivityCollection<E> implements IActivityCollection<E>
{
	private class Node
	{
		public E value;
		public Node next, prev;
		public long lastActivity;

		public Node(E e)
		{
			this.value = e;
			this.lastActivity = System.currentTimeMillis();
		}
	}

	private int size;
	private HashMap<E, Node> map = new HashMap<E, Node>();

	public Node head, tail;

	@Override
	public E getLastInactive(long interval)
	{
		if (isEmpty())
			return null;

		long now = System.currentTimeMillis();
		if ((now - tail.lastActivity) > interval) {
			E lastInactive = tail.value;
			remove(lastInactive);
			return lastInactive;
		} else {
			return null;
		}
	}

	@Override
	public boolean add(E e)
	{
		if (e == null)
			return false;

		Node toAdd = new Node(e);

		if (isEmpty()) {
			this.head = toAdd;
			this.tail = toAdd;
		} else {
			Node temp = this.head;
			this.head = toAdd;
			this.head.next = temp;
		}

		map.put(e, toAdd);
		size++;
		return true;
	}

	@Override
	public boolean remove(E e)
	{
		if (e == null)
			return false;

		if (isEmpty())
			return false;

		Node node = map.get(e);
		if (node == null)
			return false;

		map.remove(e);
		Node prev = node.prev;
		prev.next = node.next;
		size--;
		if (isEmpty())
			this.head = null;
		return true;
	}

	@Override
	public boolean update(E e)
	{
		return remove(e) && add(e);
	}

	@Override
	public void clear()
	{
		this.map.clear();
		this.head = null;
		this.tail = null;
	}

	@Override
	public int size()
	{
		return size;
	}

	@Override
	public boolean isEmpty()
	{
		return size == 0;
	}
}
