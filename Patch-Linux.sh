#!/bin/sh
# Lycanites Mobs - Familiar UUID Patcher (Linux / macOS)
#
# Run this after putting your Lycanites Mobs jar into the "put-jar-here" folder.
# Needs Python 3, which is preinstalled on most Linux distributions.
#
#   bash Patch-Linux.sh
#
# Options are passed through to tools/patch_jar.py (--yes, --uuid <uuid>).

set -u

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$here" || exit 1

if command -v python3 >/dev/null 2>&1; then
    python3 tools/patch_jar.py "$@"
    rc=$?
elif command -v python >/dev/null 2>&1 && \
     python -c 'import sys; raise SystemExit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
    python tools/patch_jar.py "$@"
    rc=$?
else
    echo ""
    echo "Python 3 is needed but was not found."
    echo ""
    echo "Install it with one of these, then run this script again:"
    echo "   Ubuntu / Debian / Mint:  sudo apt install python3"
    echo "   Fedora:                  sudo dnf install python3"
    echo "   Arch / Manjaro:          sudo pacman -S python"
    echo "   openSUSE:                sudo zypper install python3"
    echo ""
    exit 1
fi

if [ -t 0 ]; then
    printf '\nPress Enter to close this window...'
    read -r _
fi

exit $rc
