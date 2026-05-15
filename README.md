![Build Status](https://jenkins.tyron.rocks/buildStatus/icon?job=quickdrop/master)
[![MIT License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Docker Pulls](https://img.shields.io/docker/pulls/roastslav/quickdrop?logo=docker&style=flat)](https://hub.docker.com/r/roastslav/quickdrop)

# QuickDrop

A self-hosted file sharing and pastebin platform. Upload files or paste text, protect them with per-item passwords,
generate share links with expiry dates and download limits, and manage everything from a live admin dashboard — no
restart required for configuration changes.

---

## Contents

- [Screenshots](#screenshots)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
  - [Docker (recommended)](#docker-recommended)
  - [Docker Compose / Portainer](#docker-compose--portainer)
  - [Run without Docker](#run-without-docker)
- [Persistence](#persistence)
- [Configuration](#configuration)
- [Internationalization](#internationalization)
- [Updates](#updates)
- [Development Builds](#development-builds)
- [License](#license)

---

## Screenshots

<img width="1691" height="943" alt="Upload page" src="https://github.com/user-attachments/assets/16125ad7-98bf-4353-b779-4cd35932e1c4" />

*Upload page — drag-and-drop with configurable size limit, retention period, and encryption status.*

<img width="1703" height="943" alt="File view page" src="https://github.com/user-attachments/assets/3b65b8bc-45fc-44bc-8e7a-cb52524261b4" />

*File view page — download, renew, and generate a share link with expiry and download limits.*

<img width="1686" height="945" alt="Share link recipient view" src="https://github.com/user-attachments/assets/4ef1a580-c89a-49d6-b565-77913871ca2a" />

*Share link recipient view — clean download page, no password required.*

<img width="1667" height="944" alt="Admin dashboard" src="https://github.com/user-attachments/assets/599812fd-f3a5-49b8-9bfa-b010be3caffc" />

*Admin dashboard — aggregate stats for files and pastes, with quick links to all management pages.*

<img width="1695" height="942" alt="Admin settings" src="https://github.com/user-attachments/assets/0e5abc59-36ed-48ee-8afe-eecaf54f8b9a" />

*Admin settings — all configuration in one place, applied instantly without a restart.*


---

## Features

### File Uploads

- **Chunked uploads** — files are split into 1 MB pieces and merged server-side, making large uploads reliable over slow
  or flaky connections.
- **Folder uploads** — pick an entire directory; the folder structure and manifest are preserved.
- **Configurable limits** — set a maximum file size and a default retention period (e.g. 30 days). Users can renew
  individual files to reset the clock.
- **Keep indefinitely** — mark files exempt from scheduled deletion (admin-only option available).
- **Hide from list** — files can be link-only and not appear in the public file list (admin-only option available).

### Encryption & Passwords

- **Encryption at rest** — files uploaded with a password are AES-256/CBC encrypted on disk using a PBKDF2-derived key.
  Unencrypted files are never touched by the cipher.
- **Per-file passwords** — access is gated by a BCrypt-hashed password; a valid session token is issued on success and
  scoped to that file and browser session.
- **Global encryption toggle** — encryption and upload passwords can be disabled site-wide from the admin settings.

### Pastebin

- **Create and edit pastes** — plain text or Markdown, with syntax highlighting in the editor and viewer.
- **Password-protected pastes** — same encryption and session token flow as files.
- **View counts** — each paste view is logged; total views are shown in the admin paste list.
- **Keep indefinitely / hide** — same controls as files.

### File Previews

- **Supported formats** — images (PNG, JPEG, GIF, WebP, SVG, …), plain text and code files, PDF, JSON, and CSV/TSV.
- **SVG rasterization** — SVG files are transcoded to PNG server-side using Apache Batik before delivery. Script
  execution and external resource loading are disabled during transcoding, so the preview is sandboxed regardless of
  what the SVG contains.
- **Size gate** — previews above a configurable limit require a manual override click so large files don't auto-load.
- **Toggle** — previews can be disabled globally.

### Share Links

- **Token-based links** — generate a share URL for any file without exposing the original password.
- **Expiry date** — tokens expire on a chosen date and are cleaned up automatically.
- **Download limits** — optionally cap how many times a token can be used before it self-destructs.
- **Simplified links** — an optional mode that generates unlimited, no-expiry share links with a shorter URL.
- **QR codes** — every share link has a QR code for quick mobile sharing.
- **Encrypted files** — when a share link is created for an encrypted file, the file is re-encrypted in the background
  under a randomly generated one-time share key. That key is embedded in the share URL (`/share/{token}?key=…`) and
  never stored in plaintext — only its BCrypt hash is kept in the database. Recipients follow the link and download the
  file without needing the original password. No unencrypted copy ever touches disk. While the background encryption is
  in progress (typically seconds for small files, longer for large ones), recipients see a "File is being prepared"
  page and can refresh to check readiness.
- **Share link event logging** — creating a share token, downloading via a share link, token expiry, and admin
  revocation are all recorded in the file's history log.

### Admin Dashboard

The admin area is protected by a separate password and lives at `/admin`.

| Page            | What it shows                                                                                                                      |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------|
| **Overview**    | Aggregate stats: total downloads, storage used, average file size, paste counts and view totals                                    |
| **Files**       | Paginated list with search, per-file download counts, delete / hide / extend / keep-indefinitely actions                           |
| **Pastes**      | Paginated list with search, per-paste view counts, same actions                                                                    |
| **Share Links** | Paginated list of all active share tokens with file name, token string, expiry, remaining downloads, and a per-token Revoke button |
| **Activity**    | Global event log across all files and pastes; filterable by date range, event type, IP address, and user agent                     |
| **Settings**    | All configuration in one form, applied without restarting                                                                          |

Each file and paste has a dedicated **history page** listing every event (upload, download, share-link download,
renewal, deletion, share-link creation/expiry/revocation)
with timestamp, IP address, and user agent.

### Notifications

- **Discord webhook** — posts a message to a channel on file events.
- **Email (SMTP)** — sends the same events via configurable SMTP with STARTTLS or implicit SSL support.
- **Per-event toggles** — individually enable or disable notifications for uploads, downloads, renewals, deletions,
  share-link creation, share-link downloads, paste creation/view/edit. Noisy events (share-link downloads, paste views)
  default to off.
- **Notification batching** — queue events and flush them as a single digest at a configurable interval to avoid
  per-event spam.
- **Test buttons** — send a live test notification for Discord or email directly from the settings page.

### Scheduled Maintenance

Three automatic jobs run on configurable schedules:

| Job                     | Schedule          | What it does                                                                  |
|-------------------------|-------------------|-------------------------------------------------------------------------------|
| **File cleanup**        | Configurable cron | Deletes files older than `maxFileLifeTime` days (respects `keepIndefinitely`) |
| **DB reconciliation**   | Daily 03:00       | Removes database rows for files deleted outside the application               |
| **Share token cleanup** | Daily 03:30       | Deletes expired or exhausted share tokens                                     |

The file cleanup cron expression has a preset-button helper (hourly, daily, weekly, monthly), the input and the
expression is validated client-side before the form can be saved.

### Security

- **Application password** — optionally require a site-wide password before anything is accessible.
- **Admin password** — separate BCrypt-verified password for the `/admin` area; set via a first-run setup flow.
- **Session management** — configurable session timeout; admin and file sessions are tracked in memory and invalidated
  on logout or session expiry.
- **CSRF protection** — cookie-based CSRF token enforced on all state-changing requests.
- **Metadata stripping** — optional EXIF removal for uploaded images.
- **Input sanitisation** — folder manifests are validated as JSON; share-link content is HTML-escaped.

### Branding & Customisation

- **Custom app name** — replaces "QuickDrop" everywhere in the UI, including the browser tab.
- **Custom logo** — upload an image to replace the default favicon/logo in the nav bar.
- **Default home page** — choose whether `/` redirects to the upload page, file list, or new-paste page.
- **Feature flags** — independently toggle the file list page, admin dashboard button, pastebin, encryption, upload
  passwords, and previews.

---

## Tech Stack

| Layer           | Technology                                                    |
|-----------------|---------------------------------------------------------------|
| Language        | Java 21                                                       |
| Framework       | Spring Boot 3.5.x (Web/MVC, Security, Actuator)               |
| ORM             | Spring Data JPA + Hibernate 6.6                               |
| Database        | SQLite via Hibernate Community Dialects                       |
| Connection pool | HikariCP                                                      |
| Migrations      | Flyway                                                        |
| Templates       | Thymeleaf (with Spring Security dialect)                      |
| CSS             | Tailwind CSS + custom styles                                  |
| Runtime config  | Spring Cloud Context (`@RefreshScope`)                        |
| SVG rendering   | Apache Batik (server-side SVG-to-PNG transcoder)              |
| Email           | Spring Mail (JavaMailSender)                                  |
| Build           | Maven                                                         |
| Container       | Docker (multi-stage: Maven builder → jlink slim JRE → Alpine) |

---

## Getting Started

### Docker (recommended)

```bash
docker run -d \
  --name quickdrop \
  -p 8080:8080 \
  --restart unless-stopped \
  -v /path/to/db:/app/db \
  -v /path/to/files:/app/files \
  -v /path/to/log:/app/log \
  roastslav/quickdrop:latest
```

Open **http://localhost:8080** and follow the admin setup prompt.

### Docker Compose / Portainer

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
      - ./data/files:/app/files
      - ./data/log:/app/log
```

### Run without Docker

**Prerequisites:** Java 21+, Maven 3.9+

```bash
git clone https://github.com/RoastSlav/quickdrop.git
cd quickdrop
mvn -B clean package
java -jar target/quickdrop.jar
```

The app starts on port `8080`. The SQLite database is created at `db/quickdrop.db` and files are stored in the `files/`
directory relative to where you launch the jar.

---

## Persistence

If you run without volume mounts, all data lives inside the container and is lost when the container is removed.

| Volume       | Contents                            |
|--------------|-------------------------------------|
| `/app/db`    | SQLite database (`quickdrop.db`)    |
| `/app/files` | Uploaded files (encrypted or plain) |
| `/app/log`   | Application log (`quickdrop.log`)   |

---

## Configuration

All settings are managed at runtime through the admin settings page (`/admin/settings`). Changes apply immediately — no
restart needed.

| Setting                            | Description                                                                                                   |
|------------------------------------|---------------------------------------------------------------------------------------------------------------|
| **Max file size**                  | Maximum upload size in MB                                                                                     |
| **Max file lifetime**              | Days before a file is eligible for scheduled deletion                                                         |
| **File storage path**              | Directory where uploaded files are saved                                                                      |
| **Log storage path**               | Directory for the application log                                                                             |
| **Deletion cron**                  | Spring 6-field cron expression for the cleanup job                                                            |
| **Session timeout**                | HTTP session lifetime in minutes                                                                              |
| **Encryption**                     | Enable/disable AES encryption at rest                                                                         |
| **Upload passwords**               | Enable/disable per-file password support                                                                      |
| **Previews**                       | Enable/disable in-browser file preview                                                                        |
| **Max preview size**               | File size limit for auto-loading previews (MB)                                                                |
| **Metadata stripping**             | Strip EXIF data from uploaded images                                                                          |
| **File list page**                 | Show/hide the public file list at `/file/list`                                                                |
| **Admin dashboard button**         | Show/hide the Admin link in the nav bar for non-admin users                                                   |
| **Default home page**              | Landing page for `/` — upload, list, or paste                                                                 |
| **Keep indefinitely (admin only)** | Restrict the keep-indefinitely toggle to admins                                                               |
| **Hide from list (admin only)**    | Restrict the hide toggle to admins                                                                            |
| **Share links**                    | Enable/disable share token generation                                                                         |
| **Simplified share links**         | Generate unlimited, no-expiry share links only                                                                |
| **Pastebin**                       | Enable/disable the pastebin feature                                                                           |
| **App password**                   | Optional site-wide access password                                                                            |
| **App name**                       | Custom application display name                                                                               |
| **Logo**                           | Custom logo image (replaces the default favicon)                                                              |
| **Default language**               | Default UI language for new visitors                                                                          |
| **Discord webhook**                | Webhook URL and enable toggle for Discord notifications                                                       |
| **Email / SMTP**                   | Host, port, credentials, TLS/SSL, from address, recipients                                                    |
| **Notification batching**          | Batch notifications into digests at a set interval                                                            |
| **Notification event filters**     | Per-event on/off toggles (upload, download, renewal, deletion, share create/download, paste create/view/edit) |

---

## Internationalization

QuickDrop ships with translations for eight languages, selectable per-session via the language picker in the navigation
bar:

| Code | Language             |
|------|----------------------|
| `en` | English              |
| `de` | German               |
| `es` | Spanish              |
| `fr` | French               |
| `it` | Italian              |
| `bg` | Bulgarian            |
| `ja` | Japanese             |
| `zh` | Chinese (Simplified) |

The admin can set a default language for all visitors in the settings. Individual users can override it at any time
using the language selector.

---

## Updates

```bash
docker stop quickdrop
docker rm quickdrop
docker pull roastslav/quickdrop:latest
docker run -d \
  --name quickdrop \
  -p 8080:8080 \
  --restart unless-stopped \
  -v /path/to/db:/app/db \
  -v /path/to/files:/app/files \
  -v /path/to/log:/app/log \
  roastslav/quickdrop:latest
```

Database migrations are handled automatically by Flyway on startup. No manual SQL is needed between versions.

If you are using Docker Compose or Portainer: pull the new image and redeploy the stack.

---

## Development Builds

A development build is published under the `develop` tag:

```bash
docker pull roastslav/quickdrop:develop
```

- `:develop` tracks the latest work on the `dev` branch and may change frequently.
- It can include incomplete features, breaking changes, or debug logging.
- For stable deployments use `:latest` or a versioned tag such as `:v1.5.3`.

---

## License

MIT — see [LICENSE](LICENSE).
