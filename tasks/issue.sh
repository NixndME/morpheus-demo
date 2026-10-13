#!/bin/bash
# Make a problem on a demo VM to show the monitoring alert, or fix it again.
ACTION='<%= customOptions.demoAction %>'
fix() {
  systemctl stop 'demo-cpu-*' demo-memory 2>/dev/null
  systemctl reset-failed 'demo-cpu-*' demo-memory 2>/dev/null
  if systemctl list-unit-files mariadb.service >/dev/null 2>&1; then systemctl start mariadb; fi
}
case "$ACTION" in
  cpu)
    # one busy loop per core at the lowest priority (the monitoring agent still runs), stops by itself after 30 minutes
    for i in $(seq 1 "$(nproc)"); do
      systemd-run --quiet --unit "demo-cpu-$i" -p RuntimeMaxSec=1800 -p Nice=19 /bin/sh -c 'while :; do :; done'
    done
    echo "CPU is now at 100% on $(hostname). The alert comes within a few minutes." ;;
  memory)
    # fill memory until about 8% is left, stops by itself after 30 minutes
    FREE=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
    LEFT=$(awk '/MemTotal/ {m = int($2/1024*0.08); print (m < 120 ? 120 : m)}' /proc/meminfo)
    MB=$((FREE - LEFT))
    [ "$MB" -gt 100 ] || { echo "Memory is already almost full on $(hostname)."; exit 0; }
    systemd-run --quiet --unit demo-memory -p RuntimeMaxSec=1800 python3 -c "import time; b = b'x' * ($MB * 1048576); time.sleep(1800)"
    sleep 8
    echo "Memory is now almost full on $(hostname) (${MB} MB taken). The alert comes within a few minutes." ;;
  stop)
    if ! systemctl list-unit-files mariadb.service >/dev/null 2>&1; then
      echo "This VM has no database. Pick a VM whose name ends with -db."; exit 1
    fi
    systemctl stop mariadb
    echo "MariaDB is stopped on $(hostname). The app turns red and the alert comes within a minute." ;;
  fix)
    fix
    echo "Fixed on $(hostname): CPU and memory are back to normal and the database runs. The alerts clear in a few minutes." ;;
  *) echo "Pick an action."; exit 1 ;;
esac
