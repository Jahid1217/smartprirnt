#!/bin/sh
set -eu
umask 022
agent_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
artifact=${1:-smartprint-agent_1.0.0_all.deb}
staging=$(mktemp -d)
trap 'rm -rf -- "$staging"' EXIT
chmod 0755 "$staging"
install -d "$staging/DEBIAN" "$staging/usr/bin" "$staging/usr/lib/smartprint-agent/app" \
    "$staging/lib/systemd/system" "$staging/usr/lib/sysusers.d"
cat > "$staging/DEBIAN/control" <<'CONTROL'
Package: smartprint-agent
Version: 1.0.0
Section: utils
Priority: optional
Architecture: all
Maintainer: SmartPrint
Depends: python3 (>= 3.10), python3-cups, python3-dotenv, python3-requests, systemd (>= 252), cups
Description: SmartPrint desktop printing service for CUPS
 Discovers configured CUPS queues and prints orders approved by your SmartPrint shop.
CONTROL
install -m 0644 "$agent_dir"/app/*.py "$staging/usr/lib/smartprint-agent/app/"
install -m 0644 "$agent_dir/packaging/linux/smartprint-agent.service" "$staging/lib/systemd/system/"
printf '%s\n' 'u smartprint-agent - "SmartPrint Agent" /var/lib/smartprint-agent' \
    > "$staging/usr/lib/sysusers.d/smartprint-agent.conf"
cat > "$staging/usr/bin/smartprint-agent" <<'WRAPPER'
#!/bin/sh
if [ "${1:-}" = "--pair" ]; then
    if [ "$(id -u)" -ne 0 ]; then
        echo "Run pairing with sudo so systemd can encrypt the service credential." >&2
        exit 1
    fi
    install -d -m 0700 /etc/smartprint-agent
    export SMARTPRINT_CREDENTIAL_FILE=/etc/smartprint-agent/credentials.enc
    export AGENT_STATE_DIR=/etc/smartprint-agent
fi
cd /usr/lib/smartprint-agent
exec /usr/bin/python3 -m app.main "$@"
WRAPPER
cat > "$staging/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -eu
if [ "$1" = configure ]; then
    systemd-sysusers smartprint-agent.conf
    if [ -d /run/systemd/system ]; then
        systemctl daemon-reload
        if systemctl is-active --quiet smartprint-agent.service; then
            systemctl restart smartprint-agent.service
        fi
    fi
fi
POSTINST
cat > "$staging/DEBIAN/prerm" <<'PRERM'
#!/bin/sh
set -eu
if [ "$1" = remove ] && [ -d /run/systemd/system ]; then
    systemctl stop smartprint-agent.service
    systemctl disable smartprint-agent.service
fi
PRERM
cat > "$staging/DEBIAN/postrm" <<'POSTRM'
#!/bin/sh
set -eu
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload
fi
POSTRM
chmod 0755 "$staging/usr/bin/smartprint-agent" "$staging/DEBIAN/"postinst \
    "$staging/DEBIAN/"prerm "$staging/DEBIAN/"postrm
dpkg-deb --root-owner-group --build "$staging" "$artifact"
