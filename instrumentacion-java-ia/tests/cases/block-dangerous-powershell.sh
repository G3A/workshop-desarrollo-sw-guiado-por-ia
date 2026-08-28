# Hook 8 — dangerous command blocker, PowerShell. PreToolUse, matcher PowerShell.
# {{PROTECTED_BRANCHES}} resolves to main|master in this suite (see lib.sh materialize()).
H=block-dangerous-powershell.sh

# --- privilege escalation --------------------------------------------------------------------
check $H deny 'ps: runas'                    "$(payload_powershell 'runas /user:Admin cmd')"
check $H deny 'ps: Start-Process -Verb RunAs, space'  "$(payload_powershell 'Start-Process powershell -Verb RunAs')"
check $H deny 'ps: Start-Process -Verb:RunAs, colon'  "$(payload_powershell 'Start-Process powershell -Verb:RunAs')"
check $H deny 'ps: saps alias'               "$(payload_powershell 'saps powershell -Verb RunAs')"

# --- recursive delete outside the repo ---------------------------------------------------------
check $H deny 'ps: Remove-Item -Recurse abs' "$(payload_powershell 'Remove-Item -Recurse -Force C:\Windows\Temp\stuff')"
check $H deny 'ps: rm alias'                 "$(payload_powershell 'rm -Recurse C:\Windows\Temp\stuff')"
check $H deny 'ps: home shorthand'           "$(payload_powershell 'Remove-Item -Recurse ~/Downloads')"
# The exact bypass class /code-review found in secret-read-guard's first PowerShell branch --
# colon-bound parameter syntax -- has to be checked here too, not assumed fixed by analogy.
check $H deny 'ps: colon-bound -Path, abs'   "$(payload_powershell 'Remove-Item -Recurse -Path:C:\Windows\System32')"
check $H deny 'ps: colon-bound -LiteralPath' "$(payload_powershell 'Remove-Item -Recurse -LiteralPath:C:\Windows\System32')"
check $H deny 'ps: colon-bound -Recurse'     "$(payload_powershell 'Remove-Item -Recurse:$true C:\Windows\Temp\stuff')"

# --- pinned build files, wherever named ---------------------------------------------------------
check $H deny 'ps: root pom.xml'             "$(payload_powershell 'Remove-Item pom.xml')"
check $H deny 'ps: maven wrapper properties' "$(payload_powershell 'Remove-Item .mvn/wrapper/maven-wrapper.properties')"

# --- git, same binary as under Bash -- close ports, not new logic ------------------------------
check $H deny 'ps: force-push no branch'     "$(payload_powershell 'git push --force')"
check $H deny 'ps: force-push main'          "$(payload_powershell 'git push --force origin main')"
check $H deny 'ps: force-push HEAD'          "$(payload_powershell 'git push --force origin HEAD')"
check $H deny 'ps: reset --hard'             "$(payload_powershell 'git reset --hard')"

# --- mvn, same binary as under Bash -------------------------------------------------------------
check $H deny 'ps: mvn deploy'               "$(payload_powershell 'mvn deploy')"
check $H deny 'ps: mvn release:perform'      "$(payload_powershell 'mvn release:perform')"

# --- must not fire ---------------------------------------------------------------------------
check $H silent 'ps: relative recurse'       "$(payload_powershell 'Remove-Item -Recurse bin, obj')"
check $H silent 'ps: recurse inside repo'    "$(payload_powershell 'Remove-Item -Recurse ./target')"
check $H silent 'ps: git status'             "$(payload_powershell 'git status')"
check $H silent 'ps: push a feature branch'  "$(payload_powershell 'git push origin feature/foo')"
check $H silent 'ps: mvn -v'                 "$(payload_powershell 'mvn -v')"
check $H silent 'ps: the word in a message'  "$(payload_powershell 'Write-Output "never run git reset --hard"')"
check $H silent 'ps: bare call operator'     "$(payload_powershell '& git status')"

# --- must not fire, Bash-only rules stay Bash-only ----------------------------------------------
check $H silent 'ps: sudo is not a PowerShell command, no false match'  "$(payload_powershell 'Write-Output sudo')"

# --- a payload it cannot read is not a reason to guess ---------------------------------------
check $H silent 'empty payload'              ''
check $H silent 'no tool_input'              '{"tool_name":"PowerShell"}'
