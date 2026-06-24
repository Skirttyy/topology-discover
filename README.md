# Topology Discovery — L2/L3 (Juniper & Arista)

Teza de an — Informatică Aplicată, anul 2.
Aplicație de descoperire automată a topologiei de rețea L2/L3, multi-vendor (Juniper Junos, Arista EOS), folosind LLDP + SNMP + SSH config push.

## Arhitectură

```
backend/   → Spring Boot (Java 17), REST API + WebSocket, discovery engine BFS
frontend/  → React + React Flow, grafic interactiv al topologiei
```

**Flux principal:**
1. Dai un subnet (CIDR) sau un device individual + credențiale + vendor
2. Backend-ul scanează subnet-ul, găsește device-uri vii (port SSH deschis)
3. Pentru fiecare device: SSH login → activează automat SNMP v2c + LLDP (dacă lipsesc) → citește hostname/model/versiune
4. SNMP walk pe LLDP-MIB → găsește vecinii direcți → BFS recursiv pe toată topologia
5. SNMP walk pe ARP table → completează informația L3 (IP-uri pe interfețe)
6. Graful complet e expus prin `/api/topology` și randat în React Flow

---

## Rulare locală (pe calculatorul tău)

### Cerințe
- Java 17+ (JDK)
- Maven 3.8+
- Node.js 20+ și npm
- Acces de rețea de la calculatorul tău către lab-ul EVE-NG (management network)

### 1. Backend

```bash
cd backend

# Seteaza cheia de criptare (Jasypt) - OBLIGATORIU, nu porni fara asta
export JASYPT_ENCRYPTOR_PASSWORD="o-cheie-puternica-orice-vrei-tu"

# Profilul "local" foloseste H2 (fisier local, nu necesita Postgres instalat)
mvn spring-boot:run
```

Backend-ul pornește pe `http://localhost:8080`.
Poți verifica că merge accesând `http://localhost:8080/swagger-ui.html` (documentație API interactivă).

Consolă H2 (ca să vezi datele direct, opțional): `http://localhost:8080/h2-console`
(JDBC URL: `jdbc:h2:file:./data/topology-db`, user `sa`, fără parolă)

### 2. Frontend

Într-un terminal nou:

```bash
cd frontend
npm install

# copiaza si ajusteaza daca e nevoie (implicit arata deja spre localhost:8080)
cp .env.example .env

npm run dev
```

Frontend-ul pornește pe `http://localhost:5173`. Deschide-l în browser.

### 3. Testare

1. Click pe **"+ Scaneaza subnet"** în colțul stânga-sus
2. Completează subnet-ul lab-ului tău EVE-NG (ex: `192.168.100.0/24`), vendor-ul, user/parolă SSH ale device-urilor
3. Apasă **"Pornește scanarea + discovery"**
4. Urmărește bara de progres din colțul stânga-jos (live, prin WebSocket)
5. La final, graful se reîncarcă automat — click pe orice nod pentru detalii

---

## Deploy pe Proxmox

Recomandare: un VM/CT separat de EVE-NG, cu o interfață de rețea care are rută către rețeaua de management a lab-ului (bridge-ul/segmentul pe care EVE-NG expune IP-urile de management ale device-urilor).

### 1. Pregătire VM/CT

```bash
# pe Debian/Ubuntu, in interiorul VM-ului/CT-ului din Proxmox
sudo apt update
sudo apt install -y docker.io docker-compose-plugin git
sudo systemctl enable --now docker
```

### 2. Verifică conectivitatea către lab-ul EVE-NG

**Important, înainte de orice altceva** — din acest VM, trebuie să poți ajunge la IP-urile de management ale device-urilor din EVE-NG:

```bash
ping <ip-management-al-unui-device-din-lab>
nc -zv <acelasi-ip> 22
```

Dacă nu merge, verifică în Proxmox:
- VM-ul/CT-ul tău și EVE-NG sunt pe același bridge Linux (vmbrX), SAU
- ai rute configurate explicit între segmentele de rețea
- dacă EVE-NG rulează el însuși ca VM cu interfețe multiple, verifică pe ce bridge e interfața de management a topologiei tale

Dacă rețeaua de management a lab-ului nu e direct rutabilă către containerul Docker (de exemplu pentru că e izolată pe un vSwitch intern al EVE-NG), ai două opțiuni:
- **Opțiunea simplă**: rulează backend-ul cu `network_mode: host` în `docker-compose.yml` (deja comentat acolo ca indicație) — containerul folosește direct stack-ul de rețea al VM-ului gazdă, deci dacă VM-ul gazdă vede lab-ul, și containerul îl vede.
- **Opțiunea curată**: adaugă o interfață de rețea suplimentară VM-ului din Proxmox, conectată direct la bridge-ul de management al EVE-NG.

### 3. Clonează/copiază proiectul pe VM

```bash
# daca ai pus codul pe un repo Git
git clone <repo-ul-tau> topology-discovery
cd topology-discovery

# SAU, daca lucrezi doar local, copiaza folderul prin scp:
# scp -r topology-discovery/ user@<ip-vm-proxmox>:/home/user/
```

### 4. Configurează variabilele de mediu

```bash
cp .env.example .env
nano .env
```

Completează:
- `DB_PASSWORD` — orice parolă puternică pentru Postgres
- `JASYPT_ENCRYPTOR_PASSWORD` — cheia de criptare a credențialelor device-urilor (genereaz-o cu `openssl rand -base64 32`)
- `VITE_API_BASE_URL` — IP-ul VM-ului tău din Proxmox, cel la care vei accesa backend-ul din browser-ul tău local (ex: `http://192.168.1.50:8080`)

### 5. Build & pornire

```bash
docker compose up -d --build
```

Verifică starea containerelor:

```bash
docker compose ps
docker compose logs -f backend
```

### 6. Accesează aplicația

- Frontend: `http://<ip-vm-proxmox>:3000`
- Backend Swagger UI: `http://<ip-vm-proxmox>:8080/swagger-ui.html`

### 7. Oprire / restart

```bash
docker compose down          # opreste tot, pastreaza datele (volumul Postgres)
docker compose down -v       # opreste tot SI sterge datele (reset complet)
docker compose restart backend
```

---

## Configurare device-uri în EVE-NG — ce trebuie să existe deja

Aplicația **activează automat SNMP v2c + LLDP** la primul contact cu fiecare device (nu trebuie să le configurezi manual înainte). Singura cerință e ca device-urile să aibă deja:

- O interfață de management cu IP fix, accesibilă din rețeaua unde rulează backend-ul
- SSH activat (de regulă activ implicit pe imaginile vJunos/vMX și cEOS din EVE-NG)
- Un user/parolă valide pentru SSH

Comenzile exacte trimise automat de aplicație, pentru referință (vezi și `JuniperAdapter.java` / `AristaAdapter.java`):

**Juniper (Junos):**
```
configure
set snmp community <community> authorization read-only
set protocols lldp interface all
set protocols lldp port-id-subtype interface-name
commit and-quit
```

**Arista (EOS):**
```
configure terminal
snmp-server community <community> ro
lldp run
end
write memory
```

---

## Structura proiectului

```
topology-discovery/
├── backend/
│   ├── src/main/java/com/topo/discovery/
│   │   ├── DiscoveryApplication.java
│   │   ├── controller/      → DeviceController, DiscoveryController, TopologyController
│   │   ├── service/         → DiscoveryEngineService (BFS), GraphBuilderService (JGraphT),
│   │   │                       BootstrapConfigService, SubnetScannerService, DeviceService
│   │   ├── vendor/          → VendorAdapter (interfata), JuniperAdapter, AristaAdapter
│   │   ├── collector/       → SshCommandExecutor (JSch), SnmpCollector (SNMP4J)
│   │   ├── model/           → Device, NetworkInterface, Link (entitati JPA)
│   │   ├── repository/      → Spring Data JPA repos
│   │   ├── dto/              → request/response-uri API
│   │   ├── security/        → CredentialEncryptionService (Jasypt)
│   │   └── config/           → CorsConfig, WebSocketConfig
│   ├── pom.xml
│   └── Dockerfile
├── frontend/
│   ├── src/
│   │   ├── components/      → TopologyGraph, DeviceNode, DeviceDetailsPanel,
│   │   │                       DiscoveryControls, DiscoveryStatusBar
│   │   ├── api/client.js    → toate apelurile catre backend
│   │   └── App.jsx
│   ├── package.json
│   └── Dockerfile
└── docker-compose.yml
```

## Note pentru documentația tezei

- **Algoritmul de discovery e un BFS clasic** pe un graf necunoscut a priori, descoperit incremental (vezi `DiscoveryEngineService.runBfs()`) — bun punct de plecare pentru capitolul de fundamentare teoretică (teoria grafurilor aplicată).
- **Pattern Adapter** e folosit explicit pentru suportul multi-vendor (`VendorAdapter` + `JuniperAdapter`/`AristaAdapter` + `VendorAdapterFactory`) — adăugarea unui vendor nou (ex: Cisco IOS-XR) necesită doar o clasă nouă, fără să atingi restul codului. Bun exemplu de design pattern aplicat practic.
- **Limitare cunoscută documentată**: LLDP-MIB standard (`lldpRemSysName` etc.) nu garantează IP-ul de management al vecinului direct în tabelele de bază folosite — aplicația leagă vecinii cunoscuți după hostname; o extensie ar interoga și `lldpRemManAddrTable` pentru rezolvare IP completă.
- **Criptarea credențialelor** (Jasypt, AES-256) e un punct bun de discutat la securitate — parolele SSH/community SNMP nu sunt niciodată stocate sau expuse în clar către frontend.
