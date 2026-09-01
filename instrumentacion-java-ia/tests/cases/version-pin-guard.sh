# Hook 6 — version-pin guard. PostToolUse, matcher Edit|Write|MultiEdit.
# Warns rather than blocks: PostToolUse runs after the write, so there is nothing left to stop.
H=version-pin-guard.sh
P="$WORK/proj"
mkdir -p "$P/literal" "$P/multiline" "$P/property" "$P/clean" "$P/managed"

cat > "$P/literal/pom.xml" <<'XML'
<project>
  <dependencies>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>foo</artifactId>
      <version>4.2.0</version>
    </dependency>
  </dependencies>
</project>
XML

# A version spread across its own lines, the way an IDE's reformat-on-save can leave it. A
# line-oriented check sees neither line as a violation on its own and reports nothing — the
# silent pass this whole hook exists to avoid.
cat > "$P/multiline/pom.xml" <<'XML'
<project>
  <dependencies>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>bar</artifactId>
      <version>
        4.2.0
      </version>
    </dependency>
  </dependencies>
</project>
XML

cat > "$P/property/pom.xml" <<'XML'
<project>
  <properties>
    <bar.version>2.1.0</bar.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>bar</artifactId>
      <version>${bar.version}</version>
    </dependency>
  </dependencies>
</project>
XML

cat > "$P/clean/pom.xml" <<'XML'
<project>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
  </dependencies>
</project>
XML

# A literal version legitimately living inside <dependencyManagement> is the source of truth, not
# a violation — the <dependencies> entry that consumes it carries no version at all.
cat > "$P/managed/pom.xml" <<'XML'
<project>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.example</groupId>
        <artifactId>bom-managed</artifactId>
        <version>1.0.0</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>bom-managed</artifactId>
    </dependency>
  </dependencies>
</project>
XML

check $H warn   'literal version'           "$(payload_file Edit "$P/literal/pom.xml")"
check $H warn   'version on its own lines'  "$(payload_file Edit "$P/multiline/pom.xml")"
check $H silent '${property} version'       "$(payload_file Edit "$P/property/pom.xml")"
check $H silent 'no version, BOM-managed'   "$(payload_file Edit "$P/clean/pom.xml")"
check $H silent 'literal version inside dependencyManagement is exempt' "$(payload_file Edit "$P/managed/pom.xml")"
check $H silent 'not a pom.xml'             "$(payload_file Edit /repo/src/main/resources/web.xml)"
check $H silent 'a .java file'              "$(payload_file Edit /repo/src/main/java/App.java)"
check $H silent 'a pom that is gone'        "$(payload_file Edit "$P/missing/pom.xml")"
check $H silent 'empty payload'             ''
