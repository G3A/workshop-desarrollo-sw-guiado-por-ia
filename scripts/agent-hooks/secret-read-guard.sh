#!/usr/bin/env bash
# `set -u` only, deliberately. `pipefail` turns SIGPIPE into a failed pipeline, so a filter that
# short-circuits — `grep -q`, `head -1` — makes the whole guard silently fall through to allow.
# That failure is invisible: the hook exits 0 and nothing is blocked. See references/hook-catalog.md.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

# Names that ARE the credential, not a reference to one. Anchored on a path or assignment
# boundary so `deploy/keys/` does not match `.key` and `ENV_FILE=.env` still does.
# Keep this list short: every pattern is a promise that the team will not have to argue with it.
SECRET_PATTERNS='(^|[/=])\.env($|\.)|\.(pem|pfx|p12|jks|keystore)$|(^|[/=])id_(rsa|dsa|ecdsa|ed25519)$|(^|[/=])secrets\.(json|ya?ml)$|(^|[/=])application-secrets.*\.ya?ml$|(^|/)\.ssh/|(^|/)\.aws/credentials$'

# Committed on purpose, hold no secret, and are the thing the agent should read instead. Checked
# BEFORE the patterns and never stripped from the name: stripping `.example` off `.env.example`
# leaves `.env`, and the guard then blocks the very file it is meant to point at.
TEMPLATE_RE='\.(example|sample|template|dist)$'

is_secret() {
  printf '%s' "$1" | grep -Eq "$TEMPLATE_RE" && return 1
  printf '%s' "$1" | grep -Eq "$SECRET_PATTERNS"
}

TOOL="$(json_raw tool_name)"

case "$TOOL" in
  Bash)
    CMD="$(json_path command)"
    [ -z "$CMD" ] && exit 0
    # Four passes, and THE ORDER IS THE WHOLE CORRECTNESS ARGUMENT.
    #
    # 1. JSON escapes -> real separators, BEFORE any backslash is removed. A multi-line Bash
    #    command arrives as the two characters \ and n, not as a newline. Strip the backslash
    #    first and `cat .env\necho done` fuses into the single token `.envnecho`, which the
    #    anchored pattern misses — and Claude Code emits multi-line Bash constantly, so that is
    #    the NORMAL case, not an exotic one. This pass is why the guard works at all.
    # 2. Split into arguments. `,` is in the set: `cat .env,other` is one token without it.
    # 3. Strip " ' and backslash (octal 042 047 134) — shell quoting and the payload's own JSON
    #    escaping both survive into the token, and `\"src/.env\"` must still read as a path.
    # 4. Drop trailing glob characters, so `cat .env*` still reads as `.env`.
    #
    # A glob that does not spell the name — `cat .en?` — still passes. That is deliberate
    # evasion rather than a mistake, and this guard is a guardrail against mistakes. See the
    # header of block-dangerous-bash.sh.
    HIT="$(printf '%s' "$CMD" \
      | sed -E 's/\\[nrt]/ /g' \
      | tr ' \t\n;|&()<>,`' '\n' \
      | tr -d '\042\047\134' \
      | sed -E 's/[*?]+$//' \
      | grep -v -E "$TEMPLATE_RE" \
      | grep -m1 -E "$SECRET_PATTERNS")"
    [ -z "$HIT" ] && exit 0
    ;;
  *)
    # Read, and Edit/Write/MultiEdit if the matcher was widened. Always absolute, per the docs.
    TARGET="$(json_path file_path)"
    # No readable target means no decision. Never guess: an unreadable payload that denied by
    # default would block the whole session the first time the schema changes.
    [ -z "$TARGET" ] && exit 0
    is_secret "$TARGET" || exit 0
    ;;
esac

hook_deny "Blocked: that path is a credential file (.env, *.pem/pfx/p12/jks/keystore, id_rsa/id_ed25519, secrets.json/.yml, application-secrets*.yml, .ssh/, .aws/credentials). Use the committed .example/.sample/.template file instead, or ask the user for the value you need."
