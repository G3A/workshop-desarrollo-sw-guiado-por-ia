#!/usr/bin/env bash
# `set -u` only. See the note in secret-read-guard.sh: pipefail turns a short-circuiting filter
# into a silent failure.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

CMD="$(json_path command)"
[ -z "$CMD" ] && exit 0

ROOT="$(repo_root)"
PROTECTED='main|dev'

# Split into subcommands. Two portability traps live in these four lines, and both fail silently:
#
#   - Inside a bracket expression, POSIX reads `[;|&\n]` as the characters ; | & \ and **n**.
#     Every letter n becomes a separator, so `feature/my-branch`, `pom.xml` and
#     `never run ...` all shatter into fragments and the rules match the wrong things.
#   - BSD sed does not expand `\n` in the REPLACEMENT either; on macOS `s/;/\n/` inserts the
#     letter n. Only `tr` handles newlines the same way everywhere.
#
# So: normalise every separator to `;` with sed, then let `tr` make the newlines. JSON escapes go
# first — a multi-line command arrives as the two characters \ and n — and quotes and backslashes
# go last, the same ordering argument as in secret-read-guard.sh.
SUBS="$(printf '%s' "$CMD" \
  | sed -E 's/\\[nrt]/;/g' \
  | sed -E 's/\|\||&&/;/g' \
  | tr ';|&\n' '\n\n\n\n' \
  | tr -d '\042\047\134')"

# Is a delete target outside the repository? Absolute paths and home paths are, unless they point
# back inside the working tree. Relative targets are always fine — `rm -rf target obj` is routine.
# This replaces enumerating system directories, which always misses one: /usr/local and /etc are
# no less catastrophic than /.
outside_repo() {
  case "$1" in
    -*) return 1 ;;                     # a flag, not a target
    "~"|"~/"*|'$HOME'|'$HOME/'*) return 0 ;;
    /*) case "$1" in "$ROOT"/*|"$ROOT") return 1 ;; *) return 0 ;; esac ;;
    *) return 1 ;;
  esac
}

while IFS= read -r sub; do
  # Drop leading VAR=value assignments and whitespace, then read the command word.
  sub="$(printf '%s' "$sub" | sed -E 's/^[[:space:]]*//; s/^([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+)*//')"
  [ -z "$sub" ] && continue
  # shellcheck disable=SC2086
  set -- $sub
  [ $# -eq 0 ] && continue
  CMDWORD="$1"; shift

  # Privilege escalation. Nothing a coding task legitimately needs runs as root, and a hook cannot
  # undo what root did.
  case "$CMDWORD" in
    sudo|doas) hook_deny 'Blocked: sudo/doas. A change that needs root is a change for the user to make deliberately, not for the agent to run.' ;;
  esac

  case "$CMDWORD" in
    rm)
      RECURSIVE=no; TARGETS_OUTSIDE=no; PINNED=no
      for a in "$@"; do
        case "$a" in
          --recursive|--force) [ "$a" = --recursive ] && RECURSIVE=yes ;;
          --*) ;;                                  # --no-preserve-root and friends: not targets
          -*) case "$a" in *[rR]*) RECURSIVE=yes ;; esac ;;
          *)
            outside_repo "$a" && TARGETS_OUTSIDE=yes
            case "$a" in
              *.mvn/wrapper/maven-wrapper.properties) PINNED=yes ;;
              pom.xml) PINNED=yes ;;
              "$ROOT"/pom.xml) PINNED=yes ;;
            esac
            ;;
        esac
      done
      # Recursive delete of anything outside the working tree. No undo, no trash: the tree, the
      # git objects and everything else go together. Enumerating flags in one regex cannot work —
      # `rm -f -r /` and `rm --recursive --force /` are the same command in a different order.
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
            # A force-push that names no branch pushes the CURRENT one, which may well be main —
            # `git push -f` and `git push --force origin HEAD` are the common shape of this
            # accident, and a rule that only looks for the branch name misses both.
            if [ -z "${REFS# }" ]; then
              hook_deny 'Blocked: a force-push with no branch named pushes the current branch, which may be a protected one. Name the feature branch explicitly.'
            fi
            for r in $REFS; do
              # HEAD:main and +main both name main. Compare whole names, so feature/main and
              # release/main — different branches — are not caught by a rule about main.
              r="${r#+}"; r="${r##*:}"; r="${r#refs/heads/}"
              # HEAD and @ are the current branch under another spelling.
              case "$r" in
                HEAD|@) hook_deny 'Blocked: force-pushing HEAD pushes whatever branch is checked out, which may be a protected one. Name the feature branch explicitly.' ;;
              esac
              printf '%s' "$r" | grep -Eq "^($PROTECTED)$" && \
                hook_deny 'Blocked: force-push to a protected branch rewrites history other people have already pulled. Push to a feature branch and open a pull request.'
            done
          fi
          ;;
        reset)
          # Discards every uncommitted change with no reflog entry for the working tree.
          for a in "$@"; do
            [ "$a" = --hard ] && hook_deny 'Blocked: git reset --hard discards uncommitted work irreversibly. Use `git stash` if you need a clean tree.'
          done
          ;;
      esac
      ;;

    mvn)
      # Publishes to a remote repository. Outward-facing and, once another build has resolved it,
      # not truly deletable — the Maven analogue of `dotnet nuget push`.
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
