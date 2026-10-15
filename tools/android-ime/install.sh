#!/usr/bin/env bash
# Installs the Android keyboard ibus engine into the desktop runtime. Run inside the desktop
# (a terminal or an SSH session there), from this directory. Needs ibus with Python bindings,
# which the desktop package does not ship; ibus-daemon has to be started by the session.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)

python3 -c 'import gi; gi.require_version("IBus", "1.0"); from gi.repository import IBus' \
  || { echo "python3 cannot import IBus: install ibus and python-gobject first" >&2; exit 1; }

install -D -m 755 "$here/droiddeck-android-ime" /usr/local/bin/droiddeck-android-ime
install -D -m 644 "$here/droiddeck-android.xml" /usr/share/ibus/component/droiddeck-android.xml

if command -v gsettings >/dev/null; then
  engines=$(gsettings get org.freedesktop.ibus.general preload-engines 2>/dev/null || echo "[]")
  case "$engines" in
    *"'droiddeck-android'"*) ;;
    "[]"|"@as []") gsettings set org.freedesktop.ibus.general preload-engines "['xkb:us::eng', 'droiddeck-android']" ;;
    *) gsettings set org.freedesktop.ibus.general preload-engines "${engines%]}, 'droiddeck-android']" ;;
  esac
  echo "preload-engines: $(gsettings get org.freedesktop.ibus.general preload-engines)"
fi

sock="${XDG_RUNTIME_DIR:-/run/droiddeck}/droiddeck-ime.sock"
if pgrep -f '[d]roiddeck-android-ime' >/dev/null; then
  echo "the engine is already running"
elif ibus engine >/dev/null 2>&1; then
  setsid nohup /usr/local/bin/droiddeck-android-ime >/dev/null 2>&1 </dev/null &
  sleep 1
  [ -S "$sock" ] && pgrep -f '[d]roiddeck-android-ime' >/dev/null && echo "registered with the running ibus-daemon" \
    || { echo "the engine did not start; see ~/.cache/droiddeck-android-ime.log" >&2; exit 1; }
else
  echo "ibus-daemon is not running; the engine starts with the next session"
fi
