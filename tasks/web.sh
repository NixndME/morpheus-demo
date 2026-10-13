#!/bin/bash
# Install Apache + PHP, put the app in place and point it at the database tier.
set -e
PASS='<%= customOptions.demoPassword %>'
PASS="${PASS//\\/}"
# The database tier IP comes from the connected tier in the blueprint.
DB_IP=$(env | grep -iE '^[A-Z_]*DATABASE[A-Z_]*_IP=' | head -n1 | cut -d= -f2)
[ -z "$DB_IP" ] && DB_IP=$(env | grep -E '_IP=' | grep -vE '^(SSH|SERVER|MORPHEUS)_' | head -n1 | cut -d= -f2)
echo "Variables with an IP: $(env | grep -E '_IP=' | cut -d= -f1 | tr '\n' ' ')"
case "$DB_IP" in ''|0.0.0.0) echo "No database tier IP found."; exit 1 ;; esac
WEB_IP=$(hostname -I | awk '{print $1}')

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q apache2 libapache2-mod-php php-mysql
rm -f /var/www/html/index.html
echo '@@APP@@' | base64 -d | tar -xz -C /var/www/html

MON='<%= customOptions.demoMonitoring %>'
case "$MON" in
  grafana) MON_NAME=Grafana; MON_URL="http://$WEB_IP:3000/d/demo-app" ;;
  opsramp) MON_NAME=OpsRamp; MON_URL=$(echo '<%= cypher.read("secret/demo-opsramp-url") %>' | sed -E 's/.*"value":"([^"]*)".*/\1/') ;;
  *) MON_NAME=None; MON_URL='' ;;
esac

# plan ids of the sizes, filled in by Set up
case '<%= customOptions.demoSize %>' in
@@SIZES@@
  *) SIZE='' ;;
esac
case '<%= customOptions.demoBackup %>' in
  1) BACKUP='Every day at midnight' ;;
  2) BACKUP='Every Sunday at midnight' ;;
  *) BACKUP='No backup' ;;
esac

mkdir -p /etc/demo-app
cat > /etc/demo-app/config.php <<EOF
<?php return [
  'db_host' => '$DB_IP', 'db_user' => 'demo', 'db_pass' => '$PASS', 'db_name' => 'demo', 'web_ip' => '$WEB_IP',
  'order' => [
    'app_name' => '<%= customOptions.demoAppName %>',
    'environment' => '<%= customOptions.demoEnv %>',
    'owner' => '<%= customOptions.demoOwner %>',
    'ordered' => '$(date -u '+%Y-%m-%d %H:%M UTC')',
    'size' => '$SIZE',
    'monitoring' => '$MON_NAME',
    'monitoring_url' => '$MON_URL',
    'backup' => '$BACKUP',
    'cost_owner' => '<%= customOptions.demoCostOwner %>',
    'cost_id' => '<%= customOptions.demoCostId %>',
  ],
];
EOF
chown root:www-data /etc/demo-app/config.php
chmod 640 /etc/demo-app/config.php
systemctl restart apache2
curl -fsS -o /dev/null http://localhost/ && echo "App is up at http://$WEB_IP/ and uses the database at $DB_IP."
