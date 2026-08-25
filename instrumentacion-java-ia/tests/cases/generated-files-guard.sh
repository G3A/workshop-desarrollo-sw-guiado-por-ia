# Hook 7 — generated-file guard. PreToolUse, matcher Edit|Write|MultiEdit.
# Unlike a guard that blocks every write under its directory, this one is existence-based: Flyway
# and Liquibase migrations are normally hand-written, so creating the NEXT one must go through;
# only editing one that is already on disk (and therefore may already be applied) is denied.
H=generated-files-guard.sh

FLYWAY="$WORK/repo/src/main/resources/db/migration"
CHANGELOG="$WORK/repo/src/main/resources/db/changelog"
mkdir -p "$FLYWAY" "$CHANGELOG"
: > "$FLYWAY/V1__init.sql"
: > "$CHANGELOG/001-init.xml"

check $H deny   'edit an existing Flyway migration'   "$(payload_file Edit "$FLYWAY/V1__init.sql")"
check $H deny   'overwrite an existing migration with Write' "$(payload_file Write "$FLYWAY/V1__init.sql")"
check $H silent 'write the NEXT migration'            "$(payload_file Write "$FLYWAY/V2__add_index.sql")"

check $H deny   'edit an existing Liquibase changelog' "$(payload_file Edit "$CHANGELOG/001-init.xml")"
check $H silent 'write the NEXT changelog entry'        "$(payload_file Write "$CHANGELOG/002-add-column.xml")"

# Segment-anchored, so a name that merely contains the word is untouched.
check $H silent 'docs/migrations-guide.md'   "$(payload_file Edit /repo/docs/migrations-guide.md)"
check $H silent 'MigrationHelper.java'       "$(payload_file Edit /repo/src/main/java/co/g3a/MigrationHelper.java)"
check $H silent 'a normal .java file'        "$(payload_file Edit /repo/src/main/java/co/g3a/App.java)"
check $H silent 'pom.xml'                    "$(payload_file Edit /repo/pom.xml)"
check $H silent 'empty payload'              ''
