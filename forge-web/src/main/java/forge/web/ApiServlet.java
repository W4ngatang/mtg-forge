package forge.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.deck.Deck;

/**
 * The lobby, as JSON over HTTP under {@code /api}:
 * <pre>
 * GET    /api/formats                      the formats on offer
 * GET    /api/session                      whether this browser is past the site password, and whether the host password given is right
 * POST   /api/login                        enter the site password
 * POST   /api/games                        create a game (host password)
 * GET    /api/games/{id}?invite=TOKEN      what an invite link is for
 * POST   /api/games/{id}/join              take the friend's seat with the invite
 * GET    /api/decks                        saved shared deck lists (host password)
 * PUT    /api/decks                        save one (host password)
 * DELETE /api/decks?name=NAME              delete one (host password)
 * POST   /api/decks/check                  check a pasted list
 * </pre>
 * The host password goes in the {@code X-Host-Password} header, never the URL.
 */
public class ApiServlet extends HttpServlet {
    private static final int MAX_BODY = 64 * 1024;
    private final WebServer server;

    ApiServlet(WebServer server) {
        this.server = server;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        String method = req.getMethod();
        try {
            JsonElement out = route(method, path, req, resp);
            if (out != null) {
                resp.getWriter().write(out.toString());
            }
        } catch (ApiError e) {
            resp.setStatus(e.status);
            JsonObject o = new JsonObject();
            o.addProperty("error", e.getMessage());
            resp.getWriter().write(o.toString());
        } catch (RuntimeException e) {
            e.printStackTrace();
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"Something went wrong on the server.\"}");
        }
    }

    private JsonElement route(String method, String path, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        String first = parts.length > 0 ? parts[0] : "";
        switch (method + " " + first + (parts.length > 1 ? "/" + (parts.length > 2 ? "*/" + parts[2] : "*") : "")) {
            case "GET formats": return formats();
            case "GET session": {
                JsonObject o = new JsonObject();
                o.addProperty("site", server.gate().admitted(req));
                o.addProperty("host", server.passwordMatches(req.getHeader("X-Host-Password")));
                return o;
            }
            case "POST login": {
                if (!server.gate().passwordMatches(str(body(req), "password"))) {
                    try {
                        Thread.sleep(700); // slows guessing
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new ApiError(403, "That's not the password.");
                }
                server.gate().admit(req, resp);
                return ok();
            }
            case "POST games": hostOnly(req); return createGame(body(req));
            case "GET games/*": {
                JsonObject o = inviteInfo(parts[1], req.getParameter("invite"));
                server.gate().admit(req, resp); // a valid invite lets a friend past the site password
                return o;
            }
            case "POST games/*/join": {
                JsonObject o = join(parts[1], body(req));
                server.gate().admit(req, resp);
                return o;
            }
            case "GET decks": hostOnly(req); return decks();
            case "PUT decks": hostOnly(req); return saveDeck(body(req));
            case "DELETE decks": hostOnly(req); return deleteDeck(req.getParameter("name"));
            case "POST decks/*":
                if (parts[1].equals("check")) {
                    return check(body(req));
                }
                throw new ApiError(404, "No such page.");
            default: throw new ApiError(404, "No such page.");
        }
    }

    private void hostOnly(HttpServletRequest req) {
        if (!server.passwordMatches(req.getHeader("X-Host-Password"))) {
            throw new ApiError(403, "Wrong host password.");
        }
    }

    private static JsonObject body(HttpServletRequest req) throws IOException {
        try (InputStream in = req.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new ApiError(413, "That's too long.");
            }
            JsonElement e = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                throw new ApiError(400, "Expected a JSON object.");
            }
            return e.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new ApiError(400, "Expected a JSON object.");
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonArray formats() {
        JsonArray a = new JsonArray();
        for (WebFormat f : WebFormat.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.name());
            o.addProperty("label", f.label);
            o.addProperty("description", f.description);
            o.addProperty("hostPicksDeck", f.hostPicksDeck);
            a.add(o);
        }
        return a;
    }

    private JsonObject createGame(JsonObject b) throws IOException {
        WebFormat format = WebFormat.parse(str(b, "format"));
        if (format == null) {
            throw new ApiError(400, "Unknown format.");
        }
        boolean friend = "friend".equals(str(b, "vs"));
        Deck deck = format.hostPicksDeck ? chosenDeck(b.has("deck") && b.get("deck").isJsonObject() ? b.getAsJsonObject("deck") : new JsonObject()) : null;
        GameRoom room = server.newRoom(format, WebServer.playerName(str(b, "name")), deck, friend);
        JsonObject o = new JsonObject();
        o.addProperty("game", room.id);
        o.addProperty("seat", room.host.token);
        if (room.invite() != null) {
            o.addProperty("invite", room.invite());
        }
        return o;
    }

    /** The host's deck: {"kind":"standard"}, {"kind":"saved","name":..} or {"kind":"text","text":..}. */
    private Deck chosenDeck(JsonObject d) throws IOException {
        String kind = str(d, "kind");
        if (kind == null || kind.equals("standard")) {
            return null;
        }
        String text;
        String name;
        if (kind.equals("saved")) {
            name = DeckLists.cleanName(str(d, "name"));
            text = server.decks().get(name);
            if (text == null) {
                throw new ApiError(404, "No saved deck called \"" + name + "\".");
            }
        } else if (kind.equals("text")) {
            name = "Pasted deck";
            text = str(d, "text");
        } else {
            throw new ApiError(400, "Unknown deck choice.");
        }
        DeckLists.Parsed parsed = DeckLists.parse(name, text);
        if (!parsed.ok()) {
            throw new ApiError(400, String.join("\n", parsed.problems()));
        }
        return parsed.deck();
    }

    private JsonObject inviteInfo(String gameId, String invite) {
        GameRoom room = server.room(gameId);
        if (room == null || invite == null || !invite.equals(room.invite())) {
            throw new ApiError(404, "This invite has been used or has expired.");
        }
        JsonObject o = new JsonObject();
        o.addProperty("format", room.format.label);
        o.addProperty("host", room.host.name);
        return o;
    }

    private JsonObject join(String gameId, JsonObject b) {
        GameRoom room = server.room(gameId);
        GameRoom.Seat seat = room == null ? null : room.join(str(b, "invite"), WebServer.playerName(str(b, "name")));
        if (seat == null) {
            throw new ApiError(404, "This invite has been used or has expired.");
        }
        JsonObject o = new JsonObject();
        o.addProperty("game", room.id);
        o.addProperty("seat", seat.token);
        return o;
    }

    private JsonArray decks() throws IOException {
        JsonArray a = new JsonArray();
        for (Map.Entry<String, String> e : server.decks().all().entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getKey());
            o.addProperty("text", e.getValue());
            o.addProperty("cards", DeckLists.parse(e.getKey(), e.getValue()).cards());
            a.add(o);
        }
        return a;
    }

    private JsonObject saveDeck(JsonObject b) throws IOException {
        String name = DeckLists.cleanName(str(b, "name"));
        if (name.isEmpty()) {
            throw new ApiError(400, "Give the deck a name.");
        }
        DeckLists.Parsed parsed = DeckLists.parse(name, str(b, "text"));
        if (!parsed.ok()) {
            throw new ApiError(400, String.join("\n", parsed.problems()));
        }
        // store the list as Forge reads it back, so saved lists are tidy and consistent
        server.decks().save(name, DeckLists.text(parsed.deck()));
        JsonObject o = ok();
        o.addProperty("name", name);
        o.addProperty("cards", parsed.cards());
        return o;
    }

    private JsonObject deleteDeck(String rawName) throws IOException {
        if (!server.decks().delete(DeckLists.cleanName(rawName))) {
            throw new ApiError(404, "No such deck.");
        }
        return ok();
    }

    private static JsonObject check(JsonObject b) {
        DeckLists.Parsed parsed = DeckLists.parse(null, str(b, "text"));
        JsonObject o = new JsonObject();
        o.addProperty("cards", parsed.cards());
        JsonArray problems = new JsonArray();
        parsed.problems().forEach(problems::add);
        o.add("problems", problems);
        return o;
    }

    /** An answer the browser should show the player. */
    static final class ApiError extends RuntimeException {
        final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
