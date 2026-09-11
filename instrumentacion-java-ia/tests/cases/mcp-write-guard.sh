# Hook 9 — MCP write guard. PreToolUse, matcher mcp__*.
# It reads ARGUMENTS, which is what the `permissions` rules of Phase 4 cannot: a rule decides on
# the tool name, this decides on which repository and which statement. Every case below is one an
# allow-list keyed on the tool name would let through.
H=mcp-write-guard.sh

# materialize() substitutes {{REPO_SLUG}} with acme/demo.
mcp() { printf '{"tool_name":"%s","tool_input":{%s}}' "$1" "$2"; }

# ── Not an MCP call ────────────────────────────────────────────────────────────
# The matcher should filter these out; the guard must not trust it, and must not trip on them.
check $H silent 'a Bash call is none of its business'  "$(payload_bash 'git status')"
check $H silent 'an Edit call is none of its business' "$(payload_file Edit /repo/src/Main.java)"

# ── GitHub: the tool name never says WHOSE repository ──────────────────────────
check $H deny   'writes into another owner'  "$(mcp mcp__github__create_issue '"owner":"otra","repo":"demo","title":"x"')"
check $H deny   'writes into another repo'   "$(mcp mcp__github__add_issue_comment '"owner":"acme","repo":"otro","body":"x"')"
check $H silent 'writes into this repository' "$(mcp mcp__github__create_issue '"owner":"acme","repo":"demo","title":"x"')"

# A call with no target cannot be judged. Letting it through is deliberate: a guard that cannot
# read its input must not guess — the same rule the rest of the catalogue follows.
check $H silent 'no owner/repo in the payload' "$(mcp mcp__github__list_notifications '"filter":"all"')"

# ── Database: the tool is read-shaped by name, arbitrary by argument ───────────
check $H deny   'DELETE through a query tool' "$(mcp mcp__dbhub__run_query '"sql":"DELETE FROM documento WHERE id = 1"')"
check $H deny   'UPDATE'                      "$(mcp mcp__dbhub__run_query '"sql":"UPDATE usuario SET rol = 1"')"
check $H deny   'DROP'                        "$(mcp mcp__dbhub__run_query '"sql":"DROP TABLE documento"')"
check $H deny   'TRUNCATE'                    "$(mcp mcp__dbhub__run_query '"sql":"TRUNCATE documento"')"
check $H deny   'leading whitespace does not hide it' "$(mcp mcp__dbhub__run_query '"sql":"   delete from documento"')"
check $H deny   'the query key instead of sql' "$(mcp mcp__postgres__query '"query":"INSERT INTO log VALUES (1)"')"

check $H silent 'a plain SELECT'  "$(mcp mcp__dbhub__run_query '"sql":"SELECT id FROM documento"')"
check $H silent 'lowercase select' "$(mcp mcp__dbhub__run_query '"sql":"select count(*) from documento"')"

# The reason the match is anchored at the start of the statement: these read-only queries all
# carry a write keyword in a column name, an alias or a string literal, and must go through.
check $H silent 'a column named updated_at'      "$(mcp mcp__dbhub__run_query '"sql":"SELECT updated_at FROM documento"')"
check $H silent 'a literal containing DELETE'    "$(mcp mcp__dbhub__run_query '"sql":"SELECT id FROM auditoria WHERE accion = ''DELETE''"')"
check $H silent 'a table named insert_queue'     "$(mcp mcp__dbhub__run_query '"sql":"SELECT * FROM insert_queue"')"

# ── A server with no branch of its own ────────────────────────────────────────
# Context7 reads documentation. Nothing here applies, and inventing a rule for it would be the
# long blocklist the catalogue warns against.
check $H silent 'an unrelated MCP server' "$(mcp mcp__context7__get_library_docs '"library":"spring-boot"')"
