package forge.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.LobbyPlayer;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.Selection;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableTypes;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * One browser player's screen. The engine's calls become JSON messages to the browser, and the
 * browser's messages become calls on this seat's game controller.
 *
 * <p>Out: {@code state} (the game as this player may see it), {@code prompt}, {@code buttons},
 * {@code selection} (what may be clicked), {@code ask} (a dialog the engine is waiting on),
 * {@code notice}, {@code invalid}, {@code gameOver}.
 *
 * <p>In: {@code ok}, {@code cancel}, {@code card}, {@code player}, {@code stack}, {@code mana},
 * {@code concede}, {@code answer}. Everything that comes in is checked against what this player
 * may see and what was offered; anything else is refused with an {@code error} message.
 */
public class WebGuiGame extends AbstractGuiGame {
    /** Phases where a player stops for priority with an empty stack; with something on it they always do. */
    private static final Set<PhaseType> STOPS_ON_OWN_TURN = EnumSet.of(PhaseType.MAIN1, PhaseType.MAIN2);
    private static final Set<PhaseType> STOPS_ON_OPPONENT_TURN = EnumSet.of(PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.END_OF_TURN);
    private static final long STATE_THROTTLE_MS = 150;

    private static final ScheduledExecutorService FLUSHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "forge-web-state");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger nextAskId = new AtomicInteger();
    /**
     * The browser's clicks run here, one at a time. Not on the socket's thread: a click can open a
     * dialog (choosing which ability of a card to play, say), and the dialog's answer has to come in
     * on the socket's thread while the click waits for it.
     */
    private final java.util.concurrent.ExecutorService clicks = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "forge-web-clicks");
        t.setDaemon(true);
        return t;
    });
    private Consumer<String> outbox = s -> { };
    // replayed when a browser (re)connects
    private JsonObject lastState, lastPrompt, lastButtons, lastSelection;
    private JsonObject pendingAsk;
    private CompletableFuture<JsonObject> pendingAnswer;
    private volatile boolean stateDirty;
    private volatile long lastStateSent;
    private volatile boolean flushScheduled;

    /** Connects a browser. Whatever it missed (the state, the open prompt or dialog) is sent again. */
    public synchronized void attach(Consumer<String> sink) {
        outbox = sink;
        for (JsonObject o : new JsonObject[] { lastState, lastPrompt, lastButtons, lastSelection, pendingAsk }) {
            if (o != null) {
                sink.accept(o.toString());
            }
        }
    }

    public synchronized void detach(Consumer<String> sink) {
        if (outbox == sink) {
            outbox = s -> { };
        }
    }

    private synchronized void send(JsonObject message) {
        outbox.accept(message.toString());
    }

    private PlayerView you() {
        Set<PlayerView> mine = getLocalPlayers();
        return mine == null || mine.isEmpty() ? null : mine.iterator().next();
    }

    // ---- state ------------------------------------------------------------------------------

    /** Builds and sends the state now. Called on the game thread before anything that waits on the player. */
    private void sendState() {
        GameView game = getGameView();
        if (game == null) {
            return;
        }
        JsonObject state;
        try {
            state = StateJson.state(game, you(), this::mayView);
        } catch (RuntimeException e) {
            // the game moved on while the snapshot was taken; the next update sends a fresh one
            stateDirty = true;
            return;
        }
        synchronized (this) {
            lastState = state;
            stateDirty = false;
            lastStateSent = System.currentTimeMillis();
            outbox.accept(state.toString());
        }
    }

    /** Something changed: send the state soon, at most every STATE_THROTTLE_MS. */
    private void stateChanged() {
        stateDirty = true;
        if (System.currentTimeMillis() - lastStateSent >= STATE_THROTTLE_MS) {
            sendState();
        } else if (!flushScheduled) {
            flushScheduled = true;
            FLUSHER.schedule(() -> {
                flushScheduled = false;
                if (stateDirty) {
                    sendState();
                }
            }, STATE_THROTTLE_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override public void setGameView(GameView gameView) { super.setGameView(gameView); stateChanged(); }
    @Override public void updatePhase(boolean saveState) { super.updatePhase(saveState); stateChanged(); }
    @Override public void updateTurn(PlayerView player) { super.updateTurn(player); stateChanged(); }
    @Override public void updateStack() { super.updateStack(); stateChanged(); }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zones) { super.updateZones(zones); stateChanged(); }
    @Override public void updateCards(Iterable<CardView> cards) { super.updateCards(cards); stateChanged(); }
    @Override public void updateLives(Iterable<PlayerView> players) { super.updateLives(players); stateChanged(); }
    @Override public void updateManaPool(Iterable<PlayerView> players) { super.updateManaPool(players); stateChanged(); }
    @Override public void showCombat() { stateChanged(); }

    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        boolean ownTurn = playerTurn != null && playerTurn.equals(you());
        return !(ownTurn ? STOPS_ON_OWN_TURN : STOPS_ON_OPPONENT_TURN).contains(phase);
    }

    // ---- the interactive prompt --------------------------------------------------------------

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "prompt");
        o.addProperty("text", StateJson.plain(message));
        if (card != null && mayView(card)) {
            o.addProperty("card", card.getId());
        }
        synchronized (this) {
            lastPrompt = o;
        }
        send(o);
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "buttons");
        o.add("ok", button(label1, enable1));
        o.add("cancel", button(label2, enable2));
        o.addProperty("focusOk", focus1);
        sendState();
        synchronized (this) {
            lastButtons = o;
        }
        send(o);
    }

    private static JsonObject button(String label, boolean enabled) {
        JsonObject b = new JsonObject();
        b.addProperty("label", label == null ? "" : label);
        b.addProperty("enabled", enabled);
        return b;
    }

    @Override
    public void setSelection(Selection selection) {
        super.setSelection(selection);
        JsonObject o = new JsonObject();
        o.addProperty("t", "selection");
        JsonArray cards = new JsonArray();
        for (CardView c : selection.cards()) {
            if (mayView(c)) {
                cards.add(c.getId());
            }
        }
        o.add("cards", cards);
        o.addProperty("min", selection.min());
        o.addProperty("max", selection.max());
        o.add("targets", targetIds(selection.targets()));
        o.add("chosen", targetIds(selection.chosen()));
        synchronized (this) {
            lastSelection = o;
        }
        send(o);
    }

    private static JsonArray targetIds(List<TrackableObject> objects) {
        JsonArray a = new JsonArray();
        for (TrackableObject t : objects) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", t instanceof PlayerView ? "player" : t instanceof StackItemView ? "stack" : "card");
            o.addProperty("id", t.getId());
            a.add(o);
        }
        return a;
    }

    @Override public void flashIncorrectAction() { send(simple("invalid")); }
    @Override public void alertUser() { send(simple("alert")); }
    @Override public void message(String message, String title) { notice(title, message); }
    @Override public void showErrorDialog(String message, String title) { notice(title, message); }

    private void notice(String title, String text) {
        sendState();
        JsonObject o = simple("notice");
        o.addProperty("title", title == null ? "" : title);
        o.addProperty("text", text == null ? "" : text);
        send(o);
    }

    private static JsonObject simple(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    @Override protected void updateCurrentPlayer(PlayerView player) { }
    @Override
    public void openView(TrackableCollection<PlayerView> myPlayers) {
        // the engine routes this seat's clicks, and decides what it may see, through its current player
        if (myPlayers != null && !myPlayers.isEmpty()) {
            setCurrentPlayer(myPlayers.iterator().next());
        }
        stateChanged();
    }
    @Override public void setCard(CardView card) { }
    @Override public void setPanelSelection(CardView hostCard) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }

    @Override
    public void finishGame() {
        clicks.shutdown();
        sendState();
        cancelOpenDialog();
        send(simple("gameOver"));
    }

    /** Releases the engine from a dialog it's waiting on; the dialog takes its default answer. */
    private synchronized void cancelOpenDialog() {
        if (pendingAnswer != null) {
            pendingAnswer.cancel(false);
        }
    }

    // ---- dialogs: the engine waits for an answer -----------------------------------------------

    /** Sends a question and waits for a valid answer; null if the game ended first. */
    private <R> R ask(JsonObject question, Function<JsonObject, R> parse) {
        while (true) {
            CompletableFuture<JsonObject> answer = new CompletableFuture<>();
            JsonObject q = question.deepCopy();
            q.addProperty("t", "ask");
            q.addProperty("id", nextAskId.incrementAndGet());
            sendState();
            synchronized (this) {
                pendingAsk = q;
                pendingAnswer = answer;
            }
            send(q);
            JsonObject reply;
            try {
                reply = answer.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException | CancellationException e) {
                return null;
            } finally {
                synchronized (this) {
                    if (pendingAnswer == answer) {
                        pendingAsk = null;
                        pendingAnswer = null;
                    }
                }
            }
            try {
                R result = parse.apply(reply);
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException e) {
                // malformed answer: ask again
            }
            error("That answer doesn't fit the question.");
        }
    }

    private JsonObject option(Object o, FSerializableFunction<Object, String> display) {
        JsonObject j = new JsonObject();
        if (o instanceof CardView c) {
            boolean visible = mayView(c);
            j.addProperty("label", visible ? StateJson.plain(display != null ? display.apply(o) : c.getName()) : "Hidden card");
            j.add("card", StateJson.card(c, visible));
        } else {
            j.addProperty("label", StateJson.plain(display != null ? display.apply(o) : String.valueOf(o)));
            if (o instanceof PlayerView p) {
                j.addProperty("player", p.getId());
            }
        }
        return j;
    }

    @SuppressWarnings("unchecked")
    private JsonArray options(List<?> items, FSerializableFunction<?, String> display) {
        JsonArray a = new JsonArray();
        for (Object o : items) {
            a.add(option(o, (FSerializableFunction<Object, String>) display));
        }
        return a;
    }

    private static JsonObject question(String kind, String title) {
        JsonObject q = new JsonObject();
        q.addProperty("kind", kind);
        q.addProperty("title", title == null ? "" : title);
        return q;
    }

    /** Indices from {@code "choice": [..]}: distinct, in range, between min and max of them. */
    private static List<Integer> indices(JsonObject reply, String field, int size, int min, int max) {
        JsonArray a = reply.getAsJsonArray(field);
        List<Integer> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonElement e : a) {
            int i = e.getAsInt();
            if (i < 0 || i >= size || !seen.add(i)) {
                return null;
            }
            out.add(i);
        }
        return out.size() < min || out.size() > max ? null : out;
    }

    private <T> List<T> choose(String title, int min, int max, List<T> choices, FSerializableFunction<T, String> display, DelayedReveal reveal) {
        if (choices == null || choices.isEmpty()) {
            return Collections.emptyList();
        }
        JsonObject q = question("choose", title);
        q.addProperty("min", min);
        q.addProperty("max", max);
        q.add("options", options(choices, display));
        if (reveal != null) {
            q.addProperty("revealText", reveal.getMessagePrefix());
            JsonArray revealed = new JsonArray();
            for (CardView c : reveal.getCards()) {
                revealed.add(StateJson.card(c, true)); // shown to the chooser on purpose
            }
            q.add("reveal", revealed);
        }
        List<Integer> picked = ask(q, r -> indices(r, "choice", choices.size(), min, max));
        if (picked == null) {
            return Collections.emptyList();
        }
        List<T> out = new ArrayList<>();
        for (int i : picked) {
            out.add(choices.get(i));
        }
        return out;
    }

    private int chooseLabel(String title, String message, List<String> labels, int fallback) {
        JsonObject q = question("option", title);
        q.addProperty("text", message == null ? "" : message);
        JsonArray a = new JsonArray();
        for (String l : labels) {
            JsonObject j = new JsonObject();
            j.addProperty("label", l);
            a.add(j);
        }
        q.add("options", a);
        List<Integer> picked = ask(q, r -> indices(r, "choice", labels.size(), 1, 1));
        return picked == null ? fallback : picked.get(0);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        if (max < 0) {
            // Forge's way of showing cards with nothing to choose, such as a reveal
            if (choices != null && !choices.isEmpty()) {
                JsonObject q = question("reveal", message);
                q.add("options", options(choices, display));
                ask(q, r -> Boolean.TRUE);
            }
            return Collections.emptyList();
        }
        return choose(message, Math.max(0, min), Math.min(max, choices == null ? 0 : choices.size()), choices, display, null);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        List<? extends GameEntityView> picked = choose(title, isOptional ? 0 : 1, 1, optionList, null, delayedReveal);
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        return new ArrayList<>(choose(title, min, Math.min(max, optionList.size()), optionList, null, delayedReveal));
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0); // as Forge's own screens do: tapping a card plays its only ability
        }
        List<SpellAbilityView> picked = choose("Choose an ability", 0, 1, abilities, SpellAbilityView::toString, null);
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        List<String> labels = options == null || options.isEmpty() ? List.of("Yes", "No") : options;
        return chooseLabel("", question, labels, defaultIsYes ? 0 : 1) == 0;
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        return chooseLabel(title, message, List.of(yesButtonText, noButtonText), defaultYes ? 0 : 1) == 0;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return options == null || options.isEmpty() ? defaultOption : chooseLabel(title, message, options, defaultOption);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            int i = chooseLabel(title, message, inputOptions, -1);
            return i < 0 ? null : inputOptions.get(i);
        }
        JsonObject q = question("text", title);
        q.addProperty("text", message == null ? "" : message);
        q.addProperty("initial", initialInput == null ? "" : initialInput);
        q.addProperty("numeric", isNumeric);
        return ask(q, r -> {
            String s = r.get("value").getAsString();
            if (isNumeric) {
                Integer.parseInt(s.trim()); // throws, so the question is asked again
            }
            return s;
        });
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices, CardView referenceCard, boolean sideboardingMode, boolean showRememberCheckbox) {
        if (sourceChoices == null || sourceChoices.isEmpty()) {
            return new OrderResult<>(destChoices == null ? Collections.emptyList() : destChoices, false);
        }
        int n = sourceChoices.size();
        // how many may be left out of the ordered list
        int minOrdered = Math.max(0, n - Math.max(remainingObjectsMax, 0));
        int maxOrdered = Math.max(minOrdered, n - Math.max(remainingObjectsMin, 0));
        JsonObject q = question("order", title);
        q.addProperty("top", top == null ? "" : top);
        q.addProperty("min", minOrdered);
        q.addProperty("max", maxOrdered);
        q.add("options", options(sourceChoices, null));
        List<Integer> picked = ask(q, r -> indices(r, "order", n, minOrdered, maxOrdered));
        if (picked == null) {
            return new OrderResult<>(new ArrayList<>(sourceChoices), false);
        }
        List<T> out = new ArrayList<>();
        for (int i : picked) {
            out.add(sourceChoices.get(i));
        }
        return new OrderResult<>(out, false);
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable, boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> all = new ArrayList<>();
        cards.forEach(all::add);
        return order(title, "Top", 0, 0, all, null, null, false, false).ordered();
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        List<Object> targets = new ArrayList<>(blockers);
        if (defender != null) {
            targets.add(defender);
        }
        Map<Object, Integer> amounts = assignAmounts("Assign " + damage + " combat damage from " + attacker.getName(), targets, damage, false);
        Map<CardView, Integer> out = new HashMap<>();
        if (amounts == null) {
            if (!blockers.isEmpty()) {
                out.put(blockers.get(0), damage);
            }
            return out;
        }
        amounts.forEach((k, v) -> out.put(k == defender ? null : (CardView) k, v)); // null means the defender
        return out;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount, boolean atLeastOne, String amountLabel) {
        Map<Object, Integer> amounts = assignAmounts("Divide " + amount + " " + (amountLabel == null ? "" : amountLabel), new ArrayList<>(target.keySet()), amount, atLeastOne);
        return amounts == null ? target : amounts;
    }

    private Map<Object, Integer> assignAmounts(String title, List<Object> targets, int total, boolean atLeastOne) {
        JsonObject q = question("amounts", title);
        q.addProperty("total", total);
        q.addProperty("atLeastOne", atLeastOne);
        q.add("options", options(targets, null));
        List<Integer> amounts = ask(q, r -> {
            JsonArray a = r.getAsJsonArray("amounts");
            if (a.size() != targets.size()) {
                return null;
            }
            List<Integer> out = new ArrayList<>();
            int sum = 0;
            for (JsonElement e : a) {
                int v = e.getAsInt();
                if (v < 0 || (atLeastOne && v < 1)) {
                    return null;
                }
                sum += v;
                out.add(v);
            }
            return sum == total ? out : null;
        });
        if (amounts == null) {
            return null;
        }
        Map<Object, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < targets.size(); i++) {
            if (amounts.get(i) > 0) {
                out.put(targets.get(i), amounts.get(i));
            }
        }
        return out;
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return null; // the web formats don't sideboard
    }

    // ---- messages from the browser -------------------------------------------------------------

    /** Handles one message from this seat's browser. Called on the socket's thread, never the game's. */
    public void receive(String text) {
        JsonObject m;
        try {
            m = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            error("Not a message.");
            return;
        }
        String type = m.has("t") ? m.get("t").getAsString() : "";
        if (type.equals("answer")) {
            answer(m); // never waits, so it is safe on the socket's thread
            return;
        }
        clicks.execute(() -> click(type, m));
    }

    private void click(String type, JsonObject m) {
        IGameController controller = getGameController();
        GameView game = getGameView();
        if (controller == null || game == null) {
            error("No game is running.");
            return;
        }
        try {
            switch (type) {
                case "ok" -> controller.selectButtonOk();
                case "cancel" -> controller.selectButtonCancel();
                case "card" -> {
                    CardView card = game.getTracker().getObj(TrackableTypes.CardViewType, m.get("id").getAsInt());
                    if (card == null || !mayView(card)) {
                        error("No such card.");
                    } else {
                        controller.selectCard(card, null, null);
                    }
                }
                case "player" -> {
                    PlayerView player = game.getTracker().getObj(TrackableTypes.PlayerViewType, m.get("id").getAsInt());
                    if (player == null) {
                        error("No such player.");
                    } else {
                        controller.selectPlayer(player, null);
                    }
                }
                case "stack" -> {
                    StackItemView item = game.getTracker().getObj(TrackableTypes.StackItemViewType, m.get("id").getAsInt());
                    if (item == null) {
                        error("No such spell or ability.");
                    } else {
                        controller.selectStackItem(item);
                    }
                }
                case "mana" -> {
                    byte color = MagicColor.fromName(m.get("color").getAsString());
                    if (color == 0 && !"C".equalsIgnoreCase(m.get("color").getAsString())) {
                        error("No such mana color.");
                    } else {
                        controller.useMana(color == 0 ? MagicColor.COLORLESS : color);
                    }
                }
                case "concede" -> {
                    controller.concede();
                    cancelOpenDialog(); // the engine may be waiting on one, and nobody will answer it now
                }
                default -> error("Unknown message: " + type);
            }
        } catch (RuntimeException e) {
            error("Couldn't do that: " + e.getMessage());
        }
    }

    private void answer(JsonObject m) {
        CompletableFuture<JsonObject> answer;
        synchronized (this) {
            if (pendingAsk == null || !m.has("id") || m.get("id").getAsInt() != pendingAsk.get("id").getAsInt()) {
                answer = null;
            } else {
                answer = pendingAnswer;
            }
        }
        if (answer == null) {
            error("That question isn't open.");
        } else {
            answer.complete(m);
        }
    }

    private void error(String text) {
        JsonObject o = simple("error");
        o.addProperty("text", text);
        send(o);
    }
}
