#!/bin/bash
# OpsRamp choice only: install and register the OpsRamp agent. Keys and tenant come from Morpheus Cypher.
set -e
[ '<%= customOptions.demoMonitoring %>' = opsramp ] || { echo "OpsRamp not picked, nothing to do."; exit 0; }
# Cypher gives back {"value":"..."}, keep only the value.
val() { echo "$1" | sed -E 's/.*"value":"([^"]*)".*/\1/'; }
KEY=$(val '<%= cypher.read("secret/demo-opsramp-key") %>')
SECRET=$(val '<%= cypher.read("secret/demo-opsramp-secret") %>')
SERVER=$(val '<%= cypher.read("secret/demo-opsramp-server") %>')
TENANT=$(val '<%= cypher.read("secret/demo-opsramp-tenant") %>')
[ -n "$KEY" ] && [ -n "$SECRET" ] && [ -n "$SERVER" ] && [ -n "$TENANT" ] || { echo "OpsRamp settings are missing in Cypher."; exit 1; }
if systemctl is-active --quiet opsramp-agent; then echo "OpsRamp agent is already running."; exit 0; fi

json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
TOKEN=$(curl -fsS -X POST "https://$SERVER/tenancy/auth/oauth/token" -H 'Accept: application/json' \
  -d "client_id=$KEY&client_secret=$SECRET&grant_type=client_credentials" | json '["access_token"]')
INFO=$(curl -fsS -H "Authorization: Bearer $TOKEN" "https://$SERVER/api/v2/tenants/$TENANT/agents/LINUX/info?distName=ubuntu&architecture=amd64")
PKG=$(echo "$INFO" | json '["name"]')
SUM=$(echo "$INFO" | json '["checksum"]')
echo "Downloading $PKG"
curl -fsSL -H "Authorization: Bearer $TOKEN" "https://$SERVER/api/v2/tenants/$TENANT/agents/LINUX/download/$PKG" -o "/tmp/$PKG"
echo "$SUM  /tmp/$PKG" | sha256sum -c -
dpkg -i --force-confnew "/tmp/$PKG" || DEBIAN_FRONTEND=noninteractive apt-get install -f -y -q
rm -f "/tmp/$PKG"
/opt/opsramp/agent/bin/configure -K "$KEY" -S "$SECRET" -s "$SERVER" -c "$TENANT" -f 'agent:RemoteCommand,agent:Automation'
for i in $(seq 1 30); do systemctl is-active --quiet opsramp-agent && break; sleep 2; done
systemctl is-active opsramp-agent && echo "OpsRamp agent is running."
