#!/usr/bin/env bash
# Die Version des Projekts, an einer Stelle: VERSION (x.y.z oder x.y.z-beta) — und, weil Xcode
# sie zur Bauzeit braucht, iosApp/Configuration/Version.xcconfig. Alles andere liest VERSION:
# Gradle (Android versionName und versionCode), :core (AppVersion fuer App und Server).
#
#   docs/tools/version.sh              zeigt die Version
#   docs/tools/version.sh beta 1.3.0   Entwicklung auf 1.3.0 Beta setzen (ein Commit)
#   docs/tools/version.sh release      die Beta freigeben: 1.3.0, Commit und Tag v1.3.0
#   docs/tools/version.sh next         nach der Freigabe weiter: 1.3.1 Beta (ein Commit)
#
# Gepusht wird nicht — das ist ein eigener Schritt:  git push --follow-tags
set -euo pipefail
cd "$(dirname "$0")/../.."

current=$(tr -d '[:space:]' < VERSION)
number=${current%-beta}
stage=""; [[ "$current" == *-beta ]] && stage=beta

# major*100000 + minor*1000 + patch*10, plus 9 bei Freigabe: Eine Beta liegt unter ihrer
# Freigabe, die Freigabe unter der naechsten Beta — Android und iOS verlangen steigende Codes.
code() {
  local a b c extra=0
  IFS=. read -r a b c <<< "$1"
  [ -z "$2" ] && extra=9
  echo $(( a * 100000 + b * 1000 + c * 10 + extra ))
}
label() { if [ -n "$2" ]; then echo "$1 Beta"; else echo "$1"; fi; }
write() { # number stage
  if [ -n "$2" ]; then echo "$1-$2" > VERSION; else echo "$1" > VERSION; fi
  printf '// Aus VERSION im Repo, geschrieben von docs/tools/version.sh — nicht von Hand aendern.\nMARKETING_VERSION = %s\nCURRENT_PROJECT_VERSION = %s\n' "$1" "$(code "$1" "$2")" > iosApp/Configuration/Version.xcconfig
}
commit() { git add VERSION iosApp/Configuration/Version.xcconfig; git commit -q -m "$1"; }

case "${1:-show}" in
  show)
    echo "$(label "$number" "$stage") — VERSION: $current, Code $(code "$number" "$stage")" ;;
  beta)
    [[ "${2:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Aufruf: version.sh beta x.y.z"; exit 1; }
    write "$2" beta; commit "Version $2 Beta"
    echo "Version $2 Beta — committet." ;;
  release)
    [ -n "$stage" ] || { echo "$number ist schon freigegeben."; exit 1; }
    write "$number" ""; commit "Version $number"
    git tag -a "v$number" -m "Version $number"
    echo "Version $number freigegeben, Tag v$number. Pushen mit: git push --follow-tags" ;;
  next)
    [ -z "$stage" ] || { echo "$number ist noch Beta — erst freigeben (release)."; exit 1; }
    IFS=. read -r a b c <<< "$number"; n="$a.$b.$((c + 1))"
    write "$n" beta; commit "Version $n Beta"
    echo "Weiter mit Version $n Beta — committet." ;;
  *)
    echo "Aufruf: version.sh [show | beta x.y.z | release | next]"; exit 1 ;;
esac
