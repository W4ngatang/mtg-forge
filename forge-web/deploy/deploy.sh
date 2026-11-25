#!/usr/bin/env bash
# Builds the Forge web server and deploys it to Fly.io (app and settings in fly.toml).
#
#   forge-web/deploy/deploy.sh            build and deploy
#   forge-web/deploy/deploy.sh --stage    build and stage only (forge-web/target/deploy), no deploy
#   forge-web/deploy/deploy.sh --local-only   build the image with Docker here instead of Fly's builder
#
# Needs FLY_API_TOKEN for deploying. Uses flyctl from PATH, or downloads the pinned release below
# and checks it against Fly's published checksum.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPLOY="$ROOT/forge-web/deploy"
STAGE="$ROOT/forge-web/target/deploy"
FLY_VERSION="0.5.17"
FLY_SHA256="bccf8c989295a11d968fc9032fd385530ab1b024a2177aba8bbaf46ae544deb6"
# res folders the web server never reads: other game modes, desktop and mobile visuals and audio,
# and card name translations
RES_EXCLUDES=(adventure quest conquest skins music sound puzzle tutorial 'languages/cardnames-*')

flyctl_path() {
    if command -v flyctl >/dev/null 2>&1; then
        command -v flyctl
        return
    fi
    local dir="$HOME/.cache/flyctl/$FLY_VERSION"
    if [ ! -x "$dir/flyctl" ]; then
        mkdir -p "$dir"
        curl -fsSL -o "$dir/flyctl.tar.gz" \
            "https://github.com/superfly/flyctl/releases/download/v$FLY_VERSION/flyctl_${FLY_VERSION}_Linux_x86_64.tar.gz"
        echo "$FLY_SHA256  $dir/flyctl.tar.gz" | sha256sum -c - >&2
        tar xzf "$dir/flyctl.tar.gz" -C "$dir" flyctl
    fi
    echo "$dir/flyctl"
}

echo "Building the web server..."
(cd "$ROOT" && mvn -B -q -pl forge-web -am install -DskipTests)

echo "Staging $STAGE..."
rm -rf -- "${STAGE:?}"
mkdir -p "$STAGE/res"
cp "$ROOT"/forge-web/target/forge-web-*-jar-with-dependencies.jar "$STAGE/forge-web.jar"
cp "$DEPLOY/Dockerfile" "$DEPLOY/fly.toml" "$STAGE/"
excludes=(--exclude=./cardsfolder)
for e in "${RES_EXCLUDES[@]}"; do excludes+=("--exclude=./$e"); done
(cd "$ROOT/forge-gui/res" && tar cf - "${excludes[@]}" .) | (cd "$STAGE/res" && tar xf -)
# Forge reads card scripts from cardsfolder.zip when it's there: one small file instead of 34,000
mkdir -p "$STAGE/res/cardsfolder"
(cd "$ROOT/forge-gui/res/cardsfolder" && zip -q -1 -r "$STAGE/res/cardsfolder/cardsfolder.zip" .)
du -sh "$STAGE"

if [ "${1:-}" = "--stage" ]; then
    exit 0
fi
FLYCTL="$(flyctl_path)"
cd "$STAGE"
# Fly's remote builder by default; --local-only (Docker on this machine) replaces it
builder=(--remote-only)
for arg in "$@"; do
    if [ "$arg" = "--local-only" ]; then builder=(); fi
done
"$FLYCTL" deploy "${builder[@]}" --config fly.toml "$@"
