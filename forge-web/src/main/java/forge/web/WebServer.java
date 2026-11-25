package forge.web;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * The web server: the browser app from {@code /web} on the classpath, the lobby under {@code /api}
 * (see {@link ApiServlet}) and games over {@code /ws}.
 *
 * <p>Run: {@code java -Dforge.web.assets=<folder holding res/> -jar forge-web-...-jar-with-dependencies.jar [port]}.
 * The host password, needed to create games and manage saved decks, comes from the
 * {@code FORGE_WEB_PASSWORD} environment variable; without it anyone who can reach the server can.
 * The site password, needed to see anything at all, comes from {@code FORGE_SITE_PASSWORD} (see {@link SiteGate}).
 * Saved decks go in the folder named by {@code -Dforge.web.data} (default {@code forge-web-data}).
 */
public class WebServer {
    public static final String PASSWORD_ENV = "FORGE_WEB_PASSWORD";
    public static final String DATA_PROPERTY = "forge.web.data";
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000;
    /** Games running at once; each holds a game thread and the computer's thinking. */
    static final int MAX_GAMES = 12;
    private static final int MAX_NAME = 20;

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final String password;
    private final DeckLists decks;
    private final SiteGate gate = new SiteGate(System.getenv(SiteGate.PASSWORD_ENV));
    private Server jetty;

    public WebServer(String password) {
        this(password, Paths.get(System.getProperty(DATA_PROPERTY, "forge-web-data")));
    }

    public WebServer(String password, Path dataFolder) {
        // a space or newline copied along with the secret would otherwise make the right password fail
        String trimmed = password == null ? "" : password.strip();
        this.password = trimmed.isEmpty() ? null : trimmed;
        this.decks = new DeckLists(dataFolder.resolve("decks"));
    }

    private static boolean forgeLoaded;

    /** Loads Forge's card database once per process; takes a few seconds. */
    public static synchronized void initForge() {
        if (forgeLoaded) {
            return;
        }
        forgeLoaded = true;
        GuiBase.setInterface(new WebGuiBase());
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
            return null;
        });
    }

    /** Starts listening; port 0 picks a free one (see {@link #port()}). */
    public void start(int port) throws Exception {
        initForge();
        jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setPort(port);
        jetty.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        URL web = WebServer.class.getResource("/web/");
        if (web != null) {
            context.setResourceBase(web.toExternalForm());
        }
        context.addFilter(new org.eclipse.jetty.servlet.FilterHolder(gate), "/*", java.util.EnumSet.of(javax.servlet.DispatcherType.REQUEST));
        context.addServlet(new ServletHolder("ws", new WebSocketServlet() {
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.getPolicy().setIdleTimeout(IDLE_TIMEOUT_MS);
                factory.setCreator((req, resp) -> new GameSocket(WebServer.this));
            }
        }), "/ws");
        context.addServlet(new ServletHolder("api", new ApiServlet(this)), "/api/*");
        context.addServlet(new ServletHolder("img", new ImageServlet()), "/img");
        context.addServlet(new ServletHolder("static", DefaultServlet.class), "/");
        jetty.setHandler(context);
        jetty.start();
    }

    public int port() {
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }

    public void stop() throws Exception {
        if (jetty != null) {
            jetty.stop();
        }
    }

    synchronized GameRoom newRoom(WebFormat format, String hostName, forge.deck.Deck deck, boolean againstFriend) {
        rooms.values().removeIf(GameRoom::isStale);
        if (rooms.size() >= MAX_GAMES) {
            throw new ApiServlet.ApiError(503, "The server is running as many games as it can. Try again later.");
        }
        GameRoom room = againstFriend ? GameRoom.againstFriend(format, hostName, deck) : GameRoom.againstComputer(format, hostName, deck);
        rooms.put(room.id, room);
        return room;
    }

    SiteGate gate() {
        return gate;
    }

    DeckLists decks() {
        return decks;
    }

    static String playerName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME).trim();
        }
        return name.isEmpty() ? "Player" : name;
    }

    GameRoom room(String id) {
        return id == null ? null : rooms.get(id);
    }

    boolean passwordMatches(String given) {
        if (password == null) {
            return true;
        }
        return given != null && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), given.strip().getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isEmpty()) {
            System.err.println("Warning: " + PASSWORD_ENV + " is not set, so anyone who can reach this server can create games.");
        }
        if (System.getenv(SiteGate.PASSWORD_ENV) == null || System.getenv(SiteGate.PASSWORD_ENV).isEmpty()) {
            System.err.println("Note: " + SiteGate.PASSWORD_ENV + " is not set, so the site is open to anyone who can reach it.");
        }
        WebServer server = new WebServer(password);
        server.start(port);
        System.out.println("Forge web server on port " + server.port());
        server.jetty.join();
    }
}
