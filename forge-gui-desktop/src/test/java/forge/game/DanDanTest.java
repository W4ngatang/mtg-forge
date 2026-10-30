package forge.game;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * DanDan: both players draw from one library and share one graveyard, and a player owns the
 * cards they draw.
 */
public class DanDanTest extends AITest {

    private static GameRules danDanRules() {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.DanDan);
        return rules;
    }

    private Game initDanDanGame() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p1", null)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p2", null)));
        GameRules rules = danDanRules();
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        Match.shareZones(game);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(1));
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    @Test
    public void testPlayersShareLibraryAndGraveyardButNotHandOrBattlefield() {
        Game game = initDanDanGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);

        assertSame(p1.getZone(ZoneType.Library), p2.getZone(ZoneType.Library));
        assertSame(p1.getZone(ZoneType.Graveyard), p2.getZone(ZoneType.Graveyard));
        assertNotSame(p1.getZone(ZoneType.Hand), p2.getZone(ZoneType.Hand));
        assertNotSame(p1.getZone(ZoneType.Battlefield), p2.getZone(ZoneType.Battlefield));
        assertNotSame(p1.getZone(ZoneType.Exile), p2.getZone(ZoneType.Exile));
    }

    @Test
    public void testSharedLibraryIsCountedOnce() {
        Game game = initDanDanGame();
        Player p1 = game.getPlayers().get(0);
        fillLibrary(p1, 10);

        assertEquals(game.getCardsIn(ZoneType.Library).size(), 10);
    }

    @Test
    public void testDrawingFromSharedLibraryMakesTheDrawerTheOwner() {
        Game game = initDanDanGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        addCardToZone("Island", p1, ZoneType.Library);

        CardCollectionView drawn = p2.drawCards(1);

        assertEquals(drawn.size(), 1);
        Card island = drawn.get(0);
        assertTrue(p2.getZone(ZoneType.Hand).contains(island));
        assertSame(island.getOwner(), p2);
        assertTrue(p1.getZone(ZoneType.Library).isEmpty());
    }

    @Test
    public void testSharedGraveyardCardsCountAsEachPlayersOwn() {
        Game game = initDanDanGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        Card brainstorm = addCardToZone("Brainstorm", p1, ZoneType.Graveyard);

        for (Player p : List.of(p1, p2)) {
            assertTrue(brainstorm.isValid("Card.YouOwn", p, null, null), p + " owns it");
            assertTrue(brainstorm.isValid("Card.YouCtrl", p, null, null), p + " controls it");
            assertFalse(brainstorm.isValid("Card.OppOwn", p, null, null), p + "'s opponent owns it");
        }
    }

    @Test
    public void testReturningFromSharedGraveyardGoesToThePlayerWhoseEffectMovedIt() {
        Game game = initDanDanGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        Card brainstorm = addCardToZone("Brainstorm", p1, ZoneType.Graveyard);
        Card retrieval = addCardToZone("Mystic Retrieval", p2, ZoneType.Hand);
        SpellAbility sa = retrieval.getFirstSpellAbility();
        sa.setActivatingPlayer(p2);

        Card returned = game.getAction().moveToHand(brainstorm, sa);

        assertTrue(p2.getZone(ZoneType.Hand).contains(returned));
        assertSame(returned.getOwner(), p2);
    }

    @Test
    public void testCardsOutsideSharedZonesKeepTheirOwner() {
        Game game = initDanDanGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        Card island = addCard("Island", p2);
        Card capsize = addCardToZone("Capsize", p1, ZoneType.Hand);
        SpellAbility sa = capsize.getFirstSpellAbility();
        sa.setActivatingPlayer(p1);

        Card returned = game.getAction().moveToHand(island, sa);

        assertTrue(p2.getZone(ZoneType.Hand).contains(returned));
        assertSame(returned.getOwner(), p2);
    }

    @Test(timeOut = 300000)
    public void testAiGamePlaysOutWithOneEightyCardDeck() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(DanDanDeck.build(null)).setPlayer(new LobbyPlayerAi("p1", null)));
        players.add(new RegisteredPlayer(DanDanDeck.build(null)).setPlayer(new LobbyPlayerAi("p2", null)));
        Match match = new Match(danDanRules(), players, "Test");
        Game game = match.createGame();

        match.startGame(game);

        assertTrue(game.isGameOver());
        // the loser leaves getPlayers(), so look through every registered player's zones, and count
        // only the deck's own cards since effects can leave rules cards in the command zone
        Set<Card> deckCards = new HashSet<>();
        for (Player p : game.getRegisteredPlayers()) {
            for (ZoneType z : List.of(ZoneType.Library, ZoneType.Graveyard, ZoneType.Hand, ZoneType.Battlefield, ZoneType.Exile)) {
                for (Card c : p.getZone(z).getCards(false)) {
                    if (!c.isToken() && DanDanDeck.CARDS.containsKey(c.getName())) {
                        deckCards.add(c);
                    }
                }
            }
        }
        deckCards.addAll(game.getStackZone().getCards());
        assertEquals(deckCards.size(), 80);
    }
}
