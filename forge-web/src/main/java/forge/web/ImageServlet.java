package forge.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Card images: {@code /img?name=Brainstorm&set=ice&size=small}. Looks a card up on Scryfall once,
 * remembers where its images are, and redirects the browser to Scryfall's image servers. A board
 * shows dozens of cards and Scryfall's API asks for at most ten requests a second, so lookups are
 * spaced out here rather than made by every browser. A card Scryfall doesn't know gets a 404, and
 * the browser draws it as text.
 */
public class ImageServlet extends HttpServlet {
    private static final String API = "https://api.scryfall.com/cards/named?exact=";
    private static final long SPACING_MS = 110;
    private static final long FORGET_MISSES_MS = 6 * 60 * 60 * 1000;
    private static final int MAX_REMEMBERED = 50_000;

    private record Images(String small, String normal, long at) {
        boolean missing() {
            return small == null;
        }
    }

    private final Map<String, Images> known = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private long lastLookup;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String name = req.getParameter("name");
        String set = req.getParameter("set");
        if (name == null || name.isBlank() || name.length() > 150 || (set != null && !set.matches("[a-z0-9]{2,8}"))) {
            resp.sendError(400);
            return;
        }
        Images images = images(name, set);
        if (images.missing()) {
            resp.setHeader("Cache-Control", "max-age=3600");
            resp.sendError(404);
            return;
        }
        resp.setHeader("Cache-Control", "max-age=604800");
        resp.sendRedirect("normal".equals(req.getParameter("size")) ? images.normal() : images.small());
    }

    private Images images(String name, String set) {
        String key = name.toLowerCase() + "|" + (set == null ? "" : set);
        Images cached = known.get(key);
        if (cached != null && !(cached.missing() && System.currentTimeMillis() - cached.at() > FORGET_MISSES_MS)) {
            return cached;
        }
        Images found = lookUp(name, set);
        if (found.missing() && set != null) {
            found = lookUp(name, null); // Forge's printing may not be one Scryfall has; any printing will do
        }
        if (known.size() > MAX_REMEMBERED) {
            known.clear();
        }
        known.put(key, found);
        return found;
    }

    private Images lookUp(String name, String set) {
        waitYourTurn();
        String url = API + URLEncoder.encode(name, StandardCharsets.UTF_8) + (set == null ? "" : "&set=" + set);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "ForgeWeb/1.0 (https://github.com/Card-Forge/forge)")
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Images(null, null, System.currentTimeMillis());
            }
            JsonObject card = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject uris = card.has("image_uris") ? card.getAsJsonObject("image_uris")
                    : card.getAsJsonArray("card_faces").get(0).getAsJsonObject().getAsJsonObject("image_uris"); // double-faced
            return new Images(uris.get("small").getAsString(), uris.get("normal").getAsString(), System.currentTimeMillis());
        } catch (IOException | RuntimeException e) {
            return new Images(null, null, System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Images(null, null, System.currentTimeMillis());
        }
    }

    private synchronized void waitYourTurn() {
        long wait = lastLookup + SPACING_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastLookup = System.currentTimeMillis();
    }
}
