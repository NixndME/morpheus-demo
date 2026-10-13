#!/bin/bash
# Install MariaDB, open it to the web tier and load the app tables.
set -e
PASS='<%= customOptions.demoPassword %>'
PASS="${PASS//\\/}"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q mariadb-server
cat > /etc/mysql/mariadb.conf.d/60-demo.cnf <<'EOF'
[mysqld]
bind-address = 0.0.0.0
innodb_buffer_pool_size = 64M
performance_schema = off
EOF
systemctl restart mariadb
mysql <<EOF
CREATE DATABASE IF NOT EXISTS demo;
CREATE USER IF NOT EXISTS 'demo'@'%' IDENTIFIED BY '${PASS}';
ALTER USER 'demo'@'%' IDENTIFIED BY '${PASS}';
GRANT ALL ON demo.* TO 'demo'@'%';
FLUSH PRIVILEGES;
EOF
echo '@@SCHEMA@@' | base64 -d | mysql demo
echo "MariaDB is ready on $(hostname -I | awk '{print $1}')."
