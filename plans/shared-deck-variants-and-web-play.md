# Shared-Deck Variants and Web Play

**Goal:** a server-hosted Forge instance that friends can play in a web browser, starting with shared-deck variants such as DanDan, and later a way to design and test new shared-deck variants, including against the AI.

The work splits into three tracks: the **variant engine** (A), a **headless server** (B), and a **web client** (C). The engine comes first, so DanDan is playable with friends on the normal Forge app long before the web part exists.

## What the code already provides

| Seam | Where | Why it matters |
|---|---|---|
| One lookup decides each player's library | `Player.getZone()` (`forge-game/src/main/java/forge/game/player/Player.java`) reads a per-player map | If both players' maps point at the same library object, you get a shared library without rewriting how drawing works. |
| Rules as cards in the command zone | Vanguard and Planechase | A variant's special rules can be written in Forge's normal card-script format instead of Java. |
| AI vs AI without a window | `sim` mode in `Main`, `forge-gui-desktop/src/main/java/forge/view/SimulateMatch.java` | Run many games to find crashes and test the AI. |
| Empty slot for a dedicated server | `server` mode in `forge-gui-desktop/src/main/java/forge/view/Main.java` prints "Not implemented" | A natural place for a headless server. |
| Headless game helpers | `HeadlessGuiDesktop`, `HeadlessNetworkGuiGame`, `UnifiedNetworkHarness` in `forge-gui-desktop/src/test/java/forge/net/` | Show how to run a game with no GUI. They are test-only today. |
| Interface between engine and GUI | `IGuiGame` (`forge-gui/src/main/java/forge/gui/interfaces/IGuiGame.java`, 78 methods) | Online play already forwards these calls over the network (`RemoteClientGuiGame`). A web client is another version of that. |
| Online play | Netty server and client in `forge-gui/src/main/java/forge/gamemodes/net/` | Up to 8 players, rejoin after disconnect, AI takes over for a player who doesn't return. |
| WebSocket support | Jetty is already a dependency in `forge-gui/pom.xml` (no Java code uses it yet) | Can serve WebSockets for the browser. |

## Track A: the variant engine

### A1. Shared-zones spike (small)
Throwaway code: start a game where both players use one library object and one graveyard object, and play by hand on desktop.

- **Goal:** a list of what breaks: shuffling, mulligans, "owner's library" moves, the UI showing the same zone twice, how the AI judges library size.
- **Done when:** a written list of problems found, which feeds into A2.

### A2. A general shared-zones game option (medium)
- Add settings to `GameRules`: share the library, share the graveyard, and the shared deck list.
- Decide **ownership** (an open question, see below) and set it up consistently.
- Add unit tests in `forge-game` for drawing, milling, bouncing, shuffling, and mulligans with shared zones.

### A3. A DanDan game type (small)
- A `GameType` entry plus the standard 80-card list, built automatically the way `MomirBasic` builds its decks.
- Add it to the desktop menus.

### A4. Make it work over the existing online play (small to medium)
- Check that the shared zones show up correctly for clients.
- Check that reconnecting and replacing a dropped player with the AI both work.

> **Milestone 1:** you play DanDan with friends using the normal Forge app, with one person hosting.

### A5. AI tuning (medium, open-ended)
- Use `sim` to run many AI-vs-AI DanDan games.
- Fix crashes first, then the AI's worst decisions. For example, it may think milling the shared library only hurts the opponent.

### A6. Variants defined in data files (medium)
- A format where each variant lists its zone sharing, its decks, its starting conditions, and optional rules cards in the command zone.
- DanDan becomes one of these files.

> **Milestone 2:** you design new shared-deck variants and test them against the AI without changing Java.

## Track B: the headless server

### B1. Fill in `Main server` (medium)
- Start the existing lobby server with no GUI.
- Turn the test helpers into real code.
- Add basic settings: port, password, allowed variants.

### B2. Packaging (small)
- A Dockerfile and a cheap VPS.
- Card images come from the client side or a CDN, not the server.
- TLS in front, through a reverse proxy.

> **Milestone 3:** an always-on server. Players still use the native app, but nobody has to keep a desktop open as host.

## Track C: the web client

### C1. Protocol design (small, but do it carefully)
- Map game state and player prompts to JSON messages.
- Most of the 78 `IGuiGame` methods are "state changed" notifications or "choose from this list" requests. Look for one general "choose" message that covers most of them.

### C2. A WebSocket version of the GUI on the server (large)
- Build it with Jetty, modeled on `RemoteClientGuiGame`.
- Test it with a script before any UI exists.

### C3. A minimal browser UI (large)
- Lobby, battlefield, hand, stack, prompts, and the shared library and graveyard.
- Use Scryfall images.
- Correct first, pretty later.

### C4. Hardening (medium)
- Logins or invite links.
- Reconnecting from the browser.
- Timers, and several games at once.

> **Milestone 4:** friends open a URL and play.

### Optional stopgap
For browser play before Track C is done, run the desktop app in the Docker browser-streaming setup from `docs/Docker-Setup.md`, one container per player, connected through normal Forge hosting. It's clunky but needs no code.

## Order

1. A1 → A2 → A3 → A4 (Milestone 1: play DanDan with friends)
2. B1 → B2 (Milestone 3: always-on server)
3. A5 / A6 (Milestone 2: custom variants vs AI) and Track C (Milestone 4: web play), in parallel

## Open questions

- **Ownership in DanDan:** does the player who drew a card own it? This decides where bounced and milled cards go and what "your graveyard" means.
- **Images in the web client:** Scryfall hotlinking versus a self-hosted cache.
- **Login for the web client:** invite links are simplest for a group of friends.

## Risks

- **Ownership edge cases (A2):** this is where the rules bugs will be.
- **Size of the web client (C2/C3):** the biggest single piece of work.
- **Merge conflicts:** this is a fork of a fast-moving project, so keep variant code in separate files where possible.
