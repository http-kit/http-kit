package org.httpkit;

import java.util.Arrays;
import java.util.Queue;

/**
 * Copy and modified from java.util.PriorityQueue. Remove unused method. Modify
 * {@code remove} to return the removed element
 * <p/>
 * used by timer and the client
 *
 * @param <E>
 */
@SuppressWarnings("unchecked")
public class PriorityQueue<E> {

    private static final int DEFAULT_INITIAL_CAPACITY = 11;

    /**
     * Priority queue represented as a balanced binary heap: the two children of
     * queue[n] are queue[2*n+1] and queue[2*(n+1)]. The priority queue is
     * ordered by comparator, or by the elements' natural ordering, if
     * comparator is null: For each node n in the heap and each descendant d of
     * n, n <= d. The element with the lowest value is in queue[0], assuming the
     * queue is nonempty.
     */
    private transient Object[] queue;

    /**
     * The number of elements in the priority queue.
     */
    private int size = 0;

    /**
     * Creates a {@code PriorityQueue} with the specified initial capacity that
     * orders its elements according to their {@linkplain Comparable natural
     * ordering}.
     * <p/>
     * the initial capacity for this priority queue
     *
     * @throws IllegalArgumentException if {@code initialCapacity} is less than 1
     */
    public PriorityQueue() {
        this.queue = new Object[DEFAULT_INITIAL_CAPACITY];
    }

    /**
     * The maximum size of array to allocate. Some VMs reserve some header words
     * in an array. Attempts to allocate larger arrays may result in
     * OutOfMemoryError: Requested array size exceeds VM limit
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * Increases the capacity of the array.
     *
     * @param minCapacity the desired minimum capacity
     */
    private void grow(int minCapacity) {
        int oldCapacity = queue.length;
        // Double size if small; else grow by 50%
        int newCapacity = oldCapacity
                + ((oldCapacity < 64) ? (oldCapacity + 2) : (oldCapacity >> 1));
        // overflow-conscious code
        if (newCapacity - MAX_ARRAY_SIZE > 0)
            newCapacity = hugeCapacity(minCapacity);
        queue = Arrays.copyOf(queue, newCapacity);
    }

    private static int hugeCapacity(int minCapacity) {
        if (minCapacity < 0) // overflow
            throw new OutOfMemoryError();
        return (minCapacity > MAX_ARRAY_SIZE) ? Integer.MAX_VALUE : MAX_ARRAY_SIZE;
    }

    /**
     * Inserts the specified element into this priority queue.
     *
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws ClassCastException   if the specified element cannot be compared with elements
     *                              currently in this priority queue according to the priority
     *                              queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null)
            throw new NullPointerException();
        int i = size;
        if (i >= queue.length)
            grow(i + 1);
        size = i + 1;
        if (i == 0)
            queue[0] = e;
        else
            siftUp(i, e);
        return true;
    }

    /**
     * Retrieves, but does not remove, the head of this queue, or returns null
     * if this queue is empty.
     *
     * @return
     */
    public E peek() {
        if (size == 0)
            return null;
        return (E) queue[0];
    }

    /**
     * Removes a single instance of the specified element from this queue, if it
     * is present. More formally, removes an element {@code e} such that
     * {@code o.equals(e)}, if this queue contains one or more such elements.
     * Returns {@code true} if and only if this queue contained the specified
     * element (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return Element removed
     */
    public E remove(Object o) {
        for (int i = 0; i < size; i++) {
            if (queue[i].equals(o)) {
                E e = (E) queue[i];
                removeAt(i);
                return e;
            }
        }
        return null;
    }

    public int size() {
        return size;
    }

    /**
     * Retrieves and removes the head of this queue, or returns null if this
     * queue is empty.
     */
    public E poll() {
        if (size == 0)
            return null;
        int s = --size;
        E result = (E) queue[0];
        E x = (E) queue[s];
        queue[s] = null;
        if (s != 0)
            siftDown(0, x);
        return result;
    }

    /**
     * Removes the ith element from queue.
     * <p/>
     * Normally this method leaves the elements at up to i-1, inclusive,
     * untouched. Under these circumstances, it returns null. Occasionally, in
     * order to maintain the heap invariant, it must swap a later element of the
     * list with one earlier than i. Under these circumstances, this method
     * returns the element that was previously at the end of the list and is now
     * at some position before i. This fact is used by iterator.remove so as to
     * avoid missing traversing elements.
     */
    private E removeAt(int i) {
        assert i >= 0 && i < size;
        int s = --size;
        if (s == i) // removed last element
            queue[i] = null;
        else {
            E moved = (E) queue[s];
            queue[s] = null;
            siftDown(i, moved);
            if (queue[i] == moved) {
                siftUp(i, moved);
                if (queue[i] != moved)
                    return moved;
            }
        }
        return null;
    }

    /**
     * Inserts item x at position k, maintaining heap invariant by promoting x
     * up the tree until it is greater than or equal to its parent, or is the
     * root.
     * <p/>
     * To simplify and speed up coercions and comparisons. the Comparable and
     * Comparator versions are separated into different methods that are
     * otherwise identical. (Similarly for siftDown.)
     *
     * @param k the position to fill
     * @param x the item to insert
     */
    private void siftUp(int k, E x) {
        Comparable<? super E> key = (Comparable<? super E>) x;
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object e = queue[parent];
            if (key.compareTo((E) e) >= 0)
                break;
            queue[k] = e;
            k = parent;
        }
        queue[k] = key;
    }

    /**
     * Inserts item x at position k, maintaining heap invariant by demoting x
     * down the tree repeatedly until it is less than or equal to its children
     * or is a leaf.
     *
     * @param k the position to fill
     * @param x the item to insert
     */
    private void siftDown(int k, E x) {
        Comparable<? super E> key = (Comparable<? super E>) x;
        int half = size >>> 1; // loop while a non-leaf
        while (k < half) {
            int child = (k << 1) + 1; // assume left child is least
            Object c = queue[child];
            int right = child + 1;
            if (right < size && ((Comparable<? super E>) c).compareTo((E) queue[right]) > 0)
                c = queue[child = right];
            if (key.compareTo((E) c) <= 0)
                break;
            queue[k] = c;
            k = child;
        }
        queue[k] = key;
    }

    @Override
    public String toString() {
        return "size=" + size;
    }
}
