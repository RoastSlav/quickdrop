![Build Status](https://jenkins.tyron.rocks/buildStatus/icon?job=quickdrop/master)
[![MIT License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Docker Pulls](https://img.shields.io/docker/pulls/roastslav/quickdrop?logo=docker&style=flat)](https://hub.docker.com/r/roastslav/quickdrop)

# QuickDrop

QuickDrop is a self-hosted file sharing app for anonymous uploads with chunked transfers, optional encryption at rest,
per-file passwords, share tokens (expiry + download limits), and an admin console for storage/lifetime policies,
cleanup schedules, notifications, and privacy controls.

---

## Contents

- [Screenshots](#screenshots)
- [Feature highlights](#feature-highlights)
- [Technologies](#technologies)
- [Getting started](#getting-started)
  - [Docker (recommended)](#docker-recommended)
  - [Docker Compose / Portainer](#docker-compose--portainer)
  - [Run without Docker](#run-without-docker)
- [Persistence (important)](#persistence-important)
- [Updates](#updates)
- [Development builds](#development-builds)
- [License](#license)

---

## Screenshots

<img width="1296" height="998" alt="QuickDrop UI" src="https://github.com/user-attachments/assets/724267eb-7f43-4351-9167-6703ce6b5c1c" />

<img width="1315" height="1211" alt="Admin settings" src="https://github.com/user-attachments/assets/4cc67404-7631-4126-851c-75330a1c4321" />
<img width="1270" height="1211" alt="Admin analytics" src="https://github.com/user-attachments/assets/9e6a95d4-48e2-4fad-8d56-edfea63df119" />

---

## Feature highlights

### Uploads & storage

- **Anonymous uploads** with **chunked upload** support (reliable large-file transfers).
- **Folder uploads** (directory picker) with preserved structure and manifest handling.
- Configurable maximum file size, storage paths (files/logs), and default max lifetime (e.g. 30 days) with renewals.
- Optional per-file controls (admin-governed):
  - **Keep indefinitely**
  - **Hide from list** (link-only)
- Optional **encryption at rest** for stored files; **per-file passwords** supported.

### File previews

- Built-in previews for **images** and **text files**.
- Extended previews for **PDF / JSON / CSV** (and additional formats).
- Preview controls:
  - enable/disable previews
  - maximum preview size limit
- **Syntax highlighting** for code previews (including dark-theme styling).

### Sharing & access

- Direct links plus **token-based share links** with:
  - **expiration date**
  - **download limits**
- QR code generation for quick sharing.
- Share tokens are cleaned up when deleting files.
- Privacy options:
  - hidden files (link-only)
  - disable the public file list entirely
  - choose default home page (upload vs. list)

### Security

- Whole-app password mode and a separate **admin password** gate for the admin area.
- Per-file passwords; server-side session tokens for admin/file access.
- CSRF cookie enabled.

### Admin & settings

- Single-page settings UI; changes apply **without restarting** the app.
- Dashboard capabilities:
  - file list/history
  - delete (with confirmation)
  - extend lifetime
  - visibility toggles
  - optional “Admin” button toggle
- Dashboard **search + pagination** for easier browsing at scale.
- Configurable:
  - session timeout
  - cleanup cron expression
  - feature flags (file list, admin button, encryption, previews, notifications, etc.)
- Cron expression validation with clearer error handling (and next-run visibility when supported).

### Notifications & logging

- Unified file history log for uploads, renewals, downloads (and related actions), including IP + user agent.
- **Email** and **Discord webhook** notifications with built-in test actions.
- Optional **notification batching** (configurable interval) to reduce spam.

### Cleanup & maintenance

- Scheduled cleanup for expired files, missing-file DB rows, expired share tokens, and other maintenance tasks.

---

## Technologies

- **Java 21**
- **Spring Boot 3.5.x** (Spring Web/MVC, Actuator)
- **Spring Security** (app/admin/password flows, CSRF cookie)
- **Spring Data JPA** + Hibernate ORM 6.6 (community dialects)
- **Spring Cloud Context** (refresh scope)
- **Flyway** (DB migrations)
- **SQLite** (with SQLite JDBC driver)
- **HikariCP** (connection pooling)
- **Thymeleaf** (with Spring Security extras)
- **Tailwind CSS** + custom JS/CSS assets
- **Spring Mail** (SMTP notifications)
- **Maven**
- **Docker** (deployment)

---

## Getting started

### Docker (recommended)

Pull the image:

```bash
docker pull roastslav/quickdrop:latest
```

Run it:

```bash
docker run -d --name quickdrop   -p 8080:8080   --restart unless-stopped   roastslav/quickdrop:latest
```

Open:

- http://localhost:8080

### Docker Compose / Portainer

Example stack:

```yaml
services:
  quickdrop:
    image: roastslav/quickdrop:latest
    container_name: quickdrop
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./data/db:/app/db
      - ./data/log:/app/log
      - ./data/files:/app/files
```

### Run without Docker

Prerequisites:

- Java 21+
- Maven
- SQLite

```bash
git clone https://github.com/RoastSlav/quickdrop.git
cd quickdrop
mvn -B clean package
java -jar target/quickdrop.jar
```

---

## Persistence (important)

If you don’t mount volumes, your database and uploaded files live inside the container and will be lost when you recreate it.

Recommended mounts:

- `/app/db`   — SQLite DB and related app data
- `/app/files` — uploaded files
- `/app/log`   — logs (optional but useful)

---

## Updates

Typical update flow:

```bash
docker stop quickdrop
docker rm quickdrop
docker pull roastslav/quickdrop:latest
docker run -d --name quickdrop -p 8080:8080 --restart unless-stopped   -v /path/to/db:/app/db   -v /path/to/log:/app/log   -v /path/to/files:/app/files   roastslav/quickdrop:latest
```

If you’re using Compose/Portainer: pull the new image and redeploy the stack.

---

## Development builds

A **development build** is published to Docker Hub under the `develop` tag:

```bash
docker pull roastslav/quickdrop:develop
```

- `:develop` tracks the latest development work and may change frequently.
- It can include incomplete features or breaking changes.
- For stable deployments, prefer `:latest` or a versioned tag like `:v1.5.0`.

---

## License

MIT — see [LICENSE](LICENSE).
