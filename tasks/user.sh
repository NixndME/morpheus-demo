#!/bin/bash
# Make the login user "demo" with the password from the order.
set -e
PASS='<%= customOptions.demoPassword %>'
[ -n "$PASS" ] || { echo "No password in the order."; exit 1; }
id demo >/dev/null 2>&1 || useradd -m -s /bin/bash -G sudo demo
echo "demo:${PASS//\\/}" | chpasswd
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config
rm -f /etc/ssh/sshd_config.d/60-cloudimg-settings.conf
systemctl reload ssh || systemctl reload sshd || true
echo "User demo is ready."
