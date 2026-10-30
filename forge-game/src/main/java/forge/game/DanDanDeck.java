package forge.game;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;

/**
 * The shared 80-card list for DanDan, a two-player variant where both players draw from one
 * library and share one graveyard. Every player is handed this list; the match only loads one
 * copy of it into the shared library (see {@link GameType#getSharedZones()}).
 */
public final class DanDanDeck {
    /** Card name to number of copies. Edit this list to try a different DanDan build. */
    public static final Map<String, Integer> CARDS = ImmutableMap.<String, Integer>builder()
            // Creatures
            .put("Dandân", 10)
            // Lands
            .put("Island", 20)
            .put("Halimar Depths", 2)
            .put("Lonely Sandbar", 2)
            .put("Remote Isle", 2)
            .put("Mystic Sanctuary", 2)
            .put("Svyelunite Temple", 2)
            // Card draw and selection
            .put("Accumulated Knowledge", 4)
            .put("Brainstorm", 4)
            .put("Mental Note", 2)
            .put("Predict", 2)
            .put("Telling Time", 2)
            .put("Chart a Course", 2)
            .put("Mystic Retrieval", 2)
            // Counterspells
            .put("Memory Lapse", 4)
            .put("Remove Soul", 4)
            // Interaction
            .put("Control Magic", 2)
            .put("Ray of Command", 2)
            .put("Capsize", 2)
            .put("Unsubstantiate", 2)
            // Island hate, so Dandân can attack or has to be sacrificed
            .put("Magical Hack", 2)
            .put("Crystal Spray", 2)
            .put("Vision Charm", 2)
            .build();

    private DanDanDeck() {
    }

    public static Deck build(RegisteredPlayer player) {
        Deck deck = new Deck("DanDan");
        CardPool main = deck.getMain();
        for (Map.Entry<String, Integer> entry : CARDS.entrySet()) {
            main.add(StaticData.instance().getCommonCards().getCard(entry.getKey()), entry.getValue());
        }
        return deck;
    }
}
