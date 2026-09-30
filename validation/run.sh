#!/usr/bin/env bash
# GUI validation for manyfold-jdbc. NOT part of CI. Run it by hand.
#
# What it does:
#   1. Builds the client bundle (manyfold jar + H2 jar) unless one is already present.
#   2. Downloads and silently installs SQuirreL SQL (pinned version, checksum verified).
#   3. Writes a SQuirreL settings directory that already contains a "manyfold" driver definition
#      (both jars on its class path) and an alias "manyfold-h2-demo" that auto-connects at startup
#      to two H2 in-memory databases, using the URL from the README "Try it in five minutes".
#   4. Starts Xvfb, launches SQuirreL, drives the real GUI with xdotool, and saves screenshots.
#
# Usage:
#   validation/run.sh                 full run
#   validation/run.sh --install-only  download and install SQuirreL, then exit (used by Dockerfile)
#
# Needs: JDK 17+, Xvfb, xdotool, curl, and ImageMagick (import/convert) or scrot. Fonts: DejaVu.
#
# Environment overrides:
#   OUT_DIR       where screenshots, RESULT.txt and squirrel-sql.log go   (validation/out)
#   CACHE_DIR     installer download and SQuirreL install location        (validation/.cache)
#   SQUIRREL_HOME use an existing SQuirreL install instead of installing
#   BUNDLE_DIR    directory holding manyfold-jdbc-*.jar and h2-*.jar      (build/client)
#   SKIP_BUILD=1  never run Gradle; fail if BUNDLE_DIR lacks the jars
#
# PASS criteria. They are deliberately simple and only catch gross failures. The screenshots
# are the real evidence; open them.
#   - SQuirreL's own log says it connected to the alias at application start.
#   - The session tab strip is drawn in the "connected" screenshot (skipped without ImageMagick).
#   - After the two SELECTs, no window titled "Error*" exists and squirrel-sql.log has no ERROR.
#   - Each screenshot exists, is not empty, and differs from the one before it.
#   - SQuirreL is still running at the end.
# The DELETE is expected to be refused by the driver. That outcome is reported but never fails
# the run either way, because the refusal text is shown in the GUI, which is not machine-checked.
#
# Exit status: 0 PASS, 1 FAIL (see RESULT.txt), 2 could not set up.

set -euo pipefail

# ---- pinned SQuirreL release ------------------------------------------------------------------
SQUIRREL_VERSION=5.1.0
SQUIRREL_SHA256=a6ad409375aea36db5e158f6f5d1d7ae7c6ba0d1fecea98225e217e1ab123184
SQUIRREL_URL="https://github.com/squirrel-sql-client/squirrel-sql-stable-releases/releases/download/${SQUIRREL_VERSION}-installer/squirrel-sql-${SQUIRREL_VERSION}-standard.jar"
# ------------------------------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/out}"
CACHE_DIR="${CACHE_DIR:-$SCRIPT_DIR/.cache}"
BUNDLE_DIR="${BUNDLE_DIR:-$REPO_ROOT/build/client}"
SQUIRREL_HOME="${SQUIRREL_HOME:-$CACHE_DIR/squirrel-sql-$SQUIRREL_VERSION}"
ALIAS_NAME="manyfold-h2-demo"
SCREEN_W=1600
SCREEN_H=1000

XVFB_PID=""
SQUIRREL_PID=""
WORK_DIR=""
FAILURES=()
NOTES=()

log() { printf '[validation] %s\n' "$*"; }

die() {
  printf '[validation] ERROR: %s\n' "$*" >&2
  mkdir -p "$OUT_DIR"
  printf 'FAIL\nsetup error: %s\n' "$*" >"$OUT_DIR/RESULT.txt"
  exit 2
}

cleanup() {
  local status=$?
  if [ -n "$WORK_DIR" ] && [ -f "$WORK_DIR/userdir/logs/squirrel-sql.log" ]; then
    cp "$WORK_DIR/userdir/logs/squirrel-sql.log" "$OUT_DIR/squirrel-sql.log" 2>/dev/null || true
  fi
  [ -n "$SQUIRREL_PID" ] && kill "$SQUIRREL_PID" 2>/dev/null || true
  [ -n "$XVFB_PID" ] && kill "$XVFB_PID" 2>/dev/null || true
  [ -n "$WORK_DIR" ] && rm -rf "$WORK_DIR"
  exit "$status"
}
trap cleanup EXIT

need() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }

xml_escape() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }

# ---- SQuirreL install -------------------------------------------------------------------------
install_squirrel() {
  if [ -x "$SQUIRREL_HOME/squirrel-sql.sh" ]; then
    log "SQuirreL already installed at $SQUIRREL_HOME"
    return
  fi
  need java
  need curl
  need sha256sum
  mkdir -p "$CACHE_DIR"
  local installer="$CACHE_DIR/squirrel-sql-$SQUIRREL_VERSION-standard.jar"
  if [ ! -f "$installer" ]; then
    log "downloading $SQUIRREL_URL"
    curl -fsSL --retry 3 -o "$installer.part" "$SQUIRREL_URL" \
      || die "could not download $SQUIRREL_URL"
    mv "$installer.part" "$installer"
  fi
  local actual
  actual="$(sha256sum "$installer" | cut -d' ' -f1)"
  [ "$actual" = "$SQUIRREL_SHA256" ] \
    || die "checksum mismatch for $installer: expected $SQUIRREL_SHA256, got $actual"
  log "installing SQuirreL $SQUIRREL_VERSION into $SQUIRREL_HOME"
  local answers="$CACHE_DIR/auto-install.xml"
  sed "s#@INSTALL_PATH@#$SQUIRREL_HOME#" "$SCRIPT_DIR/auto-install.xml" >"$answers"
  java -Djava.awt.headless=true -jar "$installer" "$answers" >"$CACHE_DIR/install.log" 2>&1 \
    || { tail -20 "$CACHE_DIR/install.log" >&2; die "SQuirreL installer failed"; }
  [ -x "$SQUIRREL_HOME/squirrel-sql.sh" ] || die "installer did not create squirrel-sql.sh"
}

if [ "${1:-}" = "--install-only" ]; then
  install_squirrel
  exit 0
fi

# ---- preconditions ----------------------------------------------------------------------------
need java
need Xvfb
need xdotool
need curl
SHOT_TOOL=""
if command -v import >/dev/null 2>&1; then
  SHOT_TOOL=import
elif command -v scrot >/dev/null 2>&1; then
  SHOT_TOOL=scrot
else
  die "need ImageMagick (import) or scrot to take screenshots"
fi
CONVERT=""
if command -v convert >/dev/null 2>&1; then CONVERT=convert; fi

java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ "${java_major:-0}" -ge 17 ] || die "JDK 17 or newer is required (found: ${java_major:-unknown})"

case "$SQUIRREL_HOME$OUT_DIR$BUNDLE_DIR" in
  *" "*) die "paths must not contain spaces (SQuirreL's launcher script does not quote them)" ;;
esac

mkdir -p "$OUT_DIR"
find "$OUT_DIR" -maxdepth 1 \( -name '*.png' -o -name RESULT.txt -o -name squirrel-sql.log \) \
  -delete

# ---- driver bundle ----------------------------------------------------------------------------
find_jar() { find "$BUNDLE_DIR" -maxdepth 1 -name "$1" 2>/dev/null | sort | head -1; }

MANYFOLD_JAR="$(find_jar 'manyfold-jdbc-*.jar')"
H2_JAR="$(find_jar 'h2-*.jar')"
if [ -z "$MANYFOLD_JAR" ] || [ -z "$H2_JAR" ]; then
  [ "${SKIP_BUILD:-0}" = 1 ] && die "no jars in $BUNDLE_DIR and SKIP_BUILD=1"
  [ -x "$REPO_ROOT/gradlew" ] || die "no jars in $BUNDLE_DIR and no gradlew to build them"
  log "building the client bundle with Gradle"
  (cd "$REPO_ROOT" && ./gradlew clientBundle --no-daemon -q) || die "gradle clientBundle failed"
  MANYFOLD_JAR="$(find_jar 'manyfold-jdbc-*.jar')"
  H2_JAR="$(find_jar 'h2-*.jar')"
fi
[ -n "$MANYFOLD_JAR" ] && [ -n "$H2_JAR" ] || die "clientBundle did not produce both jars in $BUNDLE_DIR"
log "driver jar: $MANYFOLD_JAR"
log "backend jar: $H2_JAR"

install_squirrel

# ---- the exact URL from the README ------------------------------------------------------------
ALIAS_URL="$(grep -E '^jdbc:manyfold:prod=jdbc:h2:mem:prod' "$REPO_ROOT/README.md" || true)"
[ "$(printf '%s\n' "$ALIAS_URL" | wc -l)" -eq 1 ] && [ -n "$ALIAS_URL" ] \
  || die "expected exactly one 'jdbc:manyfold:prod=jdbc:h2:mem:prod...' line in README.md"

# ---- SQuirreL settings: driver, alias, no first-run help window -------------------------------
WORK_DIR="$(mktemp -d)"
USERDIR="$WORK_DIR/userdir"
mkdir -p "$USERDIR"

cat >"$USERDIR/prefs.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<Beans>
    <Bean Class="net.sourceforge.squirrel_sql.client.preferences.SquirrelPreferences">
        <firstRun>false</firstRun>
    </Bean>
</Beans>
EOF

DRIVER_ID="manyfold-validation-driver"
cat >"$USERDIR/SQLDrivers.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<Beans>
    <Bean Class="net.sourceforge.squirrel_sql.fw.sql.SQLDriver">
        <JDBCDriverClassLoaded>false</JDBCDriverClassLoaded>
        <driverClassName>io.github.jbburns.manyfold.jdbc.ManyfoldDriver</driverClassName>
        <identifier Class="net.sourceforge.squirrel_sql.fw.id.UidIdentifier">
            <string>$DRIVER_ID</string>
        </identifier>
        <jarFileName/>
        <jarFileNames Indexed="true">
            <Bean Class="net.sourceforge.squirrel_sql.fw.util.beanwrapper.StringWrapper">
                <string>$(printf '%s' "$MANYFOLD_JAR" | xml_escape)</string>
            </Bean>
            <Bean Class="net.sourceforge.squirrel_sql.fw.util.beanwrapper.StringWrapper">
                <string>$(printf '%s' "$H2_JAR" | xml_escape)</string>
            </Bean>
        </jarFileNames>
        <name>manyfold</name>
        <url>jdbc:manyfold:[option=value;...][name=]jdbc:vendor://... || [name=]jdbc:vendor://...</url>
        <webSiteUrl/>
    </Bean>
</Beans>
EOF

cat >"$USERDIR/SQLAliases23.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<Beans>
    <Bean Class="net.sourceforge.squirrel_sql.client.gui.db.SQLAlias">
        <autoLogon>true</autoLogon>
        <connectAtStartup>true</connectAtStartup>
        <driverIdentifier Class="net.sourceforge.squirrel_sql.fw.id.UidIdentifier">
            <string>$DRIVER_ID</string>
        </driverIdentifier>
        <identifier Class="net.sourceforge.squirrel_sql.fw.id.UidIdentifier">
            <string>manyfold-validation-alias</string>
        </identifier>
        <name>$ALIAS_NAME</name>
        <password></password>
        <url>$(printf '%s' "$ALIAS_URL" | xml_escape)</url>
        <userName></userName>
    </Bean>
</Beans>
EOF

# ---- X server ---------------------------------------------------------------------------------
DISPLAY_NUM=""
for n in $(seq 99 130); do
  if [ ! -e "/tmp/.X$n-lock" ] && [ ! -S "/tmp/.X11-unix/X$n" ]; then
    DISPLAY_NUM=$n
    break
  fi
done
[ -n "$DISPLAY_NUM" ] || die "no free X display number between 99 and 130"
export DISPLAY=":$DISPLAY_NUM"
Xvfb "$DISPLAY" -screen 0 "${SCREEN_W}x${SCREEN_H}x24" -nolisten tcp >"$WORK_DIR/xvfb.log" 2>&1 &
XVFB_PID=$!
for _ in $(seq 1 50); do
  xdotool getdisplaygeometry >/dev/null 2>&1 && break
  sleep 0.2
done
xdotool getdisplaygeometry >/dev/null 2>&1 || die "Xvfb did not start: $(cat "$WORK_DIR/xvfb.log")"
log "Xvfb running on $DISPLAY"

# ---- helpers for driving the GUI --------------------------------------------------------------
SHOTS=()
SHOT_NOTES=()
SHOT_N=0

shot_raw() {
  if [ "$SHOT_TOOL" = import ]; then import -window root "$1"; else scrot -o "$1"; fi
}

# shot LABEL DESCRIPTION: saves NN-LABEL.png in OUT_DIR
shot() {
  SHOT_N=$((SHOT_N + 1))
  local file
  file="$(printf '%02d-%s.png' "$SHOT_N" "$1")"
  shot_raw "$OUT_DIR/$file"
  SHOTS+=("$file")
  SHOT_NOTES+=("$2")
  log "screenshot $file"
}

click() { xdotool mousemove "$1" "$2" click 1; }

# type_and_run SQL: focus the editor, replace its contents, run with Ctrl+Enter.
# Mouse clicks are used to give the editor keyboard focus because the bare Xvfb has no window
# manager to hand out focus.
type_and_run() {
  click 700 230
  sleep 0.5
  xdotool key ctrl+a
  xdotool type --delay 20 -- "$1"
  sleep 1
  xdotool key ctrl+Return
  sleep 3
}

# Widen the first result column so "source_database" is readable. Cosmetic only.
widen_first_column() {
  xdotool mousemove 145 410 mousedown 1 mousemove 190 410 mousemove 250 410 mouseup 1 || true
  sleep 0.5
}

session_strip_colors() {
  [ -n "$CONVERT" ] || { echo -1; return; }
  local tmp="$WORK_DIR/strip.png"
  shot_raw "$tmp"
  "$CONVERT" "$tmp" -crop 300x24+34+58 +repage -format %k info: 2>/dev/null || echo 0
}

error_window_exists() {
  xdotool search --name '^Error' 2>/dev/null | grep -q .
}

log_error_count() {
  local f="$USERDIR/logs/squirrel-sql.log"
  if [ -f "$f" ]; then grep -c ' ERROR ' "$f" || true; else echo 0; fi
}

fail() { FAILURES+=("$1"); log "FAIL: $1"; }

# ---- launch SQuirreL --------------------------------------------------------------------------
# Flags: -userdir <dir> (settings directory), -nos (--no-splash). The launcher script itself adds
# --squirrel-home for the install directory.
log "starting SQuirreL SQL $SQUIRREL_VERSION"
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}" \
  "$SQUIRREL_HOME/squirrel-sql.sh" -userdir "$USERDIR" -nos >"$WORK_DIR/squirrel.out" 2>&1 &
SQUIRREL_PID=$!

MAIN_WIN=""
for _ in $(seq 1 90); do
  MAIN_WIN="$(xdotool search --onlyvisible --name 'SQuirreL SQL Client / Version' 2>/dev/null | head -1 || true)"
  [ -n "$MAIN_WIN" ] && break
  kill -0 "$SQUIRREL_PID" 2>/dev/null || { tail -20 "$WORK_DIR/squirrel.out" >&2; die "SQuirreL exited during startup"; }
  sleep 1
done
[ -n "$MAIN_WIN" ] || die "SQuirreL main window did not appear within 90 s"
log "main window $MAIN_WIN"

# No window manager, so the frame opens at 600x400. Make it fill the screen.
xdotool windowmove "$MAIN_WIN" 0 0
xdotool windowsize "$MAIN_WIN" "$SCREEN_W" "$SCREEN_H"
xdotool windowfocus "$MAIN_WIN" 2>/dev/null || true
sleep 2

# Wait for the alias to auto-connect and the session tab to be drawn.
connected=0
for _ in $(seq 1 90); do
  colors="$(session_strip_colors)"
  if grep -q "Connecting during Application start to Alias: \"$ALIAS_NAME\"" \
      "$USERDIR/logs/squirrel-sql.log" 2>/dev/null \
    && { [ "$colors" -ge 8 ] || [ "$colors" -eq -1 ]; }; then
    connected=1
    break
  fi
  kill -0 "$SQUIRREL_PID" 2>/dev/null || break
  sleep 1
done
sleep 2

if grep -q "Connecting during Application start to Alias: \"$ALIAS_NAME\"" \
    "$USERDIR/logs/squirrel-sql.log" 2>/dev/null; then
  NOTES+=("log: SQuirreL connected to alias $ALIAS_NAME at startup")
else
  fail "SQuirreL log has no auto-connect line for alias $ALIAS_NAME"
fi
if [ "$connected" -eq 1 ]; then
  if [ -n "$CONVERT" ]; then NOTES+=("session tab strip is drawn"); else NOTES+=("session tab check skipped (no ImageMagick convert)"); fi
else
  fail "session window did not appear within 90 s"
fi
error_window_exists && fail "an Error window is open right after connecting"

shot connected "Objects tab right after auto-connect: session tab manyfold-h2-demo (PROD) is open, no dialog"

# Object tree: expand PROD > PUBLIC > TABLE so ORDERS is visible. Keyboard navigation of the tree.
click 110 186
sleep 0.5
xdotool key Right Down Down Right
sleep 1.5
xdotool key Down Right
sleep 2
shot objects-tree "Objects tab with the tree expanded down to the tables (comes from the first backend)"

# Switch to the SQL tab (mouse; keeps working without a window manager), and give the editor
# more room by dragging the divider down.
click 123 126
sleep 1.5
xdotool mousemove 700 192 mousedown 1 mousemove 700 250 mousemove 700 300 mouseup 1
sleep 1

type_and_run "SELECT * FROM orders ORDER BY id"
widen_first_column
shot select-all "SELECT * FROM orders ORDER BY id: grid with source_database prod, prod, dev"

type_and_run "SELECT count(*) FROM orders"
widen_first_column
shot count "SELECT count(*) FROM orders: one row per backend, 2 from prod and 1 from dev"

error_window_exists && fail "an Error window is open after the SELECT statements"
errors_after_selects="$(log_error_count)"
[ "$errors_after_selects" -eq 0 ] || fail "squirrel-sql.log has $errors_after_selects ERROR lines after the SELECTs"

type_and_run "DELETE FROM orders"
shot delete-refused "DELETE FROM orders: driver refuses it in read-only mode; SQuirreL shows the error"
if error_window_exists; then
  NOTES+=("DELETE: an Error window opened (informational)")
else
  NOTES+=("DELETE: no Error window; refusal is shown in the results pane (informational)")
fi

# Nothing must have been deleted: the count is still 2 from prod and 1 from dev.
type_and_run "SELECT count(*) FROM orders"
widen_first_column
shot count-after-delete "SELECT count(*) again: still 2 from prod and 1 from dev, so the refused DELETE removed nothing"

# ---- final checks -----------------------------------------------------------------------------
kill -0 "$SQUIRREL_PID" 2>/dev/null || fail "SQuirreL exited during the run"

prev=""
for f in "${SHOTS[@]}"; do
  if [ ! -s "$OUT_DIR/$f" ]; then
    fail "$f is missing or empty"
  elif [ -n "$prev" ] && cmp -s "$OUT_DIR/$prev" "$OUT_DIR/$f"; then
    fail "$f is identical to $prev, the GUI did not change"
  fi
  prev="$f"
done

if [ "${#FAILURES[@]}" -eq 0 ]; then VERDICT=PASS; else VERDICT=FAIL; fi
{
  echo "$VERDICT"
  echo "SQuirreL SQL $SQUIRREL_VERSION, manyfold jar $(basename "$MANYFOLD_JAR"), backend $(basename "$H2_JAR")"
  for n in "${NOTES[@]}"; do echo "check: $n"; done
  for f in "${FAILURES[@]:-}"; do [ -n "$f" ] && echo "FAILED: $f"; done
  for i in "${!SHOTS[@]}"; do echo "${SHOTS[$i]}: ${SHOT_NOTES[$i]}"; done
} >"$OUT_DIR/RESULT.txt"

cat "$OUT_DIR/RESULT.txt"
[ "$VERDICT" = PASS ]
