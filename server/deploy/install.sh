#!/usr/bin/env bash
# Erstaufstellung auf einem Server mit Docker und git — nichts weiter. Fragt den Hostnamen
# ab, erzeugt die Geheimnisse, baut den Dienst im Container und startet alles (compose.yaml).
# Noch einmal aufgerufen: baut und startet neu, laesst die .env in Ruhe.
#
#   git clone git@github.com:JonasPrenn/POS-System.git /opt/vereinsdeckel
#   cd /opt/vereinsdeckel/server/deploy && ./install.sh
set -euo pipefail
cd "$(dirname "$0")"
REPO_DIR=$(cd ../.. && pwd)

command -v docker >/dev/null || { echo "Docker fehlt: https://docs.docker.com/engine/install/"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose (Plugin) fehlt."; exit 1; }
command -v git >/dev/null || { echo "git fehlt."; exit 1; }

if [ ! -f .env ]; then
  read -r -p "Hostname, unter dem der Server erreichbar ist (DNS zeigt auf diesen Rechner): " DOMAIN
  [ -n "$DOMAIN" ] || { echo "Ohne Hostnamen kein Zertifikat."; exit 1; }
  {
    echo "DOMAIN=$DOMAIN"
    echo "DB_PASSWORD=$(openssl rand -hex 24 2>/dev/null || head -c 48 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    echo "PAIRING_ADMIN_TOKEN=$(openssl rand -hex 24 2>/dev/null || head -c 48 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    echo "VEREIN_ZONE=Europe/Vienna"
    echo "REPO_DIR=$REPO_DIR"
    echo "GIT_BRANCH=$(git -C "$REPO_DIR" rev-parse --abbrev-ref HEAD)"
    echo "SSH_DIR=$HOME/.ssh"
  } > .env
  chmod 600 .env
  echo ".env angelegt (Passwoerter erzeugt)."
fi

export GIT_SHA GIT_DATE
GIT_SHA=$(git -C "$REPO_DIR" rev-parse --short=12 HEAD)
GIT_DATE=$(git -C "$REPO_DIR" log -1 --format=%cI HEAD)
echo "Baue Stand $GIT_SHA — beim ersten Mal dauert das eine Weile (Gradle laedt im Container)."
docker compose up -d --build

# shellcheck disable=SC1091
set -a; . ./.env; set +a
echo
echo "Laeuft. Verwaltung: https://$DOMAIN/verwaltung"
echo "Verwaltungsschluessel fuer die Ersteinrichtung (steht in .env als PAIRING_ADMIN_TOKEN):"
echo "  $PAIRING_ADMIN_TOKEN"
echo "Updates: in der Verwaltung unter Einstellungen → Updates, oder hier mit ./update.sh"
