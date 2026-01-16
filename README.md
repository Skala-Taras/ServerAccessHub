# ServerAccessHub

Personal cloud storage application with a web-based file browser and terminal, deployed in Docker.

## Features

- **File Browser** — Upload, download, delete, and rename files through web interface
- **Web Terminal** — Full bash shell accessible in your browser (via xterm.js)
- **File Preview** — View PDFs, images, videos, and text files directly
- **Folder Download** — Download entire folders as ZIP archives
- **Secure** — HTTPS with SSL/TLS encryption

## Tech Stack

| Component | Technology |
|-----------|------------|
| Backend | Java 21 (pure Java, no frameworks) |
| Frontend | HTML5, CSS3, JavaScript, xterm.js |
| Build | Maven 3.9+ |
| Container | Docker & Docker Compose |
| Deployment | Ansible |

---

## Prerequisites

Before running ServerAccessHub, you need:

1. **Docker & Docker Compose** installed on your machine
2. **SSL Keystore** (`keystore.jks`) for HTTPS
3. **Environment file** (`.env`) with keystore password

### Generate SSL Keystore

```bash
keytool -genkeypair -alias serveraccesshub \
  -keyalg RSA -keysize 2048 -validity 365 \
  -keystore keystore.jks \
  -storepass YOUR_PASSWORD \
  -dname "CN=localhost, OU=Dev, O=Home, L=City, ST=State, C=US"
```

### Create .env File

Create a `.env` file in the project root:

```env
KEYSTORE_PASSWORD=YOUR_PASSWORD
```

---

## Quick Start (Docker)

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/ServerAccessHub.git
   cd ServerAccessHub
   ```

2. Generate SSL keystore (see above)

3. Create `.env` file with your keystore password

4. Build and run:
   ```bash
   docker compose up --build -d
   ```

5. Open in browser: **https://localhost:9090**

> **Note:** The browser will show a certificate warning (self-signed cert). Click "Advanced" → "Proceed" to continue.

---

## Server Deployment with Ansible

Deploy ServerAccessHub to a remote Ubuntu server using Ansible.

### What You Need

1. **Ubuntu 20.04+** server with SSH access
2. **Ansible** installed on your local machine
3. **SSH key** configured for your server
4. **Tailscale** (optional, for secure private network access)

### Step 1: Configure Inventory

Edit `ansible/inventory.ini`:

```ini
[servers]
your-server-ip ansible_user=your-username ansible_ssh_private_key_file=~/.ssh/your-key
```

### Step 2: Set Variables

Edit the variables in `ansible/playbook.yml` or create a separate vars file:

```yaml
vars:
  app_dir: "~/ServerAccessHub"
  github_repo: "https://github.com/your-username/ServerAccessHub.git"
  keystore_password: "your-secure-password"
  tailscale_ip: ""  # Optional: your Tailscale IP
```

### Step 3: Run Playbook

```bash
cd ansible
ansible-playbook -i inventory.ini playbook.yml
```

### What Ansible Does

1. Updates system packages
2. Installs Docker, Java 21, and dependencies
3. Clones the repository
4. Generates SSL keystore
5. Creates `.env` file
6. Builds and starts Docker container

---

## Tailscale Integration

[Tailscale](https://tailscale.com/) provides a secure private network to access your server without exposing ports to the internet. The application binds to the `tailscale0` interface, making it accessible only through your Tailscale network.

### Install Tailscale on Server

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

### Get Your Tailscale IP (tailscale0 interface)

```bash
tailscale ip -4
```

This returns your Tailscale IP (e.g., `100.x.x.x`) which is assigned to the `tailscale0` interface.

### Configure ServerAccessHub for Tailscale

1. Add your Tailscale IP to `.env`:
   ```env
   TAILSCALE_IP=100.x.x.x
   KEYSTORE_PASSWORD=your-password
   ```

2. Edit `docker-compose.yml` to bind only to the `tailscale0` interface:
   ```yaml
   ports:
     - "${TAILSCALE_IP}:9090:8080"
   ```
   
   This binds the container's port 8080 to your Tailscale IP on port 9090. The service will **not** be accessible from the public internet — only from devices on your Tailscale network.

3. Restart the container:
   ```bash
   docker compose down
   docker compose up -d
   ```

4. Verify binding to tailscale0:
   ```bash
   ss -tlnp | grep 9090
   # Should show: 100.x.x.x:9090
   ```

Now access your server securely at: **https://100.x.x.x:9090** (only from Tailscale-connected devices)

---

## Usage

### File Browser

- **Navigate** — Click folders to open, use breadcrumb or path bar to jump
- **Upload** — Click the + button (bottom right) or drag & drop files
- **Download** — Click on a file, or select "Download" from context menu
- **Delete/Rename** — Right-click or long-press on files for options
- **Preview** — PDFs, images, and videos open in browser

### Web Terminal

- Access at: **https://your-server:9090/terminal.html**
- Full bash shell with color support
- Working directory: `/app/cloudStorage`

---

## File Storage

Files are stored in the `cloudStorage/` directory, which is mounted as a Docker volume. Your files persist even when the container is restarted.

---

## Configuration

| Variable | Required | Description |
|----------|----------|-------------|
| `KEYSTORE_PASSWORD` | Yes | Password for SSL keystore |
| `TAILSCALE_IP` | No | Tailscale IP for private network binding |
| `TZ` | No | Timezone (default: `Europe/Warsaw`) |

---

## Ports

| Port | Description |
|------|-------------|
| 8080 | Application port (inside container) |
| 8080 | Host port (configurable in docker-compose.yml) |

---

## Project Structure

```
ServerAccessHub/
├── ansible/           # Ansible deployment files
├── cloudStorage/      # File storage (Docker volume)
├── docker/            # Docker configs
├── docs/              # Documentation
├── src/               # Java source code
├── web/               # Frontend (HTML, JS, CSS)
├── docker-compose.yml
├── Dockerfile
├── pom.xml
└── keystore.jks       # SSL certificate (you create this)
```

---

## Troubleshooting

### Certificate Warning in Browser
This is normal for self-signed certificates. Click "Advanced" → "Proceed to site".

### Cannot Connect
- Check if container is running: `docker ps`
- Check logs: `docker logs serveraccesshub`
- Verify port is open: `curl -k https://localhost:9090`

### Permission Denied
Make sure `cloudStorage/` directory has correct permissions:
```bash
chmod -R 755 cloudStorage/
```

---

## License

MIT License

---

## See Also

- [DEPLOY.md](DEPLOY.md) — Detailed deployment guide
