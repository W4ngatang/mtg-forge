package forge.web;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Plays whole games through the real server and socket with a scripted browser that casts
 * spells and answers every dialog with a random valid choice, checking along the way that no
 * hidden card reaches it.
 */
public class WebPlayTest {
    private static final String PASSWORD = "test-password";
    private WebServer server;
    private WebSocketClient client;

    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

    @BeforeClass(timeOut = 300_000)
    public void startServer() throws Exception {
        server = new WebServer(PASSWORD, java.nio.file.Files.createTempDirectory("forge-web-test"));
        server.start(0);
        client = new WebSocketClient();
        client.getPolicy().setMaxTextMessageSize(4 * 1024 * 1024);
        client.start();
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() throws Exception {
        if (client != null) client.stop();
        if (server != null) server.stop();
    }

    /** Calls the lobby API; with host, sends the host password. Returns the status and the JSON answer. */
    private Map.Entry<Integer, JsonElement> api(String method, String path, String body, boolean host) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(new URI("http://localhost:" + server.port() + "/api" + path))
                .method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody() : java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (host) b.header("X-Host-Password", PASSWORD);
        java.net.http.HttpResponse<String> r = http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        return Map.entry(r.statusCode(), JsonParser.parseString(r.body()));
    }

    private JsonObject createGame(String format, String vs, String deckJson) throws Exception {
        Map.Entry<Integer, JsonElement> r = api("POST", "/games",
                "{\"format\":\"" + format + "\",\"vs\":\"" + vs + "\",\"name\":\"Bot\"" + (deckJson == null ? "" : ",\"deck\":" + deckJson) + "}", true);
        assertEquals((int) r.getKey(), 200, r.getValue().toString());
        return r.getValue().getAsJsonObject();
    }

    private Bot playSeat(JsonObject seat, long seed) throws Exception {
        return connect("game=" + seat.get("game").getAsString() + "&seat=" + seat.get("seat").getAsString(), seed);
    }

    private Bot startGame(String format, long seed) throws Exception {
        return playSeat(createGame(format, "computer", null), seed);
    }

    private Bot connect(String query, long seed) throws Exception {
        Bot bot = new Bot(seed);
        client.connect(bot, new URI("ws://localhost:" + server.port() + "/ws?" + query)).get(30, TimeUnit.SECONDS);
        return bot;
    }

    /** A browser stand-in: acts once the server goes quiet, like a person reading the screen. */
    @WebSocket(maxTextMessageSize = 4 * 1024 * 1024)
    public static class Bot {
        final Random rnd;
        final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
        final CompletableFuture<JsonObject> joined = new CompletableFuture<>();
        final CompletableFuture<JsonObject> over = new CompletableFuture<>();
        final CompletableFuture<String> closedWith = new CompletableFuture<>();
        final Map<String, Integer> seen = Collections.synchronizedMap(new TreeMap<>());
        final List<String> leaks = Collections.synchronizedList(new ArrayList<>());
        final java.util.Deque<String> recent = new java.util.ArrayDeque<>();
        volatile boolean passive;
        /** Instead of playing randomly: use the command zone's ability (Momir's avatar) each turn, never cancel. */
        volatile boolean useAvatar;
        int avatarTriedOnTurn = -1;
        volatile Session session;
        volatile JsonObject state, selection, buttons, ask;
        volatile int actionsThisPrompt;
        ScheduledFuture<?> pending;

        Bot(long seed) {
            rnd = new Random(seed);
        }

        @OnWebSocketConnect public void onConnect(Session s) { session = s; }
        @OnWebSocketClose public void onClose(int code, String reason) { closedWith.complete(code + " " + reason); }

        @OnWebSocketMessage
        public synchronized void onMessage(String text) {
            JsonObject m = JsonParser.parseString(text).getAsJsonObject();
            String t = m.get("t").getAsString();
            count("msg:" + t);
            if (!t.equals("state")) {
                recent.addLast(text.length() > 300 ? text.substring(0, 300) : text);
                if (recent.size() > 12) recent.removeFirst();
            }
            switch (t) {
                case "joined" -> joined.complete(m);
                case "state" -> { state = m; checkHidden(m); if (m.get("gameOver").getAsBoolean()) over.complete(m); }
                case "selection" -> selection = m;
                case "buttons" -> { buttons = m; actionsThisPrompt = 0; }
                case "ask" -> { ask = m; count("ask:" + m.get("kind").getAsString()); }
                case "error" -> count("error:" + m.get("text").getAsString());
                case "gameOver" -> over.complete(state);
                default -> { }
            }
            if (!passive) {
                if (pending != null) pending.cancel(false);
                pending = clock.schedule(this::act, 25, TimeUnit.MILLISECONDS);
            }
        }

        void count(String key) {
            seen.merge(key, 1, Integer::sum);
        }

        /** Nothing the player may not see is in the state. */
        void checkHidden(JsonObject s) {
            int you = s.get("you").getAsInt();
            for (JsonElement pe : s.getAsJsonArray("players")) {
                JsonObject p = pe.getAsJsonObject();
                if (p.get("id").getAsInt() == you) {
                    for (JsonElement c : p.getAsJsonArray("battlefield")) {
                        if (c.getAsJsonObject().has("token")) {
                            count("state:ownToken");
                            break;
                        }
                    }
                }
                // A card in someone's hand is theirs. One shown under another player's control is shown
                // for the wrong reason (how the DanDan ownership bug leaked); an opponent's card shown
                // as theirs has been revealed, which Forge allows, so it's only counted.
                if (p.get("id").getAsInt() != you && p.get("handCount").getAsInt() > p.getAsJsonArray("hand").size()) {
                    count("state:opponentHandHidden");
                }
                for (JsonElement c : p.getAsJsonArray("hand")) {
                    JsonObject card = c.getAsJsonObject();
                    if (card.has("controller") && card.get("controller").getAsInt() != p.get("id").getAsInt()) {
                        leaks.add("you=" + you + ", " + p.get("id") + "'s hand shows a card controlled by " + card.get("controller")
                                + ": " + card + " | last log: " + lastLog(s));
                    } else if (p.get("id").getAsInt() != you) {
                        count("state:revealedOpponentHandCard");
                    }
                }
                for (String zone : List.of("battlefield", "graveyard", "exile", "command", "hand")) {
                    for (JsonElement c : p.getAsJsonArray(zone)) {
                        JsonObject card = c.getAsJsonObject();
                        if (card.has("hidden") && card.has("name")) {
                            leaks.add("named hidden card: " + card);
                        }
                    }
                }
            }
        }

        static String lastLog(JsonObject s) {
            JsonArray log = s.getAsJsonArray("log");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, log.size() - 8); i < log.size(); i++) sb.append(log.get(i).getAsString()).append(" / ");
            return sb.toString();
        }

        void send(JsonObject o) {
            try {
                session.getRemote().sendString(o.toString());
            } catch (Exception e) {
                // closed; the test notices through its futures
            }
        }

        void sendType(String t, Integer id) {
            JsonObject o = new JsonObject();
            o.addProperty("t", t);
            if (id != null) o.addProperty("id", id);
            send(o);
        }

        synchronized void act() {
            if (over.isDone() || session == null) return;
            // The engine doesn't repeat a prompt that hasn't changed (say, after a dialog was
            // dismissed), so look at the screen again later if nothing new arrives, as a person would.
            if (pending != null) pending.cancel(false);
            pending = clock.schedule(this::act, 400, TimeUnit.MILLISECONDS);
            if (ask != null) {
                answer(ask);
                ask = null;
                return;
            }
            JsonObject b = buttons;
            if (b == null) return;
            boolean okOn = b.getAsJsonObject("ok").get("enabled").getAsBoolean();
            boolean cancelOn = b.getAsJsonObject("cancel").get("enabled").getAsBoolean();
            if (useAvatar) {
                actAvatar(okOn);
                return;
            }
            // a selection that still needs picks (Brainstorm's two cards, say) always gets them
            JsonObject required = selection;
            if (required != null && chosenIds(required, "card").size() < required.get("min").getAsInt()) {
                List<Integer> left = ids(required.getAsJsonArray("cards"));
                left.removeAll(chosenIds(required, "card"));
                if (!left.isEmpty()) {
                    count("act:selectCard");
                    sendType("card", left.get(rnd.nextInt(left.size())));
                    return;
                }
            }
            actionsThisPrompt++;
            if (actionsThisPrompt <= 4) {
                JsonObject sel = selection;
                if (sel != null) {
                    List<Integer> cards = ids(sel.getAsJsonArray("cards"));
                    cards.removeAll(chosenIds(sel, "card"));
                    if (!cards.isEmpty() && (!okOn || rnd.nextDouble() < 0.6)) {
                        count("act:selectCard");
                        sendType("card", cards.get(rnd.nextInt(cards.size())));
                        return;
                    }
                    for (JsonElement te : sel.getAsJsonArray("targets")) {
                        JsonObject tgt = te.getAsJsonObject();
                        String kind = tgt.get("kind").getAsString();
                        if (!chosenIds(sel, kind).contains(tgt.get("id").getAsInt()) && (!okOn || rnd.nextDouble() < 0.6)) {
                            count("act:select" + kind);
                            sendType(kind, tgt.get("id").getAsInt());
                            return;
                        }
                    }
                }
                // try playing a card from hand, or an ability of a permanent or of Momir's avatar
                List<Integer> hand = myCards("hand");
                List<Integer> abilities = myCards("command");
                abilities.addAll(myCards("battlefield"));
                if (okOn && !abilities.isEmpty() && rnd.nextDouble() < 0.2) {
                    count("act:abilityCard");
                    sendType("card", abilities.get(rnd.nextInt(abilities.size())));
                    return;
                }
                if (okOn && !hand.isEmpty() && rnd.nextDouble() < 0.45) {
                    count("act:handCard");
                    sendType("card", hand.get(rnd.nextInt(hand.size())));
                    return;
                }
            }
            if (okOn) {
                count("act:ok");
                sendType("ok", null);
            } else if (cancelOn) {
                count("act:cancel");
                sendType("cancel", null);
            }
        }

        /** Activate the avatar in my main phase, then go along with whatever the activation asks. */
        void actAvatar(boolean okOn) {
            JsonObject s = state;
            JsonObject sel = selection;
            if (sel != null) {
                List<Integer> cards = ids(sel.getAsJsonArray("cards"));
                cards.removeAll(chosenIds(sel, "card"));
                if (!cards.isEmpty() && chosenIds(sel, "card").size() < Math.max(1, sel.get("min").getAsInt())) {
                    count("act:selectCard");
                    sendType("card", cards.get(0));
                    return;
                }
            }
            boolean myMain = s != null && s.get("activePlayer").getAsInt() == s.get("you").getAsInt()
                    && s.get("phase").getAsString().startsWith("MAIN") && s.getAsJsonArray("stack").isEmpty();
            List<Integer> avatar = myCards("command");
            if (myMain && !avatar.isEmpty() && avatarTriedOnTurn != s.get("turn").getAsInt()) {
                avatarTriedOnTurn = s.get("turn").getAsInt();
                count("act:abilityCard");
                sendType("card", avatar.get(0));
                return;
            }
            if (okOn) {
                count("act:ok");
                sendType("ok", null);
            }
        }

        List<Integer> myCards(String zone) {
            JsonObject s = state;
            List<Integer> out = new ArrayList<>();
            if (s == null) return out;
            for (JsonElement pe : s.getAsJsonArray("players")) {
                JsonObject p = pe.getAsJsonObject();
                if (p.get("id").getAsInt() == s.get("you").getAsInt()) {
                    for (JsonElement c : p.getAsJsonArray(zone)) out.add(c.getAsJsonObject().get("id").getAsInt());
                }
            }
            return out;
        }

        static List<Integer> ids(JsonArray a) {
            List<Integer> out = new ArrayList<>();
            a.forEach(e -> out.add(e.getAsInt()));
            return out;
        }

        static List<Integer> chosenIds(JsonObject sel, String kind) {
            List<Integer> out = new ArrayList<>();
            for (JsonElement e : sel.getAsJsonArray("chosen")) {
                if (e.getAsJsonObject().get("kind").getAsString().equals(kind)) out.add(e.getAsJsonObject().get("id").getAsInt());
            }
            return out;
        }

        void answer(JsonObject q) {
            JsonObject a = new JsonObject();
            a.addProperty("t", "answer");
            a.addProperty("id", q.get("id").getAsInt());
            String kind = q.get("kind").getAsString();
            int n = q.has("options") ? q.getAsJsonArray("options").size() : 0;
            switch (kind) {
                case "reveal" -> { }
                case "text" -> a.addProperty("value", q.get("numeric").getAsBoolean() ? "1"
                        : q.get("initial").getAsString().isEmpty() ? "Island" : q.get("initial").getAsString());
                case "amounts" -> {
                    int total = q.get("total").getAsInt();
                    boolean atLeastOne = q.get("atLeastOne").getAsBoolean();
                    int[] amounts = new int[n];
                    int left = total;
                    if (atLeastOne) for (int i = 0; i < n && left > 0; i++) { amounts[i] = 1; left--; }
                    amounts[rnd.nextInt(n)] += left;
                    JsonArray arr = new JsonArray();
                    for (int v : amounts) arr.add(v);
                    a.add("amounts", arr);
                }
                default -> {
                    int min = q.has("min") ? q.get("min").getAsInt() : 1;
                    int max = q.has("max") ? q.get("max").getAsInt() : 1;
                    int k = kind.equals("order") ? max : useAvatar ? Math.max(min, Math.min(1, max)) : min + rnd.nextInt(Math.max(1, max - min + 1));
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < n; i++) order.add(i);
                    if (!useAvatar) Collections.shuffle(order, rnd);
                    JsonArray arr = new JsonArray();
                    order.subList(0, Math.min(k, n)).forEach(arr::add);
                    a.add(kind.equals("order") ? "order" : "choice", arr);
                }
            }
            count("answered:" + kind);
            send(a);
        }
    }

    /** What a stuck game was doing, for the failure message. */
    private static String diagnose(Bot bot) {
        StringBuilder sb = new StringBuilder("\nseen: " + bot.seen + "\nlast messages:\n");
        synchronized (bot) {
            bot.recent.forEach(m -> sb.append("  ").append(m).append('\n'));
        }
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (e.getKey().getName().startsWith("Game")) {
                sb.append(e.getKey().getName()).append(":\n");
                for (StackTraceElement el : e.getValue()) {
                    if (el.getClassName().startsWith("forge")) sb.append("    at ").append(el).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Integer> playGames(String format, int games) throws Exception {
        Map<String, Integer> totals = new TreeMap<>();
        for (int g = 0; g < games; g++) {
            Bot bot = startGame(format, 1000L * g + format.hashCode());
            assertNotNull(bot.joined.get(60, TimeUnit.SECONDS));
            JsonObject end;
            try {
                end = bot.over.get(Integer.getInteger("webplay.gameMinutes", 6), TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError(format + " game " + (g + 1) + " didn't finish" + diagnose(bot));
            }
            assertTrue(end.get("gameOver").getAsBoolean());
            assertEquals(bot.leaks, List.of(), "hidden cards reached the browser: " + bot.leaks);
            System.out.println("WEBPLAY " + format + " game " + (g + 1) + ": turn " + end.get("turn") + ", " + end.get("winner") + " won");
            bot.seen.forEach((k, v) -> totals.merge(k, v, Integer::sum));
            bot.session.close();
        }
        System.out.println("WEBPLAY " + format + " over " + games + " games: " + totals);
        assertTrue(totals.getOrDefault("state:opponentHandHidden", 0) > 0, "the opponent's hand was kept hidden");
        return totals;
    }

    @Test(timeOut = 1_200_000)
    public void testDanDanGamesPlayThroughTheSocket() throws Exception {
        Map<String, Integer> seen = playGames("DANDAN", 4);
        assertTrue(seen.getOrDefault("act:handCard", 0) > 0, "the scripted player tried to play cards");
    }

    @Test(timeOut = 1_200_000)
    public void testMomirGamesPlayThroughTheSocket() throws Exception {
        playGames("MOMIR", 4);
    }

    @Test(timeOut = 1_200_000)
    public void testBasicLandGamesPlayThroughTheSocket() throws Exception {
        Map<String, Integer> seen = playGames("BASIC_LAND_GAME", 4);
        assertTrue(seen.getOrDefault("act:handCard", 0) > 0, "the scripted player played lands");
    }

    @Test(timeOut = 300_000)
    public void testMomirAvatarMakesACreature() throws Exception {
        Bot bot = startGame("MOMIR", 3);
        bot.useAvatar = true;
        assertNotNull(bot.joined.get(60, TimeUnit.SECONDS));
        long until = System.currentTimeMillis() + 180_000;
        while (bot.seen.getOrDefault("state:ownToken", 0) == 0 && !bot.over.isDone() && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
        assertTrue(bot.seen.getOrDefault("state:ownToken", 0) > 0, "Momir's avatar made a creature" + diagnose(bot));
        // Leave the game rather than concede: conceding is covered by the reconnect test, and once
        // on CI a concede right after the avatar didn't end the game (an open item in plans/web-play.md).
        bot.session.close();
    }

    @Test(timeOut = 120_000)
    public void testCreatingGamesAndDecksNeedsTheHostPassword() throws Exception {
        assertEquals((int) api("POST", "/games", "{\"format\":\"DANDAN\"}", false).getKey(), 403);
        assertEquals((int) api("GET", "/decks", null, false).getKey(), 403);
        assertEquals((int) api("PUT", "/decks", "{\"name\":\"x\",\"text\":\"40 Island\"}", false).getKey(), 403);
        assertFalse(api("GET", "/session", null, false).getValue().getAsJsonObject().get("host").getAsBoolean());
        assertTrue(api("GET", "/session", null, true).getValue().getAsJsonObject().get("host").getAsBoolean());
        Bot bot = connect("game=nope&seat=nope", 1);
        assertTrue(bot.closedWith.get(30, TimeUnit.SECONDS).startsWith("4000"));
        assertFalse(bot.joined.isDone());
    }

    @Test(timeOut = 900_000)
    public void testFriendsPlayEachOtherFromAnInviteLink() throws Exception {
        JsonObject host = createGame("DANDAN", "friend", null);
        String game = host.get("game").getAsString(), invite = host.get("invite").getAsString();
        Bot hostBot = playSeat(host, 21);
        hostBot.passive = true;
        until(() -> hostBot.seen.containsKey("msg:waiting"), 30_000);
        assertEquals((int) api("GET", "/games/" + game + "?invite=wrong", null, false).getKey(), 404);
        JsonObject info = api("GET", "/games/" + game + "?invite=" + invite, null, false).getValue().getAsJsonObject();
        assertEquals(info.get("host").getAsString(), "Bot");

        // a friend joins without the host password; the invite then can't be used again
        Map.Entry<Integer, JsonElement> joined = api("POST", "/games/" + game + "/join", "{\"invite\":\"" + invite + "\",\"name\":\"Friend\"}", false);
        assertEquals((int) joined.getKey(), 200);
        assertEquals((int) api("POST", "/games/" + game + "/join", "{\"invite\":\"" + invite + "\",\"name\":\"Late\"}", false).getKey(), 404);
        Bot friendBot = playSeat(joined.getValue().getAsJsonObject(), 22);
        hostBot.passive = false;
        hostBot.act();

        JsonObject end;
        try {
            end = hostBot.over.get(Integer.getInteger("webplay.gameMinutes", 6), TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the friend game didn't finish" + diagnose(hostBot) + diagnose(friendBot));
        }
        assertTrue(end.get("gameOver").getAsBoolean());
        friendBot.over.get(60, TimeUnit.SECONDS);
        assertEquals(hostBot.leaks, List.of(), "hidden cards reached the host: " + hostBot.leaks);
        assertEquals(friendBot.leaks, List.of(), "hidden cards reached the friend: " + friendBot.leaks);
        assertTrue(hostBot.state.get("you").getAsInt() != friendBot.state.get("you").getAsInt(), "each sees the game as their own seat");
        System.out.println("WEBPLAY friend game: turn " + end.get("turn") + ", " + end.get("winner") + " won");
    }

    @Test(timeOut = 300_000)
    public void testSharedDeckListsAreCheckedSavedAndUsed() throws Exception {
        JsonObject bad = api("POST", "/decks/check", "{\"text\":\"4 Brainstorm\\n2 Not A Real Card\"}", false).getValue().getAsJsonObject();
        assertEquals(bad.getAsJsonArray("problems").size(), 1, bad.toString());
        assertEquals((int) api("PUT", "/decks", "{\"name\":\"Bad\",\"text\":\"2 Not A Real Card\"}", true).getKey(), 400);

        String list = "16 Dandan\\n24 Island";
        assertEquals((int) api("PUT", "/decks", "{\"name\":\"Forty Fish\",\"text\":\"" + list + "\"}", true).getKey(), 200);
        JsonElement saved = api("GET", "/decks", null, true).getValue();
        assertTrue(saved.toString().contains("Forty Fish") && saved.toString().contains("16 Dand"), saved.toString());

        // a DanDan game with it: the shared library starts from its 40 cards, not the standard 80
        Bot bot = playSeat(createGame("DANDAN", "computer", "{\"kind\":\"saved\",\"name\":\"Forty Fish\"}"), 31);
        bot.passive = true;
        // the first state can come before the library is filled
        until(() -> bot.state != null && bot.state.getAsJsonArray("players").size() == 2
                && bot.state.getAsJsonArray("players").get(0).getAsJsonObject().get("libraryCount").getAsInt() > 0, 60_000);
        int library = bot.state.getAsJsonArray("players").get(0).getAsJsonObject().get("libraryCount").getAsInt();
        assertTrue(library <= 40 && library > 0, "library of " + library);
        bot.session.close();

        assertEquals((int) api("DELETE", "/decks?name=Forty%20Fish", null, true).getKey(), 200);
        assertFalse(api("GET", "/decks", null, true).getValue().toString().contains("Forty Fish"));
    }

    private static void until(java.util.function.BooleanSupplier condition, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "timed out waiting");
    }

    @Test(timeOut = 300_000)
    public void testReconnectingGetsTheGameBack() throws Exception {
        JsonObject created = createGame("DANDAN", "computer", null);
        Bot first = playSeat(created, 7);
        first.passive = true; // leave the opening hand undecided, as if the phone went to sleep
        first.joined.get(60, TimeUnit.SECONDS);
        long until = System.currentTimeMillis() + 60_000;
        // the first decision is a dialog (such as the computer revealing cards) or the mulligan buttons
        while (first.ask == null && first.buttons == null && System.currentTimeMillis() < until) Thread.sleep(50);
        boolean waitingOnDialog = first.ask != null;
        assertTrue(waitingOnDialog || first.buttons != null, "the game asked something");
        first.session.close();

        String game = created.get("game").getAsString(), seat = created.get("seat").getAsString();
        Bot wrongSeat = connect("game=" + game + "&seat=not-the-token", 8);
        assertTrue(wrongSeat.closedWith.get(30, TimeUnit.SECONDS).startsWith("4000"));

        Bot again = connect("game=" + game + "&seat=" + seat, 9);
        again.passive = true;
        until = System.currentTimeMillis() + 30_000;
        while ((again.state == null || (again.ask == null && again.buttons == null)) && System.currentTimeMillis() < until) Thread.sleep(50);
        assertNotNull(again.state, "the state was sent again");
        assertTrue(again.ask != null || again.buttons != null, "the open decision was sent again" + diagnose(first) + diagnose(again));
        again.session.getRemote().sendString("{\"t\":\"concede\"}");
        assertTrue(again.over.get(60, TimeUnit.SECONDS).get("gameOver").getAsBoolean());
    }
}
