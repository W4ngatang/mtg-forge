package forge.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.deck.DeckRecognizer;
import forge.deck.DeckRecognizer.Token;
import forge.deck.DeckRecognizer.TokenType;

/**
 * Deck lists as people paste and share them ("4 Brainstorm", one card per line), and the shared
 * deck lists the host saves on the server, one JSON file each in the data folder.
 */
final class DeckLists {
    static final int MAX_LINES = 400;
    static final int MAX_NAME = 40;
    private static final Gson GSON = new Gson();

    /** A pasted list: the deck if every line was understood, otherwise the lines that weren't. */
    record Parsed(Deck deck, int cards, List<String> problems) {
        boolean ok() {
            return problems.isEmpty() && cards > 0;
        }
    }

    static Parsed parse(String name, String text) {
        List<String> problems = new ArrayList<>();
        String[] lines = text == null ? new String[0] : text.split("\\R");
        if (lines.length > MAX_LINES) {
            problems.add("A list can have at most " + MAX_LINES + " lines.");
            return new Parsed(null, 0, problems);
        }
        Deck deck = new Deck(name == null || name.isBlank() ? "Pasted deck" : name);
        int cards = 0;
        for (Token t : new DeckRecognizer().parseCardList(lines)) {
            if (t.getCard() != null) {
                deck.getMain().add(t.getCard(), t.getQuantity());
                cards += t.getQuantity();
            } else if (t.getType() == TokenType.UNKNOWN_CARD || t.getType() == TokenType.UNSUPPORTED_CARD) {
                problems.add("Unknown card: " + t.getText());
            } else if (t.getType() == TokenType.UNKNOWN_TEXT) {
                problems.add("Not understood: " + t.getText());
            }
        }
        if (cards == 0 && problems.isEmpty()) {
            problems.add("The list has no cards.");
        }
        return new Parsed(deck, cards, problems);
    }

    /** The text form of a deck, "4 Brainstorm" per line: what export gives and import accepts. */
    static String text(Deck deck) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        deck.getMain().forEach(e -> counts.merge(e.getKey().getName(), e.getValue(), Integer::sum));
        StringBuilder sb = new StringBuilder();
        counts.forEach((name, n) -> sb.append(n).append(' ').append(name).append('\n'));
        return sb.toString();
    }

    // ---- saved lists -----------------------------------------------------------------------

    private final Path folder;

    DeckLists(Path folder) {
        this.folder = folder;
    }

    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("[^\\p{L}\\p{N} '_-]", "").trim();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).trim() : name;
    }

    private Path file(String name) {
        // letters and digits only, so a name can never reach outside the folder
        String lower = name.toLowerCase();
        String slug = lower.replaceAll("[^a-z0-9]+", "-");
        return folder.resolve(slug + "-" + Integer.toHexString(lower.hashCode()) + ".json");
    }

    synchronized Map<String, String> all() throws IOException {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                JsonObject o = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
                out.put(o.get("name").getAsString(), o.get("text").getAsString());
            }
        }
        return out;
    }

    synchronized String get(String name) throws IOException {
        Path p = file(name);
        if (!Files.exists(p)) {
            return null;
        }
        JsonObject o = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
        return o.get("name").getAsString().equalsIgnoreCase(name) ? o.get("text").getAsString() : null;
    }

    synchronized void save(String name, String text) throws IOException {
        Files.createDirectories(folder);
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("text", text);
        Files.writeString(file(name), GSON.toJson(o), StandardCharsets.UTF_8);
    }

    synchronized boolean delete(String name) throws IOException {
        return get(name) != null && Files.deleteIfExists(file(name));
    }
}
