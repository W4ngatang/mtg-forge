# Android Test Build

How to install this branch's build of Forge on an Android phone and play DanDan.

## Download

Every push to the branch `ccr-0fcd92aa-6egimh` builds a new APK with the **Android test APK** GitHub Actions workflow (`.github/workflows/android-test-apk.yml`). It takes about 15 minutes. The workflow publishes to the `android-test` release:

**https://github.com/W4ngatang/mtg-forge/releases/tag/android-test**

You can also start a build by hand: in the GitHub app or website, open **Actions → Android test APK → Run workflow**.

## Install

1. **Uninstall any other Forge app first.** This build is signed with a different key from the official Forge app, so Android refuses to install it over the official one. Your official Forge data (decks, settings) is removed with it.
2. On the phone, open the release page above and tap the file `forge-android-…-signed-aligned.apk`.
3. Android asks to allow installs from your browser ("Install unknown apps"). Allow it, then tap **Install**.
4. Open Forge. On first launch it downloads about 160 MB of game data (`assets.zip`) from the same release. Use Wi-Fi.

## Later builds

New builds install over the old one, keeping your decks and settings. When a new build is published, the app offers the update when it starts. You can also download the new APK from the release page and install it the same way.

## Play DanDan

1. **Classic Mode → New Game → Constructed**.
2. In **Variants**, choose **DanDan**.
3. **Seat 1's deck is the shared library.** Seat 1 gets a deck button; seat 2 doesn't, because its deck isn't used. Pick **Constructed → DanDan** for the standard list, or one of your own. With no deck picked, the standard list is used.
4. Set the second seat to **AI** to play the computer, or use **Play Online** to play a friend (they need the same build). Online, the host's seat 1 deck is the one used.

## Build your own shared deck

Choosing the DanDan variant saves the standard list as an ordinary constructed deck named **DanDan**. Your own lists are ordinary constructed decks too.

- **Start from the standard list:** open **Deck Manager** from the home screen (or tap seat 1's deck button), open **DanDan**, change it, then use **Save As** to keep it under a new name. Editing and saving **DanDan** itself also works; the standard list is only re-created if no deck named DanDan exists.
- **Start from scratch:** create a new constructed deck and add cards as usual. There's no 4-copy limit for DanDan games, so 10 Dandân and 20 Islands are fine.
- **Export:** in the deck editor's menu, tap **Copy to clipboard**. You get a plain list such as `10 Dandân` / `20 Island`, ready to paste into a chat or notes app.
- **Import:** copy a list, then in a new deck tap **Import from clipboard**. One card per line, with the count first. `Dandan` without the accent works.
- **Test it against the AI:** pick it for seat 1, set seat 2 to AI, and play.

Any size works. The game ends when someone draws from the empty shared library, so smaller decks mean shorter games.

## Rules as implemented

- Both players draw from one library and put cards into one graveyard.
- A card you draw is yours: it returns to your hand, and it's under your control.
- "Your graveyard" and "your library" mean the shared ones. For example, Mystic Sanctuary and Mystic Retrieval can pick any instant or sorcery in the shared graveyard.
- A card that an effect returns from the shared graveyard or library to "its owner's hand" goes to the player whose spell or ability moved it.

The standard deck list is in `forge-game/src/main/java/forge/game/DanDanDeck.java`.

On desktop, AI-vs-AI games with your own list: `java -jar forge.jar sim -f DanDan -D <deck folder> -d MyList.dck -n 20`

## Known limitations

- The AI wasn't written with a shared library in mind. It plays legal games, but expect odd choices, such as milling cards it would want to draw itself.
- The build is signed with a public debug key, so it's for testing with friends, not distribution.
