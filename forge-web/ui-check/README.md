# Phone screen checks

Two Playwright scripts that drive the web app in a phone-sized Chromium (iPhone 13) and save screenshots. They're manual checks, not part of the Maven build: they need a running server.

- `walkthrough.js`: the host password; saving a shared deck by pasting a list into the deck builder, with an unknown card caught first (the builder's Scryfall search isn't exercised: it needs api.scryfall.com); a DanDan game against the computer with that deck, played to your main phase; playing a land; zooming a card; the log; then a friend game, where the host gets an invite link and a second phone joins.
- `momir-avatar.js`: a Momir game against the computer, using the avatar until it makes a creature (choose X, discard a card, pay).

Both scripts print any JavaScript errors at the end. Image 404s are expected when the server can't reach Scryfall.

## Run

Start a server from the repository root (the walkthrough needs the host password set; if the server also has a site password, pass `FORGE_SITE_PASSWORD` to the scripts too):

```
FORGE_WEB_PASSWORD=pick-one java -Dforge.web.assets=forge-gui/ -jar forge-web/target/forge-web-*-jar-with-dependencies.jar 8080
```

Then, with Playwright installed (`npm i playwright`):

```
mkdir -p shots
FORGE_WEB_PASSWORD=pick-one node forge-web/ui-check/walkthrough.js shots
FORGE_WEB_PASSWORD=pick-one node forge-web/ui-check/momir-avatar.js shots
```

Set `FORGE_URL` to check a server somewhere else.
