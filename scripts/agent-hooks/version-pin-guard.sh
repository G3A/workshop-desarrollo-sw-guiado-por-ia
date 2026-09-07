#!/usr/bin/env bash
# `set -u` only. See the note in secret-read-guard.sh.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

FILE="$(json_path file_path)"
[ -z "$FILE" ] && exit 0

case "$FILE" in
  */pom.xml|pom.xml) ;;
  *) exit 0 ;;
esac

[ -f "$FILE" ] || exit 0

# Read the file rather than the payload: an Edit delivers a diff, not the result, so the payload
# cannot answer "does this dependency now carry a literal version".
FLAT="$(tr '\n' ' ' < "$FILE")"

# Versions declared inside <dependencyManagement> are the source of truth and are exempt, whether
# literal or a ${property}. Single greedy pass: see the header note on the one-block assumption.
FLAT="$(printf '%s' "$FLAT" | sed -E 's#<dependencyManagement>.*</dependencyManagement>##')"

# Pull out each remaining <dependency>...</dependency> entry. They do not nest, so this is a
# sequential find-next-close, not a depth-counted scan.
BLOCKS="$(printf '%s' "$FLAT" | awk '
  {
    s = $0
    while ((i = index(s, "<dependency>")) > 0) {
      rest = substr(s, i + length("<dependency>"))
      j = index(rest, "</dependency>")
      if (j == 0) { break }
      print substr(rest, 1, j - 1)
      print "===dep-sep==="
      s = substr(rest, j + length("</dependency>"))
    }
  }')"

COUNT=0
while IFS= read -r block; do
  [ "$block" = "===dep-sep===" ] && continue
  case "$block" in
    *'<version>'*)
      VER="$(printf '%s' "$block" | sed -E -n 's#.*<version>[[:space:]]*([^<]*)[[:space:]]*</version>.*#\1#p')"
      case "$VER" in
        '${'*'}') ;;   # a property reference: exactly what centralised versioning asks for
        '') ;;         # no readable content; not a decision to guess at
        *) COUNT=$((COUNT + 1)) ;;
      esac
      ;;
  esac
done <<EOF
$BLOCKS
EOF

[ "$COUNT" -eq 0 ] && exit 0

REASON="$(printf 'This repository keeps dependency versions centrally, so %s <dependency> in %s must not pin its own <version> literally. Move it to a ${property} declared in <properties> and resolved through <dependencyManagement> or an imported BOM.' "$COUNT" "$(basename "$FILE")")"

printf '{"decision":"block","reason":"%s"}' "$(json_escape "$REASON")"
exit 0
