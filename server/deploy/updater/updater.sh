#!/usr/bin/env bash
# Der Updater (server/deploy/updater): sucht im Git-Repo nach neuen Staenden und spielt sie
# ein — auf Knopfdruck aus der Verwaltung oder von selbst, je nach Einstellung.
#
# Dienst und Updater reden ueber /data/updates (web/Updates.kt):
#   settings.json  { "mode": "MANUAL|CHECK|AUTO", "intervalMinutes": 60 }   schreibt der Dienst
#   request        "check" oder "install"                                   schreibt der Dienst, loescht der Updater
#   status.json    was der Updater weiss                                    schreibt der Updater
#
# MANUAL: nur auf Knopfdruck. CHECK: regelmaessig suchen, installieren auf Knopfdruck.
# AUTO: regelmaessig suchen und einspielen. Installieren heisst: git auf den Stand des
# Zweigs setzen, das Image bauen, den Dienst neu starten — die Datenbank bleibt, Migrationen
# laufen beim Start des Dienstes.
set -u
DIR=/data/updates
REPO=${REPO_DIR:?REPO_DIR fehlt}
BRANCH=${GIT_BRANCH:-main}
COMPOSE=("docker" "compose" "-f" "$REPO/server/deploy/compose.yaml" "--env-file" "$REPO/server/deploy/.env")
mkdir -p "$DIR"
git config --global --add safe.directory "$REPO" >/dev/null 2>&1 || true

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { echo "$(now) $*"; }

# status.json neu schreiben; was nicht uebergeben wird, bleibt wie es war.
write_status() { # state message [key value]...
  local state=$1 message=$2; shift 2
  local tmp="$DIR/status.json.tmp"
  jq -n --arg state "$state" --arg message "$message" --arg at "$(now)" --arg branch "$BRANCH" \
        --arg installed "$(git -C "$REPO" rev-parse --short=12 HEAD 2>/dev/null || echo unbekannt)" \
        --argjson prev "$(cat "$DIR/status.json" 2>/dev/null || echo '{}')" \
        '$prev + {state: $state, message: $message, updatedAt: $at, branch: $branch, installed: $installed}' > "$tmp"
  local key val
  while [ $# -ge 2 ]; do
    key=$1; val=$2; shift 2
    jq --arg k "$key" --arg v "$val" '.[$k] = $v' "$tmp" > "$tmp.2" && mv "$tmp.2" "$tmp"
  done
  mv "$tmp" "$DIR/status.json"
}

setting() { jq -r --arg k "$1" --arg d "$2" '.[$k] // $d' "$DIR/settings.json" 2>/dev/null || echo "$2"; }

check() {
  log "suche auf $BRANCH"
  write_status checking "Suche im Repo …"
  if ! out=$(git -C "$REPO" fetch --quiet origin "$BRANCH" 2>&1); then
    write_status failed "Holen fehlgeschlagen: ${out##*$'\n'}" log "$out"
    return 1
  fi
  local latest date message behind
  latest=$(git -C "$REPO" rev-parse --short=12 "origin/$BRANCH")
  date=$(git -C "$REPO" log -1 --format=%cI "origin/$BRANCH")
  message=$(git -C "$REPO" log -1 --format=%s "origin/$BRANCH")
  behind=$(git -C "$REPO" rev-list --count "HEAD..origin/$BRANCH" 2>/dev/null || echo 0)
  write_status idle "Zuletzt gesucht $(now)" checkedAt "$(now)" latest "$latest" latestDate "$date" latestMessage "$message" behind "$behind" log ""
  log "im Repo: $latest ($behind voraus)"
  [ "$behind" != "0" ]
}

install() {
  log "installiere"
  write_status installing "Neuer Stand wird geholt und gebaut — das dauert ein paar Minuten."
  local out
  if ! out=$(git -C "$REPO" fetch --quiet origin "$BRANCH" 2>&1 && git -C "$REPO" reset --hard --quiet "origin/$BRANCH" 2>&1); then
    write_status failed "Holen fehlgeschlagen: ${out##*$'\n'}" log "$out"; return 1
  fi
  export GIT_SHA GIT_DATE
  GIT_SHA=$(git -C "$REPO" rev-parse --short=12 HEAD)
  GIT_DATE=$(git -C "$REPO" log -1 --format=%cI HEAD)
  if ! out=$("${COMPOSE[@]}" build api 2>&1); then
    write_status failed "Bauen fehlgeschlagen — der alte Stand laeuft weiter." log "$(echo "$out" | tail -n 40)"; return 1
  fi
  if ! out=$("${COMPOSE[@]}" up -d --no-deps api 2>&1); then
    write_status failed "Neustart fehlgeschlagen." log "$(echo "$out" | tail -n 40)"; return 1
  fi
  write_status installed "Stand $GIT_SHA eingespielt $(now); der Dienst startet neu." behind "0" latest "$GIT_SHA" log ""
  log "fertig: $GIT_SHA"
}

write_status idle "Updater bereit."
last_check=0
while true; do
  mode=$(setting mode CHECK)
  interval=$(setting intervalMinutes 60)
  action=""
  if [ -f "$DIR/request" ]; then
    action=$(tr -d '[:space:]' < "$DIR/request"); rm -f "$DIR/request"
  elif [ "$mode" != "MANUAL" ] && [ $(( $(date +%s) - last_check )) -ge $(( interval * 60 )) ]; then
    action=check
  fi
  case "$action" in
    check)
      last_check=$(date +%s)
      if check && [ "$mode" = "AUTO" ]; then install; fi ;;
    install)
      last_check=$(date +%s)
      install ;;
    "") sleep 10 ;;
    *) log "unbekannte Anfrage: $action" ;;
  esac
done
