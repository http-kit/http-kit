package org.httpkit;

import java.util.HashMap;

/**
 * Author: Noam Ben Ari
 * Github: @NoamB
 * Twitter: @nbenari
 * Date: 9/28/14
 *
 * A data structure composed of a List and a Map that performs add and remove in constant time.
 * It holds its values inside the list in insertion order, adding to each its insertion time.
 * This way it can remove easily from its tail an element that is too old.
 */
public class LinkedLRUCache<K> implements LRUCache<K>
{
	private class Element
	{
		public K key;
		public Element next, prev;
		public long lastActivity;

		public Element(K k, long now)
		{
			this.key = k;
			this.lastActivity = now;
		}
	}

	private class LinkedList {
		private Element root = new Element(null, 0L);

		LinkedList() {
			clear();
		}

		public void remove(Element e) {
			e.prev.next = e.next;
			e.next.prev = e.prev;
		}

		// root -> next  => root -> e -> next
		public void pushFront(Element e) {
			Element n = this.root.next;
			n.prev = e;

			this.root.next = e;
			e.next = n;
			e.prev = this.root;
		}

		public Element back() {
			Element p = this.root.prev;
			if (p != this.root) {
				return p;
			}

			return null;
		}

		public void moveToFront(Element e) {
			this.remove(e);
			this.pushFront(e);
		}

		public void clear() {
			this.root.prev = this.root;
			this.root.next = this.root;
		}
	}

	private HashMap<K, Element> map = new HashMap<K, Element>();
	private LinkedList list = new LinkedList();

	@Override
	public K getLastInactive(long interval, long now)
	{
		Element tail = list.back();
		if (tail.lastActivity != 0L && (now - tail.lastActivity) > interval) {
			K lastInactive = tail.key;
			remove(lastInactive);
			return lastInactive;
		}
		return null;
	}

	@Override
	public boolean add(K k, long now)
	{
		if (k == null)
			return false;

		Element e = new Element(k, now);
		map.put(k, e);
		list.pushFront(e);
		return true;
	}

	@Override
	public boolean remove(K k)
	{
		if (k == null || isEmpty())
			return false;

		Element e = map.get(k);
		if (e == null)
			return false;

		map.remove(k);
		list.remove(e);
		return true;
	}

	@Override
	public boolean update(K k, long now)
	{
		if (k == null || isEmpty())
			return false;

		Element e = map.get(k);
		if (e == null)
			return false;

		e.lastActivity = now;
		list.moveToFront(e);
		return true;
	}

	@Override
	public void clear()
	{
		this.map.clear();
		this.list.clear();
	}

	@Override
	public int size()
	{
		return map.size();
	}

	@Override
	public boolean isEmpty()
	{
		return map.isEmpty();
	}
}
