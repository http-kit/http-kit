package org.httpkit.client;

import org.httpkit.PriorityQueue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.util.Queue;

public class ConnectRequest extends Request{
    public Request nextRequest;

    public ConnectRequest(InetSocketAddress addr, InetSocketAddress realAddr,
                          ByteBuffer[] request, IRespListener handler,
                          PriorityQueue<Request> clients, RequestConfig config, Request nextRequest) {
        super(addr, realAddr, request, handler, clients, config, null);
        this.nextRequest = nextRequest;
    }

    public void finish() {
        clients.remove(this);
        isDone = true;
    }
}
