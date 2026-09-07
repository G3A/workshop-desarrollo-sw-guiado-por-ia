# Troubleshooting

> Referenced from `SKILL.md`'s `## Reference` section.

| Symptom | Almost always | Confirm with |
|---|---|---|
| A guard never fires, and never errors | `set -o pipefail` in the script | Remove it; every script here uses `set -u` alone |
| A guard fires on things it should not | Matching the whole stdin payload instead of a field | Match `tool_input.file_path`, or tokenise `tool_input.command` |
| A path check never matches on Windows | `tool_input.file_path` arrives with backslashes | Use `json_path`, not `json_raw`, for anything path-shaped |
| Hooks do nothing at all on Windows | Git Bash is absent; Claude Code fell back to PowerShell | `bash --version` |
| `settings.json` edits seem ignored | The file no longer parses | `python3 -c "import json;json.load(open('.claude/settings.json'))"` |
| An MCP server stays `⏸ Pending approval` | The workspace is not trusted | Run `claude`, accept the trust dialog, then `/mcp` |
| `${CLAUDE_PROJECT_DIR}` expands to `.` in `.mcp.json` | It is set in the server's environment, not Claude Code's | Use a dedicated env var with no default for an absolute path |
| `format-on-edit` reformats nothing | The `-DspotlessFiles` pattern did not match, or `spotless-maven-plugin` is not declared | Re-check the pattern against `mvn -f <pom> help:evaluate -Dexpression=project.basedir`; confirm the plugin exists |
| `version-pin-guard` fires on every dependency | Installed without `<dependencyManagement>`/a BOM in the pom | Remove it; the precondition is centralised versions |
| `generated-files-guard` blocks a brand-new migration | The script is checking the directory, not existence | Confirm the delivered template checks `[ -f "$FILE" ]`, not just the path pattern |
| An MCP server never starts, nothing looks wrong in `.mcp.json` | A flag the pinned version does not accept | `npx -y <package>@<pinned version> --help < /dev/null` |
