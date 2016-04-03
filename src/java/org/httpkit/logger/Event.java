package org.httpkit.logger;

public class Event {

    // ----- server events -----

    public static final String SERVER_ACCEPT_ERROR         = "httpkit.server.accept.error";
    public static final String SERVER_LOOP_ERROR           = "httpkit.server.loop.error";

    public static final String SERVER_WS_DECODE_ERROR      = "httpkit.server.ws.decode.error";
    public static final String SERVER_WS_FRAME_ERROR       = "httpkit.server.ws.frame.error";

    public static final String SERVER_CHANNEL_CLOSE_ERROR  = "httpkit.server.channel.close.error";

    /** Prefix for HTTP status of processed requests */
    public static final String SERVER_STATUS_PREFIX   = "httpkit.server.status.processed.";

    /** Resource not found */
    public static final String SERVER_STATUS_404      = "httpkit.server.status.404";

    /** Request entity too large */
    public static final String SERVER_STATUS_413      = "httpkit.server.status.413";

    /** URI too large */
    public static final String SERVER_STATUS_414      = "httpkit.server.status.414";

    /** Internal error */
    public static final String SERVER_STATUS_500      = "httpkit.server.status.500";

    /** Server overloaded */
    public static final String SERVER_STATUS_503      = "httpkit.server.status.503";

    /** Server overloaded but 503 response not sent */
    public static final String SERVER_STATUS_503_TODO = "httpkit.server.status.503.todo";

    // ----- client events -----

    /** Code path not meant to be executed */
    public static final String CLIENT_IMPOSSIBLE              = "httpkit.client.impossible";

}
