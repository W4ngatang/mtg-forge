# Web Play

**Goal:** a Forge server that friends play in a web browser, on phones first, against each other or the computer.

Status: step 1 (the bridge) done. Next: step 2, the phone screen.

## Decisions

| Question | Decision |
|---|---|
| Formats | 1v1 only. Start with **DanDan** and **Momir Basic**; more formats will follow soon, so adding one must be cheap. |
| Opponents | Friends and the computer. |
| Screens | **Phones only for now** (portrait). Tablets and computers come later; see below for how to keep that cheap. |
| Access | **Invite links + host password**, no VPN. Cloudflare Access can be added later (see below). |

## Architecture

One Java process, a new module `forge-web`:

- **Web server:** Jetty (already a Forge dependency) serves the browser app and one WebSocket per player.
- **Games:** each game is a `HostedMatch` started with `startMatch(rules, variants, players, guis)`: one `WebGuiGame` per browser seat, none for a computer seat. Forge's lobby and network protocol aren't used.
- **`WebGuiGame`:** implements Forge's GUI interface (`IGuiGame`, through `AbstractGuiGame`). Every engine call becomes a JSON message to that player's browser. Browser messages call the seat's game controller (`IGameController`).
- **Formats:** a small registry. Each entry names the `GameType`, where decks come from (generated, pasted, or a saved shared list) and the seat rules. Adding a format means adding an entry, then running AI games through the spike harness to check that every decision it produces has a browser screen.

### What the browser has to handle

- **State:** players (life, mana pool), zones (hand, battlefield, graveyard, library and exile counts, the command zone for Momir's avatar), the stack, the phase, combat and the game log. The engine sends updates as `updateCards`, `updateZones`, `updatePhase`, `updateStack`, `updateLives` and `showCombat`.
- **The interactive prompt:** most moves (priority, casting, targeting, paying mana, attacking, blocking, mulligans, discarding) are one pattern:
  - The engine sends a prompt (`showPromptMessage`) and two buttons with labels (`updateButtons`).
  - It also sends a `Selection`: the clickable cards and targets, with a minimum and maximum count.
  - The browser answers with `selectCard`, `selectPlayer`, `selectStackItem`, `selectButtonOk`, `selectButtonCancel` or `useMana`.
  - The `Selection` lets the browser highlight valid choices without any engine change.
- **Dialogs:** about 15 kinds: pick one / pick many / pick up to N (`getChoices`, `one`, `many`), order cards (`order`), confirm, pick an option, enter a number or text (`showInputDialog`, for example naming a card for Predict), choose an ability when a card has several, and assign damage.

The card pool decides which dialogs come up:
- **DanDan** needs nearly all of them: targeting, countering, optional costs (Capsize buyback), choosing and ordering cards (Brainstorm, Telling Time), naming a card (Predict), choosing a land type or word (Vision Charm, Crystal Spray, Magical Hack), and modes (Vision Charm).
- **Momir** creates a random copy of *any* creature, so its games can produce any ability in Forge. The dialogs must therefore be generic, and Momir is a good stress test for them.

## Step 0 spike (done)

`forge-gui-desktop/src/test/java/forge/net/WebSeatSpike.java` (run with `-Dtest=WebSeatSpike`) starts headless `HostedMatch` games with one computer seat and one stand-in for a browser player. The stand-in answers every prompt from its own thread through the game controller, the way WebSocket messages would.

- **Result:** 5 DanDan and 5 Momir games ran to completion. The architecture works without a desktop GUI or Forge's lobby.
- **Limit:** the stand-in only clicks OK or the first valid choice, so it never casts spells and saw few dialog types. Step 1 extends it to play cards, and it becomes the per-format coverage check.

## Step 1: the bridge (done)

The `forge-web` module (`forge-web/src/main/java/forge/web/`):

| File | What it does |
|---|---|
| `WebServer` | Jetty: serves `/web` from the classpath and games over `/ws`. The host password comes from `FORGE_WEB_PASSWORD`. |
| `GameSocket` | One browser connection. `/ws?new=DANDAN&name=..&password=..` starts a game against the computer. `/ws?game=ID&seat=TOKEN` reconnects. |
| `GameRoom` | One game: a `HostedMatch` with a browser seat and a computer seat. The seat token is random and unguessable. |
| `WebGuiGame` | The seat's screen. Engine calls become JSON messages, and browser messages become controller calls, checked against what the player may see and what was offered. |
| `StateJson` | The game state as the seat may see it. Opponent hands and libraries are counts only. |
| `WebFormat` | The format list (DanDan, Momir Basic). |
| `resources/web/index.html` | A plain debug page that can play a whole game; not the real phone screen. |

**Run it locally:**

```
mvn -pl forge-web -am install -DskipTests
FORGE_WEB_PASSWORD=choose-one java -Dforge.web.assets=forge-gui/ -jar forge-web/target/forge-web-*-jar-with-dependencies.jar 8080
```

Run it from the repository root (`forge.web.assets` points at the folder that holds `res/`). Then open `http://localhost:8080/`, pick a format, and enter the password.

**Tests:** `WebPlayTest` plays whole games through the real socket with a scripted browser that casts spells, uses abilities and answers every dialog with a random valid choice:
- 4 DanDan and 4 Momir games, checking every state message for hidden cards and that Momir's avatar makes creatures.
- A wrong host password, and reconnecting to an open prompt or dialog.

**What it caught:**
- A browser click can open a dialog (choosing which ability to play), so clicks must not run on the socket's reader thread: the dialog's answer could never arrive. Clicks run on a per-seat thread; answers are handled on the socket thread.
- A card with one playable ability plays it straight away, as Forge's own screens do. A one-item menu made Momir's avatar impossible to use for the scripted player and would cost a tap on every play.
- `getChoices` with a maximum of −1 means "show these cards", for example the computer revealing cards it can't play well. It's now a `reveal` dialog.
- Conceding while the engine waits on a dialog releases it.
- A real DanDan bug, now fixed in the engine: a card drawn from the shared library changed owner only after the move, so the move's own events (and the server's state snapshot) still showed the old owner. That leaked a card in the computer's hand to the browser. Ownership now changes before the move.

**Decision types seen so far:** choose (one, several or optional), order, reveal, number entry (Momir's X), card, player and stack-item selection, and the mana and OK/Cancel prompts.

## Steps

| Step | What | Effort |
|---|---|---|
| 1. Bridge (**done**) | `forge-web` module, Jetty WebSocket, `WebGuiGame`, a JSON schema for game state, prompts and dialogs. Validate every browser message against what was offered. A scripted client plays whole games over the socket. | 1–2 weeks |
| 2. Board UI | Phone portrait layout. Battlefield, hands, stack, zones, log, Scryfall images, the prompt bar, then the dialogs. | 3–5 weeks |
| 3. Lobby | Create a game (format, computer or invite link), paste or choose a deck, saved shared-deck lists on the server, reconnecting. | ~1 week |
| 4. Deploy | Docker image, a small server or Oracle free tier, Cloudflare Tunnel for HTTPS, host password, basic rate limits. | ~1 week |

That's about 7–9 weeks of focused work. **First milestone:** a complete DanDan game against the computer on a phone, plain-looking, after steps 1–2 (about 4–6 weeks).

## Access without a VPN

| Option | Friend's experience | Protection | Work |
|---|---|---|---|
| **Invite links + host password** (chosen) | Tap the link, type a name, play | Only someone with a link can join a game, and only someone with the host password can create games on your server. Links can be forwarded, but they only reach that game. | Small, part of step 3 |
| **Cloudflare Access** email login in front of the whole site | First visit: enter email, type the code sent to it. Stays logged in for weeks. | Strongest: nobody outside your email list reaches the server at all. | Little code; needs a domain on Cloudflare (about $10/year); free for up to 50 users. Can be added later in an afternoon. |
| Built-in accounts and passwords | Sign up, remember a password | Depends on getting it right | Most work and the most responsibility; not worth it here |

Either way:
- **Untrusted input:** the server treats browser messages as untrusted. It only accepts answers from the choices it offered and never reads Java-serialized data from a browser.
- **No open ports:** Forge's own network port is never exposed. Cloudflare Tunnel gives HTTPS without opening ports or revealing the server's address.

## Phones, tablets and computers

**Decision: phones only for now.** To keep adding the larger screens cheap later, step 2 builds components that don't assume a screen size: card, zone, prompt bar and dialog components are arranged by one layout file, so a wider layout is a new arrangement, not a rewrite.

What adding them later involves, about 1–2 weeks:
- **Same components everywhere:** one set of components, arranged in three layouts by screen width: phone portrait; phone landscape and small tablets; large tablets and computers, with both battlefields side by side and the log and stack in a side panel.
- **Touch and mouse:**
  - Phones and tablets: tap a card to enlarge it, long-press for details.
  - Computers: hover to preview, plus keyboard shortcuts for OK and Cancel later.
- **Priorities:** phone portrait gets the polish first; the wide layouts must work from day one but can look plainer.

Adding computers late would be the expensive path: phone-only layouts usually need reworking to stretch.

## Open questions

- Card images: link to Scryfall directly (simplest) or keep a server-side cache (faster, works if Scryfall is slow).
- Game timers and what happens when a friend's phone sleeps mid-game (reconnecting is planned; an AI takeover after a timeout is possible).
