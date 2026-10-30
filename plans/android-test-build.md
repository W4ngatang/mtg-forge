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
2. In **Variants**, choose **DanDan**. Both seats get the shared 80-card list automatically, so there's no deck to pick.
3. Set the second seat to **AI** to play the computer, or use **Play Online** to play a friend (they need the same build).

## Rules as implemented

- Both players draw from one library and put cards into one graveyard.
- A card you draw is yours: it returns to your hand, and it's under your control.
- "Your graveyard" and "your library" mean the shared ones. For example, Mystic Sanctuary and Mystic Retrieval can pick any instant or sorcery in the shared graveyard.
- A card that an effect returns from the shared graveyard or library to "its owner's hand" goes to the player whose spell or ability moved it.

The deck list is in `forge-game/src/main/java/forge/game/DanDanDeck.java`.

## Known limitations

- The AI wasn't written with a shared library in mind. It plays legal games, but expect odd choices, such as milling cards it would want to draw itself.
- The build is signed with a public debug key, so it's for testing with friends, not distribution.
