package forge.game;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.StaticData;
import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The Basic Land Game: each basic does something when it enters, Islands in hand can counter an
 * opponent's land, and you win with all five basic types or five of one.
 */
public class BasicLandGameTest extends AITest {

    /** A game where both players have the rules card in the command zone, on p1's main phase. */
    private Game game() {
        Game game = initAndCreateGame();
        for (Player p : game.getPlayers()) {
            // the rules card is a variant card, like Momir's avatar, so it isn't among the common cards
            Card rules = Card.fromPaperCard(StaticData.instance().getVariantCards().getCard("Basic Land Game"), p);
            rules.setGameTimestamp(game.getNextTimestamp());
            p.getZone(ZoneType.Command).add(rules);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private Player p1(Game game) {
        return game.getPhaseHandler().getPlayerTurn();
    }

    private Player p2(Game game) {
        return p1(game).getSingleOpponent();
    }

    /** Puts a land from hand onto the battlefield and lets its abilities resolve. */
    private Card play(Game game, String land, Player p) {
        Card c = addCardToZone(land, p, ZoneType.Hand);
        Card played = game.getAction().moveToPlay(c, p, null, null);
        // resolving one ability can trigger another (Plains bringing a land back), so go until none are left
        for (int i = 0; i < 10; i++) {
            game.getAction().checkStateEffects(true);
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (game.getStack().isEmpty() || game.isGameOver()) {
                break;
            }
            playUntilStackClear(game);
        }
        return played;
    }

    private static int count(Player p, ZoneType zone, String name) {
        return (int) p.getCardsIn(zone).stream().filter(c -> c.getName().equals(name)).count();
    }

    @Test
    public void testIslandDrawsACard() {
        Game game = game();
        Player p = p1(game);
        fillLibrary(p, 5);
        int hand = p.getCardsIn(ZoneType.Hand).size();

        play(game, "Island", p);

        assertEquals(p.getCardsIn(ZoneType.Hand).size(), hand + 1);
    }

    @Test
    public void testForestReturnsALandFromYourGraveyard() {
        Game game = game();
        Player p = p1(game);
        addCardToZone("Mountain", p, ZoneType.Graveyard);

        play(game, "Forest", p);

        assertEquals(count(p, ZoneType.Hand, "Mountain"), 1);
        assertEquals(count(p, ZoneType.Graveyard, "Mountain"), 0);
    }

    @Test
    public void testSwampMakesTheOpponentDiscardALand() {
        Game game = game();
        Player p = p1(game), opp = p2(game);
        addCardToZone("Plains", opp, ZoneType.Hand);
        addCardToZone("Forest", opp, ZoneType.Hand);

        play(game, "Swamp", p);

        assertEquals(opp.getCardsIn(ZoneType.Hand).size(), 1);
        assertEquals(opp.getCardsIn(ZoneType.Graveyard).size(), 1);
    }

    @Test
    public void testMountainDestroysAnOpponentsLand() {
        Game game = game();
        Player p = p1(game), opp = p2(game);
        addCard("Plains", opp);

        play(game, "Mountain", p);

        assertEquals(count(opp, ZoneType.Battlefield, "Plains"), 0);
        assertEquals(count(opp, ZoneType.Graveyard, "Plains"), 1);
    }

    @Test
    public void testPlainsReusesAnotherLandsEffect() {
        Game game = game();
        Player p = p1(game), opp = p2(game);
        addCard("Mountain", p);
        addCard("Island", opp);
        addCard("Forest", opp);

        play(game, "Plains", p);

        // the Mountain left and came back, so it destroyed a land again
        assertEquals(count(p, ZoneType.Battlefield, "Mountain"), 1);
        assertEquals(opp.getCardsIn(ZoneType.Battlefield).size(), 1);
    }

    @Test
    public void testAllFiveBasicTypesWin() {
        Game game = game();
        Player p = p1(game);
        for (String land : List.of("Plains", "Island", "Swamp", "Mountain")) {
            addCard(land, p);
        }
        game.getAction().checkStateEffects(true);
        assertFalse(game.isGameOver());

        addCard("Forest", p);
        game.getAction().checkStateEffects(true);

        assertTrue(game.isGameOver());
        assertTrue(game.getOutcome().isWinner(p.getLobbyPlayer()));
    }

    @Test
    public void testFiveOfOneTypeWins() {
        Game game = game();
        Player p = p2(game);
        addCards("Swamp", 4, p);
        game.getAction().checkStateEffects(true);
        assertFalse(game.isGameOver());

        addCard("Swamp", p);
        game.getAction().checkStateEffects(true);

        assertTrue(game.isGameOver());
        assertTrue(game.getOutcome().isWinner(p.getLobbyPlayer()));
    }

    @Test
    public void testIslandInHandCountersAnOpposingLand() {
        Game game = game();
        Player p = p1(game), opp = p2(game);
        Card oppPlains = addCard("Plains", opp);
        Card island = addCardToZone("Island", opp, ZoneType.Hand);
        addCardToZone("Swamp", opp, ZoneType.Hand);

        // p plays a Mountain; its "destroy target land" goes on the stack aimed at the Plains
        Card mountain = game.getAction().moveToPlay(addCardToZone("Mountain", p, ZoneType.Hand), p, null, null);
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertEquals(game.getStack().size(), 1);
        SpellAbility destroy = game.getStack().peekAbility();

        // the opponent discards Island and Swamp to counter it
        SpellAbility counter = island.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.Counter).findFirst().orElse(null);
        assertNotNull(counter, "Islands in hand can counter");
        counter.setActivatingPlayer(opp);
        counter.getTargets().add(destroy);
        assertTrue(ComputerUtil.playStack(counter, opp, game), "the counter was played");
        playUntilStackClear(game);
        game.getAction().checkStateEffects(true);

        assertTrue(opp.getCardsIn(ZoneType.Battlefield).contains(oppPlains), "the Plains survived");
        assertFalse(p.getCardsIn(ZoneType.Battlefield).contains(mountain), "the countered Mountain is gone");
        assertEquals(count(p, ZoneType.Graveyard, "Mountain"), 1);
        assertEquals(opp.getCardsIn(ZoneType.Hand).size(), 0, "Island and the other land were discarded");
        assertEquals(opp.getCardsIn(ZoneType.Graveyard).size(), 2);
    }

    @Test(timeOut = 300000)
    public void testAiGameStartsWithFiveCardsAndNoMulligansAndEndsInAWin() {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.BasicLandGame);
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (String name : List.of("p1", "p2")) {
            players.add(RegisteredPlayer.forVariants(2, java.util.EnumSet.of(GameType.BasicLandGame),
                    GameType.BasicLandGame.autoGenerateDeck(null), null, false, null, null).setPlayer(new LobbyPlayerAi(name, null)));
        }
        Match match = new Match(rules, players, "Test");
        Game game = match.createGame();
        List<Integer> openingHands = Lists.newArrayList();
        game.subscribeToEvents(new Object() {
            @com.google.common.eventbus.Subscribe
            public void onTurn(forge.game.event.GameEventTurnBegan ev) {
                if (openingHands.isEmpty()) {
                    for (Player p : game.getPlayers()) {
                        openingHands.add(p.getCardsIn(ZoneType.Hand).size() + p.getCardsIn(ZoneType.Battlefield).size());
                    }
                }
            }
        });

        match.startGame(game);

        assertEquals(openingHands, List.of(5, 5), "both kept five cards");
        assertTrue(game.isGameOver());
        assertNotNull(game.getOutcome().getWinningLobbyPlayer(), "someone won through the rules card, not a draw");
        for (Player p : game.getRegisteredPlayers()) {
            assertTrue(p.getCardsIn(ZoneType.Command).stream().anyMatch(c -> c.getName().equals("Basic Land Game")));
        }
    }
}
