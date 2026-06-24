#!/bin/bash
# rebuild.sh — recompileaza si restarteaza serviciile dupa modificari de cod
# Ruleaza ca root: sudo bash rebuild.sh
set -e

PROJECT_DIR="/opt/topology-discovery"
SERVICE_USER="topology"

echo "=== Rebuild backend ==="
cd "$PROJECT_DIR/backend"
sudo -u "$SERVICE_USER" mvn clean package -DskipTests -B
systemctl restart topology-backend
echo "Backend restartat. Status:"
systemctl status topology-backend --no-pager -l

echo ""
echo "=== Rebuild frontend ==="
cd "$PROJECT_DIR/frontend"
sudo -u "$SERVICE_USER" npm run build
cp -r dist/* /var/www/html/
systemctl reload nginx
echo "Frontend actualizat."

echo ""
echo "Gata. Logs backend in timp real: journalctl -u topology-backend -f"
