package org.httpkit.logger;

import java.util.Collections;
import java.util.Map;

public class EventNames {

    public static final EventNames DEFAULT = new EventNames(Collections.<String, String> emptyMap());

    // ----- server events -----

    public final String serverAcceptError;
    public final String serverLoopError;

    public final String serverWsDecodeError;
    public final String serverWsFrameError;

    public final String serverChannelCloseError;

    /** Prefix for HTTP status of processed requests */
    public final String serverStatusPrefix;

    /** Resource not found */
    public final String serverStatus404;

    /** Request entity too large */
    public final String serverStatus413;

    /** URI too large */
    public final String serverStatus414;

    /** Internal error */
    public final String serverStatus500;

    /** Server overloaded */
    public final String serverStatus503;

    /** Server overloaded but 503 response not sent */
    public final String serverStatus503Todo;

    // ----- client events -----

    /** Code path not meant to be executed */
    public final String clientImpossible;

    public EventNames(Map<String, String> names) {
        this.serverAcceptError       = get(names, "serverAcceptError",       "httpkit.server.accept.error");
        this.serverLoopError         = get(names, "serverLoopError",         "httpkit.server.loop.error");
        this.serverWsDecodeError     = get(names, "serverWsDecodeError",     "httpkit.server.ws.decode.error");
        this.serverWsFrameError      = get(names, "serverWsFrameError",      "httpkit.server.ws.frame.error");
        this.serverChannelCloseError = get(names, "serverChannelCloseError", "httpkit.server.channel.close.error");
        this.serverStatusPrefix      = get(names, "serverStatusPrefix",      "httpkit.server.status.processed.");
        this.serverStatus404         = get(names, "serverStatus404",         "httpkit.server.status.404");
        this.serverStatus413         = get(names, "serverStatus413",         "httpkit.server.status.413");
        this.serverStatus414         = get(names, "serverStatus414",         "httpkit.server.status.414");
        this.serverStatus500         = get(names, "serverStatus500",         "httpkit.server.status.500");
        this.serverStatus503         = get(names, "serverStatus503",         "httpkit.server.status.503");
        this.serverStatus503Todo     = get(names, "serverStatus503Todo",     "httpkit.server.status.503.todo");
        this.clientImpossible        = get(names, "clientImpossible",        "httpkit.client.impossible");
    }

    private static String get(Map<String, String> names, String key, String defaultValue) {
        if (names.containsKey(key)) {
            return names.get(key);
        } else {
            return defaultValue;
        }
    }

}
