#!/bin/bash
# Grafana choice only: Prometheus and Grafana on the web VM, with the demo dashboard and alert.
set -e
[ '<%= customOptions.demoMonitoring %>' = grafana ] || { echo "Grafana not picked, nothing to do."; exit 0; }
PASS='<%= customOptions.demoPassword %>'
PASS="${PASS//\\/}"
DB_IP=$(grep -oP "'db_host' => '\K[^']+" /etc/demo-app/config.php)
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q prometheus prometheus-node-exporter apt-transport-https gnupg
cat > /etc/prometheus/prometheus.yml <<EOF
global:
  scrape_interval: 10s
scrape_configs:
  - job_name: web
    static_configs: [{ targets: ['localhost:9100'], labels: { tier: web } }]
  - job_name: db
    static_configs: [{ targets: ['$DB_IP:9100'], labels: { tier: database } }]
  - job_name: mysql
    static_configs: [{ targets: ['$DB_IP:9104'], labels: { tier: database } }]
EOF
echo 'ARGS="--storage.tsdb.retention.time=2d"' > /etc/default/prometheus
systemctl restart prometheus

mkdir -p /etc/apt/keyrings
curl -fsSL https://apt.grafana.com/gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/grafana.gpg
echo 'deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main' > /etc/apt/sources.list.d/grafana.list
apt-get update -q
apt-get install -y -q grafana
echo '@@GRAFANA@@' | base64 -d | tar -xz -C /etc/grafana/provisioning
cat > /etc/grafana/grafana.ini <<EOF
[security]
admin_user = admin
admin_password = $PASS
[auth.anonymous]
enabled = true
org_role = Viewer
[dashboards]
default_home_dashboard_path = /etc/grafana/provisioning/dashboards/demo-app.json
[analytics]
reporting_enabled = false
check_for_updates = false
[news]
news_feed_enabled = false
[plugins]
preinstall_disabled = true
preinstall_auto_update = false
EOF
chown -R root:grafana /etc/grafana
systemctl enable grafana-server
systemctl restart grafana-server
# the first start runs many database migrations; on a small VM that takes a few minutes
for i in $(seq 1 200); do curl -fsS -o /dev/null localhost:3000/api/health 2>/dev/null && break; sleep 3; done
curl -fsS -o /dev/null localhost:3000/api/health || { echo "Grafana did not start in 10 minutes."; exit 1; }
echo "Grafana is up at http://$(hostname -I | awk '{print $1}'):3000/d/demo-app (user admin, the order password)."
