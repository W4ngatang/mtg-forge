package forge.web;

import java.net.URL;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
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
 * The web server: the browser app from {@code /web} on the classpath, and games over {@code /ws}.
 *
 * <p>Run: {@code java -Dforge.web.assets=<folder holding res/> -cp ... forge.web.WebServer [port]}.
 * The host password, needed to create games, comes from the {@code FORGE_WEB_PASSWORD} environment
 * variable; without it anyone who can reach the server can create games.
 */
public class WebServer {
    public static final String PASSWORD_ENV = "FORGE_WEB_PASSWORD";
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000;

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final String password;
    private Server jetty;

    public WebServer(String password) {
        this.password = password == null || password.isEmpty() ? null : password;
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
        context.addServlet(new ServletHolder("ws", new WebSocketServlet() {
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.getPolicy().setIdleTimeout(IDLE_TIMEOUT_MS);
                factory.setCreator((req, resp) -> new GameSocket(WebServer.this));
            }
        }), "/ws");
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

    GameRoom newRoom(WebFormat format, String playerName) {
        rooms.values().removeIf(GameRoom::isOver);
        GameRoom room = new GameRoom(format, playerName);
        rooms.put(room.id, room);
        return room;
    }

    GameRoom room(String id) {
        return id == null ? null : rooms.get(id);
    }

    boolean passwordMatches(String given) {
        if (password == null) {
            return true;
        }
        return given != null && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isEmpty()) {
            System.err.println("Warning: " + PASSWORD_ENV + " is not set, so anyone who can reach this server can create games.");
        }
        WebServer server = new WebServer(password);
        server.start(port);
        System.out.println("Forge web server on port " + server.port());
        server.jetty.join();
    }
}
