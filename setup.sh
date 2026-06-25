#!/bin/bash
# setup.sh — instalare completa pe VM Debian/Ubuntu, fara Docker
# Ruleaza ca root: sudo bash setup.sh
set -e

PROJECT_DIR="/opt/topology-discovery"
SERVICE_USER="topology"

echo "=== [1/6] Instalare dependente ==="
apt update
apt install -y openjdk-17-jdk maven nodejs npm nginx curl

echo "=== [2/6] Creare user dedicat ==="
if ! id "$SERVICE_USER" &>/dev/null; then
    useradd -r -s /bin/false -d "$PROJECT_DIR" "$SERVICE_USER"
    echo "User '$SERVICE_USER' creat."
else
    echo "User '$SERVICE_USER' exista deja."
fi

echo "=== [3/6] Copiere proiect in /opt ==="
# Presupune ca rulezi scriptul din folderul topology-discovery/
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$SCRIPT_DIR" != "$PROJECT_DIR" ]; then
    cp -r "$SCRIPT_DIR" "$PROJECT_DIR"
fi
chown -R "$SERVICE_USER:$SERVICE_USER" "$PROJECT_DIR"

echo "=== [4/6] Build backend (JAR) ==="
cd "$PROJECT_DIR/backend"
sudo -u "$SERVICE_USER" mvn clean package -DskipTests -B
echo "JAR creat: $(ls target/*.jar)"

echo "=== [5/6] Build frontend + configurare nginx ==="
cd "$PROJECT_DIR/frontend"

# Seteaza URL-ul backend-ului - SCHIMBA cu IP-ul real al VM-ului tau
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
echo "VITE_API_BASE_URL=$BACKEND_URL" > .env

sudo -u "$SERVICE_USER" npm install
sudo -u "$SERVICE_USER" npm run build

# Copiaza build-ul React in directorul nginx
cp -r dist/* /var/www/html/

# Configureaza nginx
cat > /etc/nginx/sites-available/topology << 'NGINX'
server {
    listen 80;
    root /var/www/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }
}
NGINX

ln -sf /etc/nginx/sites-available/topology /etc/nginx/sites-enabled/topology
rm -f /etc/nginx/sites-enabled/default
nginx -t

echo "=== [6/6] Instalare servicii systemd ==="
cp "$PROJECT_DIR/topology-backend.service" /etc/systemd/system/
# pentru frontend folosim nginx direct (deja instalat ca serviciu de apt)

systemctl daemon-reload

# Activeaza si porneste nginx
systemctl enable nginx
systemctl restart nginx

# Activeaza backend (dar NU il pornim inca - trebuie setata cheia Jasypt)
systemctl enable topology-backend

echo ""
echo "========================================"
echo "Setup complet! Pasul final OBLIGATORIU:"
echo "========================================"
echo ""
echo "1. Seteaza cheia de criptare in serviciul backend:"
echo "   sudo nano /etc/systemd/system/topology-backend.service"
echo "   -> schimba linia: Environment=\"JASYPT_ENCRYPTOR_PASSWORD=schimba-aceasta-cheie\""
echo "   -> pune o cheie reala (ex: openssl rand -base64 32)"
echo ""
echo "2. Reload si pornire backend:"
echo "   sudo systemctl daemon-reload"
echo "   sudo systemctl start topology-backend"
echo ""
echo "3. Verifica ca merge:"
echo "   sudo systemctl status topology-backend"
echo "   sudo journalctl -u topology-backend -f"
echo ""
echo "4. Acceseaza:"
echo "   Frontend:   http://$(hostname -I | awk '{print $1}'):3000"
echo "   Backend API: http://$(hostname -I | awk '{print $1}'):8080/swagger-ui.html"
echo ""
