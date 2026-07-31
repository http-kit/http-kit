package org.httpkit.server;

public class WsAtta extends ServerAtta {

    final public WSDecoder decoder;
    int closeStatus;
    String closeReason = "";

    public WsAtta(AsyncChannel channel, int maxSize) {
        this.decoder = new WSDecoder(maxSize);
        this.channel = channel;
        // NOTE: the codec is NOT read here. This attachment is created while
        // the upgrade REQUEST is decoded, which is before the Ring handler runs
        // and therefore before the handshake has negotiated anything -- reading
        // it here captured null, the decoder then rejected the client's first
        // RSV1 frame as an unsupported extension, and the connection died.
        // AsyncChannel.setPerMessageDeflate pushes it in once negotiation has
        // actually happened.
    }
}
