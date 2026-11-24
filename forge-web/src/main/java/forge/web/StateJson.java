package forge.web;

import java.util.List;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;

/**
 * Game state as one JSON object, as one player is allowed to see it. Cards the player may not
 * see (an opponent's hand, every library, face-down cards) are sent as counts or as hidden
 * placeholders, never by name: anything in this object reaches the browser.
 */
final class StateJson {
    private static final byte[] MANA_COLORS = { MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN, MagicColor.COLORLESS };
    private static final String[] MANA_LETTERS = { "W", "U", "B", "R", "G", "C" };
    private static final int LOG_LINES = 30;

    private StateJson() {
    }

    static JsonObject state(GameView game, PlayerView you, Predicate<CardView> mayView) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "state");
        o.addProperty("you", you == null ? -1 : you.getId());
        o.addProperty("turn", game.getTurn());
        o.addProperty("phase", game.getPhase() == null ? "" : game.getPhase().name());
        o.addProperty("activePlayer", game.getPlayerTurn() == null ? -1 : game.getPlayerTurn().getId());
        o.addProperty("gameOver", game.isGameOver());
        if (game.isGameOver()) {
            o.addProperty("winner", game.getWinningPlayerName());
        }

        JsonArray players = new JsonArray();
        for (PlayerView p : game.getPlayers()) {
            players.add(player(p, mayView));
        }
        o.add("players", players);

        JsonArray stack = new JsonArray();
        for (StackItemView item : game.getStack()) {
            stack.add(stackItem(item, mayView));
        }
        o.add("stack", stack);
        o.add("combat", combat(game.getCombat()));

        JsonArray log = new JsonArray();
        if (game.getGameLog() != null) {
            List<GameLogEntry> entries = game.getGameLog().getLogEntries(null); // newest first
            for (int i = Math.min(LOG_LINES, entries.size()) - 1; i >= 0; i--) {
                log.add(entries.get(i).message());
            }
        }
        o.add("log", log);
        return o;
    }

    private static JsonObject player(PlayerView p, Predicate<CardView> mayView) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.getId());
        o.addProperty("name", p.getName());
        o.addProperty("life", p.getLife());
        o.addProperty("poison", p.getCounters(CounterEnumType.POISON));
        o.addProperty("ai", p.isAI());
        o.addProperty("priority", p.getHasPriority());
        o.addProperty("lost", p.getHasLost());
        JsonObject mana = new JsonObject();
        for (int i = 0; i < MANA_COLORS.length; i++) {
            int amount = p.getMana(MANA_COLORS[i]);
            if (amount > 0) {
                mana.addProperty(MANA_LETTERS[i], amount);
            }
        }
        o.add("mana", mana);
        o.addProperty("libraryCount", p.getZoneSize(ZoneType.Library));
        o.addProperty("handCount", p.getZoneSize(ZoneType.Hand));
        o.add("hand", cards(p.getHand(), mayView, true));
        o.add("battlefield", cards(p.getBattlefield(), mayView, false));
        o.add("graveyard", cards(p.getGraveyard(), mayView, false));
        o.add("exile", cards(p.getExile(), mayView, false));
        o.add("command", cards(p.getCommand(), mayView, false));
        return o;
    }

    /** With skipHidden, cards the player may not see are left out (the hand count says how many). */
    private static JsonArray cards(Iterable<CardView> cards, Predicate<CardView> mayView, boolean skipHidden) {
        JsonArray a = new JsonArray();
        if (cards == null) {
            return a;
        }
        for (CardView c : cards) {
            boolean visible = mayView.test(c);
            if (visible || !skipHidden) {
                a.add(card(c, visible));
            }
        }
        return a;
    }

    static JsonObject card(CardView c, boolean visible) {
        JsonObject o = new JsonObject();
        if (!visible) {
            o.addProperty("id", c.getHiddenId());
            o.addProperty("hidden", true);
            o.addProperty("tapped", c.isTapped());
            return o;
        }
        CardStateView s = c.getCurrentState();
        o.addProperty("id", c.getId());
        o.addProperty("name", s.getName());
        o.addProperty("set", s.getSetCode());
        o.addProperty("type", s.getType().toString());
        o.addProperty("cost", s.getManaCost() == null ? "" : s.getManaCost().getSimpleString());
        o.addProperty("text", c.getText());
        if (s.isCreature()) {
            o.addProperty("power", s.getPower());
            o.addProperty("toughness", s.getToughness());
        }
        o.addProperty("tapped", c.isTapped());
        if (c.isSick()) o.addProperty("sick", true);
        if (c.isToken()) o.addProperty("token", true);
        if (c.isFaceDown()) o.addProperty("faceDown", true);
        if (c.getDamage() > 0) o.addProperty("damage", c.getDamage());
        if (c.getController() != null) o.addProperty("controller", c.getController().getId());
        if (c.getOwner() != null) o.addProperty("owner", c.getOwner().getId());
        if (c.getAttachedTo() != null) o.addProperty("attachedTo", c.getAttachedTo().getId());
        if (c.getCounters() != null && !c.getCounters().isEmpty()) {
            JsonObject counters = new JsonObject();
            for (CounterType ct : c.getCounters().elementSet()) {
                counters.addProperty(ct.getName(), c.getCounters().count(ct));
            }
            o.add("counters", counters);
        }
        return o;
    }

    private static JsonObject stackItem(StackItemView item, Predicate<CardView> mayView) {
        JsonObject o = new JsonObject();
        o.addProperty("id", item.getId());
        o.addProperty("text", item.getText());
        if (item.getSourceCard() != null) {
            o.add("source", card(item.getSourceCard(), mayView.test(item.getSourceCard())));
        }
        if (item.getActivatingPlayer() != null) {
            o.addProperty("activator", item.getActivatingPlayer().getId());
        }
        return o;
    }

    private static JsonArray combat(CombatView combat) {
        JsonArray a = new JsonArray();
        if (combat == null) {
            return a;
        }
        for (CardView attacker : combat.getAttackers()) {
            JsonObject o = new JsonObject();
            o.addProperty("attacker", attacker.getId());
            GameEntityView defender = combat.getDefender(attacker);
            if (defender != null) {
                o.addProperty("defender", defender.getId());
            }
            JsonArray blockers = new JsonArray();
            if (combat.getBlockers(attacker) != null) {
                for (CardView b : combat.getBlockers(attacker)) {
                    blockers.add(b.getId());
                }
            }
            o.add("blockers", blockers);
            a.add(o);
        }
        return a;
    }
}
