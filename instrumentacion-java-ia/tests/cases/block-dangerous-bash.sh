# Hook 3 — dangerous command blocker. PreToolUse, matcher Bash.
# PROTECTED_BRANCHES is materialised as main|master. POM_PATH plays no part in this hook.
H=block-dangerous-bash.sh

# --- recursive delete outside the working tree -----------------------------------------------
check $H deny 'rm -rf /'                    "$(payload_bash 'rm -rf /')"
check $H deny 'rm -rf ~'                    "$(payload_bash 'rm -rf ~')"
check $H deny 'rm -rf ~/'                   "$(payload_bash 'rm -rf ~/')"
check $H deny 'rm -rf $HOME/Documents'      "$(payload_bash 'rm -rf $HOME/Documents')"
check $H deny 'after a cd'                  "$(payload_bash 'cd /tmp && rm -rf ~')"
# Flag order and long flags. One regex cannot cover these; the script parses the flags.
check $H deny 'rm -f -r /  (order)'         "$(payload_bash 'rm -f -r /')"
check $H deny 'rm --recursive --force /'    "$(payload_bash 'rm --recursive --force /')"
check $H deny 'rm -rf --no-preserve-root /' "$(payload_bash 'rm -rf --no-preserve-root /')"
# Enumerating / and ~ leaves these allowed, and they are no less final. The rule asks one
# question instead: is the target outside the repository?
check $H deny 'rm -rf /usr/local'           "$(payload_bash 'rm -rf /usr/local')"
check $H deny 'rm -rf /etc'                 "$(payload_bash 'rm -rf /etc')"
check $H deny 'rm -rf /opt/other-repo'      "$(payload_bash 'rm -rf /opt/other-repo')"

# --- files that pin the build ------------------------------------------------------------------
check $H deny 'rm pom.xml'                       "$(payload_bash 'rm pom.xml')"
check $H deny 'rm the maven wrapper properties'  "$(payload_bash 'rm .mvn/wrapper/maven-wrapper.properties')"
check $H deny 'rm a nested wrapper properties'   "$(payload_bash 'rm -f base-conocimiento/.mvn/wrapper/maven-wrapper.properties')"

# --- deleting inside the tree is routine, including nested pom.xml files -----------------------
check $H silent 'rm -rf target'             "$(payload_bash 'rm -rf target')"
check $H silent 'rm -rf ./artifacts'        "$(payload_bash 'rm -rf ./artifacts')"
check $H silent 'rm -rf src/main/target'    "$(payload_bash 'rm -rf src/main/target')"
check $H silent 'rm -rf hooktest'           "$(payload_bash 'rm -rf hooktest')"
check $H silent 'rm -f Probe.java'          "$(payload_bash 'rm -f Probe.java')"
check $H silent 'cat pom.xml'               "$(payload_bash 'cat pom.xml')"
# A relative path to a SUBMODULE's own pom.xml is a normal restructuring, not the root build file.
check $H silent 'rm a submodule pom.xml'    "$(payload_bash 'rm base-conocimiento/pom.xml')"

# --- force-push -------------------------------------------------------------------------------
check $H deny 'force to main'               "$(payload_bash 'git push --force origin main')"
check $H deny '-f to main'                  "$(payload_bash 'git push -f origin main')"
check $H deny 'force-with-lease, master'    "$(payload_bash 'git push --force-with-lease origin master')"
check $H deny 'HEAD:main'                   "$(payload_bash 'git push -f origin HEAD:main')"
check $H deny '+main'                       "$(payload_bash 'git push -f origin +main')"
# No destination named pushes whatever is checked out, which may be main. This is the common
# shape of the accident, and a rule that only looks for the branch name misses all three.
check $H deny 'no branch at all'            "$(payload_bash 'git push -f')"
check $H deny 'no branch, long flag'        "$(payload_bash 'git push --force')"
check $H deny 'HEAD'                        "$(payload_bash 'git push --force origin HEAD')"
# A branch whose name merely contains a protected one is a different branch.
check $H silent 'feature/main'              "$(payload_bash 'git push -f origin feature/main')"
check $H silent 'release/main'              "$(payload_bash 'git push --force origin release/main')"
check $H silent 'hotfix/master'             "$(payload_bash 'git push --force origin hotfix/master')"
check $H silent 'force to a feature'        "$(payload_bash 'git push --force origin feature/my-branch')"
check $H silent 'an ordinary push'          "$(payload_bash 'git push origin feature/x')"

# --- reset, publish, sudo ----------------------------------------------------------------------
check $H deny 'git reset --hard'            "$(payload_bash 'git reset --hard HEAD~3')"
check $H silent 'git reset (soft)'          "$(payload_bash 'git reset --soft HEAD~1')"
check $H silent 'git reset (mixed)'         "$(payload_bash 'git reset HEAD~1')"
check $H deny 'mvn deploy'                  "$(payload_bash 'mvn deploy')"
check $H deny 'mvn clean deploy'            "$(payload_bash 'mvn clean deploy')"
check $H deny 'mvn release:perform'         "$(payload_bash 'mvn release:perform')"
check $H silent 'mvn clean verify'          "$(payload_bash 'mvn clean verify')"
check $H silent 'mvn versions:display-dependency-updates' "$(payload_bash 'mvn versions:display-dependency-updates')"
check $H deny 'sudo'                        "$(payload_bash 'sudo mvn install')"
check $H deny 'sudo on a later line'        "$(payload_bash 'make build
sudo rm -rf /')"

# --- the command word, never text inside an argument -------------------------------------------
# Phase 6 of the skill requires writing these very strings into README.md. A rule that greps the
# whole command blocks the skill from documenting itself, and then the team removes the hook.
check $H silent 'reset named in an echo'    "$(payload_bash 'echo "never run git reset --hard"')"
check $H silent 'deploy in a grep'          "$(payload_bash 'grep -rn "mvn deploy" docs/')"
check $H silent 'sudo named in an echo'     "$(payload_bash 'echo "we block sudo and rm -rf ~ in this repo"')"

# --- ordinary work -----------------------------------------------------------------------------
check $H silent 'make check'                "$(payload_bash 'make check')"
check $H silent 'build and test'            "$(payload_bash 'mvn -B clean verify')"
check $H silent 'grep for main'             "$(payload_bash 'grep -r main src/')"
check $H silent 'multiline benign'          "$(payload_bash 'make check
git diff
echo ok')"
check $H silent 'empty payload'             ''
