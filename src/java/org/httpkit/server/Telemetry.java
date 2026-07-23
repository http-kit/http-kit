package org.httpkit.server;

import org.httpkit.logger.ContextLogger;
import org.httpkit.logger.EventLogger;

final class Telemetry {
    static void log(ContextLogger<String, Throwable> logger, String message,
                    Throwable error) {
        try {
            logger.log(message, error);
        } catch (Throwable ignored) {
        }
    }

    static void log(EventLogger<String> logger, String event) {
        try {
            logger.log(event);
        } catch (Throwable ignored) {
        }
    }

    private Telemetry() {
    }
}
