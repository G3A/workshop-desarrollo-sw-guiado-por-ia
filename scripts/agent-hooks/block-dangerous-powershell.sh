#!/usr/bin/env bash
# `set -u` only. See the note in secret-read-guard.sh: pipefail turns a short-circuiting filter
# into a silent failure.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

# json_path, not json_raw: a real Windows path in the command arrives JSON-escaped
# (C:\\repo\\..) and json_path's one substitution turns every one of those into C:/repo/.. before
# anything below runs, the same reasoning secret-read-guard's PowerShell branch relies on.
CMD="$(json_path command)"
[ -z "$CMD" ] && exit 0

ROOT="$(repo_root)"
PROTECTED='main|dev'

# Split into subcommands on ; and | (pipeline); && and || are folded into ; first, same trick as
# the Bash sibling. Deliberately NOT split on a bare & — in PowerShell that is the call operator,
# a PREFIX that invokes a command held in a variable or string (`& $cmd`), not a Bash-style
# background/statement terminator, and treating it as a separator would misparse far more command
# lines than it would ever catch.
#
# Backslash is stripped here for the same reason it is in secret-read-guard's PowerShell branch,
# not because PowerShell paths still have any left (json_path already turned every real one into
# a forward slash above): a JSON-escaped quote inside the command leaves a lone backslash next to
# it, and that orphan breaks token comparisons downstream if it survives quote-stripping.
SUBS="$(printf '%s' "$CMD" \
  | sed -E 's/\\[nrt]/;/g' \
  | sed -E 's/&&|\|\|/;/g' \
  | tr ';|\n' '\n\n\n' \
  | tr -d '\042\047\134')"

# Is a delete target outside the repository? Absolute paths and home paths are, unless they point
# back inside the working tree. Relative targets are always fine — `Remove-Item -Recurse bin obj`
# is routine. Three absolute forms, not one: a drive-letter path (C:/...), a UNC share (//server/
# share, from \\server\share once json_path normalises it), and the Unix-style form Git Bash and
# WSL interop can still produce.
outside_repo() {
  case "$1" in
    -*) return 1 ;;                                            # a flag, not a target
    "~"|"~/"*|'$HOME'|'$HOME/'*) return 0 ;;
    '$env:USERPROFILE'|'$env:USERPROFILE/'*) return 0 ;;
    [A-Za-z]:/*) case "$1" in "$ROOT"/*|"$ROOT") return 1 ;; *) return 0 ;; esac ;;
    //*) return 0 ;;                                            # a UNC share is never the repo
    /*) case "$1" in "$ROOT"/*|"$ROOT") return 1 ;; *) return 0 ;; esac ;;
    *) return 1 ;;
  esac
}

while IFS= read -r sub; do
  sub="$(printf '%s' "$sub" | sed -E 's/^[[:space:]]*//')"
  [ -z "$sub" ] && continue
  # shellcheck disable=SC2086
  set -- $sub
  [ $# -eq 0 ] && continue
  CMDWORD="$1"; shift
  # A leading & is the call operator, invoking whatever command word follows it (`& git status`,
  # `& $cmd`) — not a command word of its own. Without unwrapping it here, CMDWORD becomes the
  # literal character &, matches none of the cases below, and every rule silently no-ops on any
  # command spelled with a leading &, which is routine PowerShell, not obfuscation.
  if [ "$CMDWORD" = '&' ]; then
    [ $# -eq 0 ] && continue
    CMDWORD="$1"; shift
  fi
  # PowerShell dispatches cmdlet and command names case-insensitively (Remove-Item, remove-item
  # and REMOVE-ITEM are the same call) — match on a lower-cased copy so casing is never the reason
  # a rule misses. git/mvn subcommands stay matched on $CMDWORD/"$@" as typed below: those are
  # real external binaries with case-SENSITIVE subcommands (`git PUSH` is not `git push`).
  CMDWORD_LC="$(printf '%s' "$CMDWORD" | tr '[:upper:]' '[:lower:]')"

  # Privilege escalation, two shapes: the classic standalone tool, and Start-Process asking for
  # elevation via -Verb RunAs (space-separated or colon-bound — RunAs is a fixed enum value here,
  # not free text, so a simple pair scan covers both spellings without a general parameter parser).
  case "$CMDWORD_LC" in
    runas) hook_deny 'Blocked: runas. A change that needs elevation is a change for the user to make deliberately, not for the agent to run.' ;;
  esac
  case "$CMDWORD_LC" in
    start-process|start|saps)
      PREV_LC=""
      for a in "$@"; do
        a_lc="$(printf '%s' "$a" | tr '[:upper:]' '[:lower:]')"
        case "$a_lc" in
          -verb:runas) hook_deny 'Blocked: Start-Process -Verb RunAs requests elevation. A change that needs elevation is a change for the user to make deliberately, not for the agent to run.' ;;
        esac
        case "$PREV_LC" in
          -verb) case "$a_lc" in runas) hook_deny 'Blocked: Start-Process -Verb RunAs requests elevation. A change that needs elevation is a change for the user to make deliberately, not for the agent to run.' ;; esac ;;
        esac
        PREV_LC="$a_lc"
      done
      ;;
  esac

  case "$CMDWORD_LC" in
    remove-item|ri|rm|rd|del|erase)
      RECURSIVE=no; TARGETS_OUTSIDE=no; PINNED=no
      check_target() {
        outside_repo "$1" && TARGETS_OUTSIDE=yes
        case "$1" in
          *.mvn/wrapper/maven-wrapper.properties|pom.xml|"$ROOT"/pom.xml) PINNED=yes ;;
        esac
      }
      for a in "$@"; do
        a_lc="$(printf '%s' "$a" | tr '[:upper:]' '[:lower:]')"
        case "$a_lc" in
          -recurse|-recurse:*) RECURSIVE=yes ;;
          -path:*|-literalpath:*) check_target "${a#*:}" ;;
          -*) ;;                                # -Force, -Confirm:$false and friends: not targets
          *) check_target "$a" ;;
        esac
      done
      # Recursive delete of anything outside the working tree. No undo, no recycle bin guaranteed
      # (-Force skips it): the tree, the git objects and everything else go together.
      [ "$RECURSIVE" = yes ] && [ "$TARGETS_OUTSIDE" = yes ] && \
        hook_deny 'Blocked: a recursive delete of a path outside this repository. If you meant to remove something inside the working tree, name that path relative to the repo.'
      # Deleting the files that pin the build silently undoes reproducibility (the wrapper) or
      # removes a module's build definition entirely (the root pom.xml).
      [ "$PINNED" = yes ] && \
        hook_deny 'Blocked: that file pins the build (the Maven wrapper properties, or the root pom.xml). If the wrapper is stale, run `mvn wrapper:wrapper` to regenerate it deliberately.'
      ;;

    git)
      SUBCMD="${1:-}"
      case "$SUBCMD" in
        push)
          shift
          FORCE=no; REFS=""; REMOTE_SEEN=no
          for a in "$@"; do
            case "$a" in
              --force|--force-with-lease|--force-with-lease=*|--force-if-includes) FORCE=yes ;;
              -*f*) case "$a" in --*) ;; *) FORCE=yes ;; esac ;;
              --*) ;;
              *)
                if [ "$REMOTE_SEEN" = no ]; then REMOTE_SEEN=yes
                else REFS="$REFS $a"; fi
                ;;
            esac
          done
          if [ "$FORCE" = yes ]; then
            if [ -z "${REFS# }" ]; then
              hook_deny 'Blocked: a force-push with no branch named pushes the current branch, which may be a protected one. Name the feature branch explicitly.'
            fi
            for r in $REFS; do
              r="${r#+}"; r="${r##*:}"; r="${r#refs/heads/}"
              case "$r" in
                HEAD|@) hook_deny 'Blocked: force-pushing HEAD pushes whatever branch is checked out, which may be a protected one. Name the feature branch explicitly.' ;;
              esac
              printf '%s' "$r" | grep -Eq "^($PROTECTED)$" && \
                hook_deny 'Blocked: force-push to a protected branch rewrites history other people have already pulled. Push to a feature branch and open a pull request.'
            done
          fi
          ;;
        reset)
          for a in "$@"; do
            [ "$a" = --hard ] && hook_deny 'Blocked: git reset --hard discards uncommitted work irreversibly. Use `git stash` if you need a clean tree.'
          done
          ;;
      esac
      ;;

    mvn)
      for a in "$@"; do
        case "$a" in
          deploy) hook_deny 'Blocked: `mvn deploy` publishes to a remote repository. Leave it to the pipeline or to the user.' ;;
          release:perform) hook_deny 'Blocked: `mvn release:perform` publishes a release. Leave it to the pipeline or to the user.' ;;
        esac
      done
      ;;
  esac
done <<EOF
$SUBS
EOF

exit 0
