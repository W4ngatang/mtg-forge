package forge.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.player.GamePlayerUtil;

/**
 * One 1v1 game. Against the computer it starts at once; against a friend it starts when the friend
 * joins through the invite link. Each browser seat has its own token, which is all a browser needs to
 * play that seat or reconnect to it.
 */
public final class GameRoom {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** A friend game nobody joined is dropped after this long. */
    static final long UNJOINED_LIFETIME_MS = 24L * 60 * 60 * 1000;

    /** A player's place in the game. */
    public static final class Seat {
        public final String token = token(18);
        public final WebGuiGame gui = new WebGuiGame();
        public final String name;

        Seat(String name) {
            this.name = name;
        }
    }

    public final String id = token(9);
    public final WebFormat format;
    public final Seat host;
    /** Null until a friend joins, and always null against the computer. */
    private Seat guest;
    /** Set for friend games until the friend has joined. */
    private String invite;
    private final Deck deck;
    private final long created = System.currentTimeMillis();
    private HostedMatch match;

    private GameRoom(WebFormat format, String hostName, Deck deck) {
        this.format = format;
        this.host = new Seat(hostName);
        this.deck = deck;
    }

    static GameRoom againstComputer(WebFormat format, String hostName, Deck deck) {
        GameRoom room = new GameRoom(format, hostName, deck);
        room.start(null);
        return room;
    }

    static GameRoom againstFriend(WebFormat format, String hostName, Deck deck) {
        GameRoom room = new GameRoom(format, hostName, deck);
        room.invite = token(12);
        return room;
    }

    public synchronized String invite() {
        return invite;
    }

    public synchronized boolean isStarted() {
        return match != null;
    }

    public synchronized boolean isOver() {
        return match != null && match.getGame() != null && match.getGame().isGameOver();
    }

    synchronized boolean isStale() {
        return isOver() || (match == null && System.currentTimeMillis() - created > UNJOINED_LIFETIME_MS);
    }

    /** The friend's seat, if the invite is right and nobody has used it yet; this starts the game. */
    synchronized Seat join(String inviteToken, String name) {
        if (invite == null || !invite.equals(inviteToken)) {
            return null;
        }
        invite = null;
        guest = new Seat(name);
        start(guest);
        return guest;
    }

    synchronized Seat seat(String token) {
        if (token == null) {
            return null;
        }
        if (host.token.equals(token)) {
            return host;
        }
        return guest != null && guest.token.equals(token) ? guest : null;
    }

    /** The other player's name, for the browser's header; null while a friend hasn't joined. */
    synchronized String opponentName(Seat seat) {
        if (seat == host) {
            return guest != null ? guest.name : invite == null ? "Computer" : null;
        }
        return host.name;
    }

    private void start(Seat friend) {
        Set<GameType> variants = EnumSet.of(format.gameType);
        // the host sits first: in DanDan, the first seat's deck is the shared library
        RegisteredPlayer first = RegisteredPlayer.forVariants(2, variants, format.deck(deck), null, false, null, null)
                .setPlayer(GamePlayerUtil.getGuiPlayer(host.name, 0, 0, false));
        RegisteredPlayer second = RegisteredPlayer.forVariants(2, variants, format.deck(deck), null, false, null, null)
                .setPlayer(friend == null ? new LobbyPlayerAi("Computer", null) : GamePlayerUtil.getGuiPlayer(friend.name, 1, 1, false));
        List<RegisteredPlayer> players = Lists.newArrayList(first, second);
        Map<RegisteredPlayer, IGuiGame> guis = Maps.newHashMap();
        guis.put(first, host.gui);
        if (friend != null) {
            guis.put(second, friend.gui);
        }
        match = new HostedMatch();
        match.startMatch(format.gameType, variants, players, guis);
    }

    static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
