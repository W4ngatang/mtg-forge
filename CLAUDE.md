# Notes for Claude sessions in this fork

## Web server hosting (forge-web)

The browser version (`forge-web`) is hosted on **Fly.io**, and redeploying from a Claude session is the expected way to ship changes.

- **Redeploy:** run `forge-web/deploy/deploy.sh` from the repository root. It builds the server, stages the jar with the part of `forge-gui/res` the server uses, and runs `flyctl deploy`. It downloads a pinned, checksum-verified `flyctl` if none is installed. `forge-web/deploy/deploy.sh --stage` builds and stages without deploying.
- **From a cloud session:** Fly's remote builder can fail TLS through the session's proxy ("certificate signed by unknown authority"). Then build the image locally: start `dockerd` in the background, and run `SSL_CERT_FILE=/root/.ccr/ca-bundle.crt forge-web/deploy/deploy.sh --local-only` (extra arguments go to `flyctl deploy`).
- **Credentials:** the session environment provides `FLY_API_TOKEN`; never print it or commit it. The host password is the Fly secret `FORGE_WEB_PASSWORD`; the password for the whole site is `FORGE_SITE_PASSWORD`.
- **Settings:** `forge-web/deploy/fly.toml` has the app name, region, machine size and the volume for saved decks.
- **Keep exactly one machine.** Games live in memory, so a second machine would split players across servers. The machine runs on demand (see `fly.toml`): it stops a few minutes after the last connection closes and starts again on the next visit, and a stop drops any game in progress.
- **Before deploying:** run the web tests (`mvn -pl forge-web -am install -Dtest=WebPlayTest -Dsurefire.failIfNoSpecifiedTests=false`). Push the commit, then deploy, so the live server matches the branch.

Plans and status for the web version: `plans/web-play.md`.
