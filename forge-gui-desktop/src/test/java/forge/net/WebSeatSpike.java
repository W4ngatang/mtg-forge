package forge.net;

import static org.testng.Assert.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.StackItemView;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.Selection;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.player.GamePlayerUtil;
import forge.trackable.TrackableObject;

/**
 * Spike for the web server: a headless HostedMatch where one seat is a stand-in for a browser
 * player. The seat answers every prompt asynchronously through its game controller, the way a
 * WebSocket message would, and counts which GUI calls the engine makes. Not a regression test, and
 * named so the normal test run skips it: run it with -Dtest=WebSeatSpike to see the counts.
 */
public class WebSeatSpike {

    /** A seat that clicks OK or the first valid choice, from its own thread like a remote browser. */
    static class ScriptedSeat extends HeadlessNetworkGuiGame {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final ExecutorService answers = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "browser-stand-in");
            t.setDaemon(true);
            return t;
        });

        void count(String method) {
            calls.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
        }

        private void later(Runnable r) {
            answers.submit(() -> {
                try {
                    Thread.sleep(5);
                    if (getGameController() != null) {
                        r.run();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public void updateButtons(forge.game.player.PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
            count("updateButtons");
            if (enable1) {
                later(() -> getGameController().selectButtonOk());
            } else if (enable2) {
                later(() -> getGameController().selectButtonCancel());
            }
        }

        @Override
        public void setSelection(Selection selection) {
            super.setSelection(selection);
            count("setSelection" + (selection.offersStackItems() ? "(stack)" : selection.cards().isEmpty() ? "(none)" : "(cards)"));
            for (var card : selection.cards()) {
                if (!selection.chosen().contains(card)) {
                    later(() -> getGameController().selectCard(card, null, null));
                    return;
                }
            }
            for (TrackableObject target : selection.targets()) {
                if (target instanceof StackItemView item && !selection.chosen().contains(item)) {
                    later(() -> getGameController().selectStackItem(item));
                    return;
                }
            }
        }

        @Override public void showPromptMessage(forge.game.player.PlayerView p, String message, forge.game.card.CardView card) { count("showPromptMessage"); }
        @Override public boolean confirm(forge.game.card.CardView c, String q, boolean y, List<String> o) { count("confirm"); return super.confirm(c, q, y, o); }
        @Override public <T> List<T> getChoices(String m, int min, int max, List<T> ch, List<T> sel, forge.util.FSerializableFunction<T, String> d) { count("getChoices"); return super.getChoices(m, min, max, ch, sel, d); }
        @Override public <T> IGuiGame.OrderResult<T> order(String t, String top, int a, int b, List<T> s, List<T> d, forge.game.card.CardView r, boolean sb, boolean rem) { count("order"); return super.order(t, top, a, b, s, d, r, sb, rem); }
        @Override public boolean showConfirmDialog(String m, String t, String y, String n, boolean d) { count("showConfirmDialog"); return super.showConfirmDialog(m, t, y, n, d); }
        @Override public int showOptionDialog(String m, String t, forge.localinstance.skin.FSkinProp i, List<String> o, int d) { count("showOptionDialog"); return super.showOptionDialog(m, t, i, o, d); }
        @Override public String showInputDialog(String m, String t, forge.localinstance.skin.FSkinProp i, String in, List<String> o, boolean n) { count("showInputDialog"); return super.showInputDialog(m, t, i, in, o, n); }
        @Override public forge.game.spellability.SpellAbilityView getAbilityToPlay(forge.game.card.CardView h, List<forge.game.spellability.SpellAbilityView> a, forge.util.ITriggerEvent e) { count("getAbilityToPlay"); return super.getAbilityToPlay(h, a, e); }
        @Override public void message(String m, String t) { count("message"); }
        @Override public void setGameView(GameView gameView) { super.setGameView(gameView); count("setGameView"); }
        @Override public void updateCards(Iterable<forge.game.card.CardView> cards) { count("updateCards"); super.updateCards(cards); }
        @Override public void updateZones(Iterable<forge.player.PlayerZoneUpdate> zones) { count("updateZones"); super.updateZones(zones); }
        @Override public void updatePhase(boolean saveState) { count("updatePhase"); super.updatePhase(saveState); }
        @Override public void updateStack() { count("updateStack"); super.updateStack(); }
        @Override public void updateLives(Iterable<forge.game.player.PlayerView> l) { count("updateLives"); super.updateLives(l); }
        @Override public void showCombat() { count("showCombat"); }
        @Override public void finishGame() { count("finishGame"); }
    }

    private static Map<String, Integer> play(GameType variant, Deck humanDeck, Deck aiDeck, int games) throws InterruptedException {
        TestUtils.ensureFModelInitialized();
        Map<String, Integer> totals = new TreeMap<>();
        for (int g = 0; g < games; g++) {
            Set<GameType> variants = EnumSet.of(variant);
            ScriptedSeat seat = new ScriptedSeat();
            LobbyPlayer human = GamePlayerUtil.getGuiPlayer("Browser", 0, 0, false);
            RegisteredPlayer rpHuman = RegisteredPlayer.forVariants(2, variants, humanDeck, null, false, null, null).setPlayer(human);
            RegisteredPlayer rpAi = RegisteredPlayer.forVariants(2, variants, aiDeck, null, false, null, null).setPlayer(new LobbyPlayerAi("Computer", null));
            List<RegisteredPlayer> players = Lists.newArrayList(rpHuman, rpAi);
            Map<RegisteredPlayer, IGuiGame> guis = Maps.newHashMap();
            guis.put(rpHuman, seat);

            HostedMatch match = GuiBase.getInterface().hostMatch();
            match.startMatch(variant, variants, players, guis);
            long giveUp = System.currentTimeMillis() + 120_000;
            while ((match.getGame() == null || !match.getGame().isGameOver()) && System.currentTimeMillis() < giveUp) {
                Thread.sleep(50);
            }
            boolean over = match.getGame() != null && match.getGame().isGameOver();
            System.out.println("SPIKE " + variant + " game " + (g + 1) + ": " + (over
                    ? "finished on turn " + match.getGame().getPhaseHandler().getTurn() + ", " + match.getGame().getOutcome().getWinningLobbyPlayer() + " won"
                    : "DID NOT FINISH"));
            assertTrue(over, "game " + (g + 1) + " finished");
            seat.calls.forEach((k, v) -> totals.merge(k, v.get(), Integer::sum));
        }
        System.out.println("SPIKE " + variant + " GUI calls over " + games + " games: " + totals);
        return totals;
    }

    @Test(timeOut = 900_000)
    public void testDanDanSeatDrivenLikeABrowser() throws InterruptedException {
        TestUtils.ensureFModelInitialized();
        Deck dandan = GameType.DanDan.autoGenerateDeck(null);
        play(GameType.DanDan, dandan, dandan, 5);
    }

    @Test(timeOut = 900_000)
    public void testMomirSeatDrivenLikeABrowser() throws InterruptedException {
        TestUtils.ensureFModelInitialized();
        play(GameType.MomirBasic, GameType.MomirBasic.autoGenerateDeck(null), GameType.MomirBasic.autoGenerateDeck(null), 5);
    }
}
