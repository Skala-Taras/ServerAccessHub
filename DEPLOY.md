# ServerAccessHub - Server Deployment Guide

Deploy your personal cloud on Ubuntu server.

## Requirements

- Ubuntu 20.04+ server
- SSH access
- Ansible installed on your local machine

## Quick Deploy with Ansible

### 1. Install Ansible (on your local machine)

```bash
# Ubuntu/Debian
sudo apt install ansible

# macOS
brew install ansible

# Windows (WSL)
sudo apt install ansible
```

### 2. Configure

Copy the example env file:
```bash
cd ansible
cp .env.yml.example .env.yml
```

Edit `ansible/.env.yml`:
```yaml
app_dir: ~/ServerAccessHub
github_repo: "https://github.com/YOUR_USERNAME/ServerAccessHub.git"
keystore_password: "your_secure_password"
```

Edit `ansible/inventory.ini`:
```ini
[servers]
YOUR_SERVER_IP ansible_user=ubuntu ansible_ssh_private_key_file=~/.ssh/id_rsa
```

### 3. Deploy

```bash
cd ansible
ansible-playbook -i inventory.ini playbook.yml
```

### 4. Access

Open in browser: `https://YOUR_SERVER_IP:8080`

## Manual Install (without Ansible)

### 1. Update system

```bash
sudo apt update && sudo apt upgrade -y
```

### 2. Install Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
```

Log out and back in.

### 3. Clone project

```bash
cd /opt
sudo git clone https://github.com/YOUR_USERNAME/ServerAccessHub.git
cd ServerAccessHub
```

### 4. Generate SSL certificate

```bash
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
  -validity 365 -keystore keystore.jks -storepass changeme123 \
  -dname "CN=localhost, OU=Dev, O=Personal, L=City, ST=State, C=US"
```

### 5. Start

```bash
docker compose up --build -d
```

### 6. Check status

```bash
docker ps
docker compose logs -f
```

## Firewall

Open port 8080:

```bash
sudo ufw allow 8080/tcp
sudo ufw enable
```

## Update

```bash
cd /opt/ServerAccessHub
git pull
docker compose up --build -d
```

## Stop

```bash
docker compose down
```

## Logs

```bash
docker compose logs -f
```

## Files

Your files are stored in `cloudStorage/` folder.

To backup:
```bash
tar -czf backup.tar.gz cloudStorage/
```
