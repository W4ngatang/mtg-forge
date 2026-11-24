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
 * One browser connection. {@code /ws?new=DANDAN&name=Alex&password=...} starts a game against the
 * computer; {@code /ws?game=ID&seat=TOKEN} reconnects to it. The first message tells the browser
 * the game id and seat token to keep for reconnecting.
 */
@WebSocket(maxTextMessageSize = 64 * 1024)
public class GameSocket {
    private static final int MAX_NAME = 20;

    private final WebServer server;
    private Session session;
    private GameRoom room;
    private Consumer<String> sink;

    GameSocket(WebServer server) {
        this.server = server;
    }

    @OnWebSocketConnect
    public void onConnect(Session s) {
        session = s;
        Map<String, List<String>> q = s.getUpgradeRequest().getParameterMap();
        try {
            if (q.containsKey("game")) {
                room = server.room(first(q, "game"));
                if (room == null || !room.seatToken.equals(first(q, "seat"))) {
                    close("No such game, or not your seat.");
                    return;
                }
            } else {
                if (!server.passwordMatches(first(q, "password"))) {
                    close("Wrong host password.");
                    return;
                }
                WebFormat format = WebFormat.parse(first(q, "new"));
                if (format == null) {
                    close("Unknown format.");
                    return;
                }
                room = server.newRoom(format, playerName(first(q, "name")));
            }
        } catch (RuntimeException e) {
            close("Couldn't start the game: " + e.getMessage());
            return;
        }
        JsonObject joined = new JsonObject();
        joined.addProperty("t", "joined");
        joined.addProperty("game", room.id);
        joined.addProperty("seat", room.seatToken);
        joined.addProperty("format", room.format.label);
        sendNow(joined.toString());
        sink = this::sendNow;
        room.seat.attach(sink);
    }

    @OnWebSocketMessage
    public void onMessage(String text) {
        if (room != null) {
            room.seat.receive(text);
        }
    }

    @OnWebSocketClose
    public void onClose(int status, String reason) {
        if (room != null && sink != null) {
            room.seat.detach(sink);
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

    private static String playerName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        return name.isEmpty() ? "Player" : name;
    }
}
