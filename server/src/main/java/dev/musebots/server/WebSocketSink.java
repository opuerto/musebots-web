package dev.musebots.server;

import dev.musebots.core.EventSink;
import dev.musebots.core.PerformanceEvent;
import io.javalin.websocket.WsContext;

/** Forwards a performance's events to one browser. Agents emit from many threads, so sends are serialised. */
final class WebSocketSink implements EventSink {

    private final WsContext connection;

    WebSocketSink(WsContext connection) {
        this.connection = connection;
    }

    @Override
    public void emit(PerformanceEvent event) {
        send(Wire.encode(event));
    }

    void send(String json) {
        synchronized (connection) {
            if (connection.session.isOpen()) {
                connection.send(json);
            }
        }
    }
}
