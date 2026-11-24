# Notes for Claude sessions in this fork

## Web server hosting (forge-web)

The browser version (`forge-web`) is hosted on **Fly.io**, and redeploying from a Claude session is the expected way to ship changes.

- **Redeploy:** run `forge-web/deploy/deploy.sh` from the repository root. It builds the server, stages the jar with the part of `forge-gui/res` the server uses, and runs `flyctl deploy`. It downloads a pinned, checksum-verified `flyctl` if none is installed. `forge-web/deploy/deploy.sh --stage` builds and stages without deploying.
- **Credentials:** the session environment provides `FLY_API_TOKEN`; never print it or commit it. The host password is the Fly secret `FORGE_WEB_PASSWORD`.
- **Settings:** `forge-web/deploy/fly.toml` has the app name, region, machine size and the volume for saved decks.
- **Keep exactly one machine, always on.** Games live in memory: a second machine would split players across servers, and a stopped machine drops games.
- **Before deploying:** run the web tests (`mvn -pl forge-web -am install -Dtest=WebPlayTest -Dsurefire.failIfNoSpecifiedTests=false`). Push the commit, then deploy, so the live server matches the branch.

Plans and status for the web version: `plans/web-play.md`.
