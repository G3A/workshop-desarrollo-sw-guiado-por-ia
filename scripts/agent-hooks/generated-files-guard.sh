#!/usr/bin/env bash
# `set -u` only. See the note in secret-read-guard.sh.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

FILE="$(json_path file_path)"
[ -z "$FILE" ] && exit 0

# Flyway owns base-conocimiento/src/main/resources/db/migration/ once a script has run.
# Segment-anchored so docs/migrations-guide.md and a class named MigrationHelper.java are
# untouched. Solo la rama Flyway: este monorepo no usa Liquibase (sin carpeta db/changelog/).
case "$FILE" in
  */src/main/resources/db/migration/*|src/main/resources/db/migration/*)
    if [ -f "$FILE" ]; then
      hook_deny 'Blocked: files under src/main/resources/db/migration/ are checksummed by Flyway once applied. Do not edit an existing migration — add the next V<n>__description.sql instead.'
    fi
    ;;
esac

exit 0
