package forge.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import com.google.gson.JsonObject;

/**
 * One browser playing one seat: {@code /ws?game=ID&seat=TOKEN}, with the seat token from creating
 * or joining the game (see {@link ApiServlet}). Reconnecting is connecting again with the same token.
 * The first message, {@code joined}, names the format and players; a friend game nobody has joined
 * yet then sends {@code waiting} with the invite.
 */
@WebSocket(maxTextMessageSize = 64 * 1024)
public class GameSocket {
    private final WebServer server;
    private Session session;
    private GameRoom.Seat seat;
    private Consumer<String> sink;

    GameSocket(WebServer server) {
        this.server = server;
    }

    @OnWebSocketConnect
    public void onConnect(Session s) {
        session = s;
        Map<String, List<String>> q = s.getUpgradeRequest().getParameterMap();
        GameRoom room = server.room(first(q, "game"));
        seat = room == null ? null : room.seat(first(q, "seat"));
        if (seat == null) {
            close("No such game, or not your seat.");
            return;
        }
        JsonObject joined = new JsonObject();
        joined.addProperty("t", "joined");
        joined.addProperty("game", room.id);
        joined.addProperty("format", room.format.label);
        joined.addProperty("you", seat.name);
        String opponent = room.opponentName(seat);
        if (opponent != null) {
            joined.addProperty("opponent", opponent);
        }
        sendNow(joined.toString());
        if (!room.isStarted() && room.invite() != null) {
            JsonObject waiting = new JsonObject();
            waiting.addProperty("t", "waiting");
            waiting.addProperty("invite", room.invite());
            sendNow(waiting.toString());
        }
        sink = this::sendNow;
        seat.gui.attach(sink);
    }

    @OnWebSocketMessage
    public void onMessage(String text) {
        if (seat != null) {
            seat.gui.receive(text);
        }
    }

    @OnWebSocketClose
    public void onClose(int status, String reason) {
        if (seat != null && sink != null) {
            seat.gui.detach(sink);
        }
    }

    private void sendNow(String text) {
        Session s = session;
        if (s == null || !s.isOpen()) {
            return;
        }
        try {
            s.getRemote().sendString(text);
        } catch (IOException e) {
            // the browser went away; it gets everything it missed when it reconnects
        }
    }

    private void close(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "error");
        o.addProperty("text", why);
        sendNow(o.toString());
        session.close(4000, why);
    }

    private static String first(Map<String, List<String>> q, String key) {
        List<String> v = q.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
