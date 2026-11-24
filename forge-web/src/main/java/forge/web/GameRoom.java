package forge.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.player.GamePlayerUtil;

/** One game: a browser seat against the computer. Friends' seats and invite links come with the lobby. */
public final class GameRoom {
    private static final SecureRandom RANDOM = new SecureRandom();

    public final String id = token(9);
    /** Only the browser holding this may play the seat, including after reconnecting. */
    public final String seatToken = token(18);
    public final WebFormat format;
    public final WebGuiGame seat = new WebGuiGame();
    private final HostedMatch match = new HostedMatch();

    GameRoom(WebFormat format, String playerName) {
        this.format = format;
        Set<GameType> variants = EnumSet.of(format.gameType);
        LobbyPlayer human = GamePlayerUtil.getGuiPlayer(playerName, 0, 0, false);
        RegisteredPlayer rpHuman = RegisteredPlayer.forVariants(2, variants, format.deck(), null, false, null, null).setPlayer(human);
        RegisteredPlayer rpAi = RegisteredPlayer.forVariants(2, variants, format.deck(), null, false, null, null)
                .setPlayer(new LobbyPlayerAi("Computer", null));
        List<RegisteredPlayer> players = Lists.newArrayList(rpHuman, rpAi);
        Map<RegisteredPlayer, IGuiGame> guis = Maps.newHashMap();
        guis.put(rpHuman, seat);
        match.startMatch(format.gameType, variants, players, guis);
    }

    public boolean isOver() {
        return match.getGame() != null && match.getGame().isGameOver();
    }

    static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
