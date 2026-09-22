#!/usr/bin/env bash
# Von Hand auf den neuesten Stand des Zweigs: holen, bauen, Dienst neu starten. Dasselbe tut
# der Updater aus der Verwaltung heraus; dieses Skript ist fuer den Fall, dass man ohnehin
# auf dem Server ist. Datenbank und Belegfotos bleiben, Migrationen laufen beim Start.
set -euo pipefail
cd "$(dirname "$0")"
REPO_DIR=$(cd ../.. && pwd)
BRANCH=$(grep -E '^GIT_BRANCH=' .env 2>/dev/null | cut -d= -f2- || true)
BRANCH=${BRANCH:-main}
git -C "$REPO_DIR" fetch origin "$BRANCH"
git -C "$REPO_DIR" reset --hard "origin/$BRANCH"
export GIT_SHA GIT_DATE
GIT_SHA=$(git -C "$REPO_DIR" rev-parse --short=12 HEAD)
GIT_DATE=$(git -C "$REPO_DIR" log -1 --format=%cI HEAD)
docker compose build api updater
docker compose up -d
echo "Stand $GIT_SHA laeuft."
