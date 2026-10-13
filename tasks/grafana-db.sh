#!/bin/bash
# Grafana choice only: metrics exporters on the database VM.
set -e
[ '<%= customOptions.demoMonitoring %>' = grafana ] || { echo "Grafana not picked, nothing to do."; exit 0; }
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q prometheus-node-exporter prometheus-mysqld-exporter
# The exporter runs as the OS user prometheus and logs in over the socket.
mysql -e "CREATE USER IF NOT EXISTS 'prometheus'@'localhost' IDENTIFIED VIA unix_socket;
  GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'prometheus'@'localhost';"
printf '[client]\nuser = prometheus\nsocket = /run/mysqld/mysqld.sock\n' > /etc/mysql/exporter.cnf
chmod 644 /etc/mysql/exporter.cnf
echo 'ARGS="--config.my-cnf=/etc/mysql/exporter.cnf --no-collect.slave_status"' > /etc/default/prometheus-mysqld-exporter
# The exporter fails on install before this config exists, so clear the failed state first.
systemctl reset-failed prometheus-mysqld-exporter || true
systemctl restart prometheus-mysqld-exporter prometheus-node-exporter
sleep 3
curl -fsS localhost:9104/metrics | grep -q '^mysql_up 1' && echo "Exporters are up (node 9100, MariaDB 9104)."
