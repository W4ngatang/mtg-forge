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

    @BeforeClass(timeOut = 300_000)
    public void startServer() throws Exception {
        server = new WebServer(PASSWORD);
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
                if (p.get("id").getAsInt() != you && p.getAsJsonArray("hand").size() > 0) {
                    leaks.add("you=" + you + ", opponent " + p.get("id") + " hand: " + p.get("hand") + " | last log: " + lastLog(s));
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
                    int k = kind.equals("order") ? max : min + rnd.nextInt(Math.max(1, max - min + 1));
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < n; i++) order.add(i);
                    Collections.shuffle(order, rnd);
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
            Bot bot = connect("new=" + format + "&name=Bot&password=" + PASSWORD, 1000L * g + format.hashCode());
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
        return totals;
    }

    @Test(timeOut = 1_200_000)
    public void testDanDanGamesPlayThroughTheSocket() throws Exception {
        Map<String, Integer> seen = playGames("DANDAN", 4);
        assertTrue(seen.getOrDefault("act:handCard", 0) > 0, "the scripted player tried to play cards");
    }

    @Test(timeOut = 1_200_000)
    public void testMomirGamesPlayThroughTheSocket() throws Exception {
        Map<String, Integer> seen = playGames("MOMIR", 4);
        assertTrue(seen.getOrDefault("state:ownToken", 0) > 0, "the scripted player made a creature with Momir's avatar");
    }

    @Test(timeOut = 120_000)
    public void testWrongPasswordIsRefused() throws Exception {
        Bot bot = connect("new=DANDAN&password=nope", 1);
        String closed = bot.closedWith.get(30, TimeUnit.SECONDS);
        assertTrue(closed.startsWith("4000"), closed);
        assertFalse(bot.joined.isDone());
    }

    @Test(timeOut = 300_000)
    public void testReconnectingGetsTheGameBack() throws Exception {
        Bot first = connect("new=DANDAN&name=Phone&password=" + PASSWORD, 7);
        first.passive = true; // leave the opening hand undecided, as if the phone went to sleep
        JsonObject joined = first.joined.get(60, TimeUnit.SECONDS);
        long until = System.currentTimeMillis() + 60_000;
        // the first decision is a dialog (such as the computer revealing cards) or the mulligan buttons
        while (first.ask == null && first.buttons == null && System.currentTimeMillis() < until) Thread.sleep(50);
        boolean waitingOnDialog = first.ask != null;
        assertTrue(waitingOnDialog || first.buttons != null, "the game asked something");
        first.session.close();

        String game = joined.get("game").getAsString(), seat = joined.get("seat").getAsString();
        Bot wrongSeat = connect("game=" + game + "&seat=not-the-token", 8);
        assertTrue(wrongSeat.closedWith.get(30, TimeUnit.SECONDS).startsWith("4000"));

        Bot again = connect("game=" + game + "&seat=" + seat, 9);
        again.passive = true;
        until = System.currentTimeMillis() + 30_000;
        while ((again.state == null || (waitingOnDialog ? again.ask : again.buttons) == null) && System.currentTimeMillis() < until) Thread.sleep(50);
        assertNotNull(again.state, "the state was sent again");
        assertNotNull(waitingOnDialog ? again.ask : again.buttons, "the open decision was sent again");
        again.session.getRemote().sendString("{\"t\":\"concede\"}");
        assertTrue(again.over.get(60, TimeUnit.SECONDS).get("gameOver").getAsBoolean());
    }
}
