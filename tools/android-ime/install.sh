#!/usr/bin/env bash
# Installs the Android keyboard ibus engine into the desktop runtime. Run inside the desktop
# (a terminal or an SSH session there), from this directory. Needs ibus with Python bindings,
# which the desktop package does not ship, and an ibus-daemon started by the session.
# The engine runs for the whole session from an autostart entry and registers itself with
# ibus-daemon; a component XML would only start it once selected, which the app cannot ask
# for before the engine's socket exists.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)

python3 -c 'import gi; gi.require_version("IBus", "1.0"); from gi.repository import IBus' \
  || { echo "python3 cannot import IBus: install ibus and python-gobject first" >&2; exit 1; }

install -D -m 755 "$here/droiddeck-android-ime" /usr/local/bin/droiddeck-android-ime
rm -f /usr/share/ibus/component/droiddeck-android.xml
install -D -m 644 /dev/stdin ~/.config/autostart/droiddeck-android-ime.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=DroidDeck Android keyboard
Comment=ibus engine fed by the Android soft keyboard
Exec=/usr/local/bin/droiddeck-android-ime
X-KDE-autostart-phase=2
EOF

# Not in preload-engines: ibus-daemon checks that list at startup, before this engine has
# registered, and shows "configured input methods does not exist" for names it cannot find.
if command -v gsettings >/dev/null; then
  engines=$(gsettings get org.freedesktop.ibus.general preload-engines 2>/dev/null || echo "[]")
  case "$engines" in
    *"droiddeck-android"*)
      cleaned=$(printf '%s' "$engines" | sed -E "s/, *'droiddeck-android'//; s/'droiddeck-android', *//; s/\[ *'droiddeck-android' *\]/[]/")
      gsettings set org.freedesktop.ibus.general preload-engines "$cleaned"
      echo "preload-engines: $(gsettings get org.freedesktop.ibus.general preload-engines)" ;;
  esac
fi

sock="${XDG_RUNTIME_DIR:-/run/droiddeck}/droiddeck-ime.sock"
pkill -f '[d]roiddeck-android-ime' 2>/dev/null && sleep 1 || true
setsid nohup /usr/local/bin/droiddeck-android-ime >/dev/null 2>&1 </dev/null &
sleep 2
[ -S "$sock" ] && pgrep -f '[d]roiddeck-android-ime' >/dev/null && echo "running; listening on $sock" \
  || { echo "the engine did not start; see ~/.cache/droiddeck-android-ime.log" >&2; exit 1; }
tail -n 3 ~/.cache/droiddeck-android-ime.log
