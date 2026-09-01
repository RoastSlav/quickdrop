# QuickDrop

Self-hosted file sharing, pastebin and URL shortener. Upload files or paste text, protect them with per-item passwords,
hand out expiring share links, and administer everything from a web admin panel that applies changes without a restart.

![Build Status](https://jenkins.tyron.rocks/buildStatus/icon?job=quickdrop/master)
![Coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fjenkins.tyron.rocks%2Fjob%2Fquickdrop%2Fjob%2Fmaster%2FlastSuccessfulBuild%2Fartifact%2Fcoverage-badge.json)
[![MIT License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Docker Pulls](https://img.shields.io/docker/pulls/roastslav/quickdrop?logo=docker&style=flat)](https://hub.docker.com/r/roastslav/quickdrop)

---

## Contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Requirements](#requirements)
- [Installation](#installation)
    - [Docker](#docker)
    - [Docker Compose](#docker-compose)
    - [Build and run manually](#build-and-run-manually)
- [Configuration](#configuration)
    - [Startup properties](#startup-properties)
    - [Data directories](#data-directories)
    - [Runtime settings](#runtime-settings)
- [Usage](#usage)
- [Admin panel](#admin-panel)
- [Internationalization](#internationalization)
- [Updating](#updating)
- [Development](#development)
- [Contributing](#contributing)
- [License](#license)

---

## Features

### File uploads

- Chunked uploads — the browser splits files into 4 MB chunks and the server merges them in the background, so large
  uploads survive flaky connections.
- Folder uploads — a picked directory is zipped in the browser (ZIP64, stored uncompressed) with a JSON manifest of the
  original paths and sizes.
- Configurable maximum file size and default retention period; users can renew a file to reset its deletion date.
- `Keep indefinitely` exempts a file from scheduled deletion. `Hide from list` keeps it off the public file list. Either
  toggle can be restricted to admins.
- Optional client-side EXIF/metadata stripping for images before upload (files up to 25 MB).

### Encryption and passwords

- Files uploaded with a password are encrypted at rest with AES-256/GCM in 4 MB chunks, keyed by PBKDF2. Files encrypted
  by older versions (monolithic GCM, and AES-256/CBC from 1.x) are still readable.
- Per-file passwords are BCrypt-hashed; a successful unlock issues a session token scoped to that file and browser
  session.
- Encryption and upload passwords can each be turned off site-wide.

### Pastebin

- Plain-text or Markdown pastes with syntax highlighting in both the editor and the viewer.
- Password-protected pastes use the same encryption and session flow as files.
- Edit-only mode — anyone can read the paste, only the password holder can change it.
- Immutable flag — locks the content permanently.
- Per-paste view counts and a history log.
- Pastes are capped at 10 MB, or the configured max file size, whichever is smaller.

### File previews

- Images (PNG, JPEG, GIF, WebP, SVG), plain text and code, PDF, JSON, and CSV/TSV.
- SVGs are transcoded to PNG server-side with Apache Batik; script execution and external resource loading are disabled
  during transcoding.
- Previews above a configurable size require a manual click instead of auto-loading.

### Share links

- Generate a share URL for a file without revealing its password.
- Optional expiry date and download-count cap; expired and exhausted tokens are cleaned up on a schedule.
- Simplified mode issues unlimited, non-expiring links with a shorter URL.
- Every share link has a QR code (SVG and PNG).
- When nothing gates a file — no per-file password and no app password — the share panel offers the page link and a QR
  code directly instead of the limiter form.
- For encrypted files, a one-time share key is generated and the file is re-encrypted under it in the background. The
  key travels in the URL; only its BCrypt hash is stored. Recipients see a
  "being prepared" page while that runs.
- Creation, download, expiry and revocation are all recorded in the file's history.

### Link shortener

A general-purpose URL shortener at `/link/new`, sharing the short-code table with share links.

- Shorten any URL into a `/s/{code}` link with a QR code; no file involved.
- Custom aliases — request a specific code instead of a random one. Admin-only by default.
- Optional expiry date and use-count cap.
- Destinations are validated on creation and re-validated on every visit: `http`/`https` only, and never loopback,
  link-local or private-network addresses. Admins can add a domain allowlist or blocklist.
- Optional threat-intelligence checks against Phishing Army, URLhaus and Google Safe Browsing. All three ship disabled
  and each requires the admin to accept its licence terms in the settings UI first. Safe Browsing only ever receives a
  truncated hash of the destination.
- Interstitial preview page showing the destination before continuing — always, never, or non-admins only (default).
- Per-visit logging can be switched off independently of the link's own use counters.

### Storage backends

Files can be stored somewhere other than the server's disk. The backend is chosen in the admin settings and applies
without a restart.

| Backend              | Configuration                                                            |
|----------------------|--------------------------------------------------------------------------|
| Local disk (default) | File storage path                                                        |
| S3                   | Endpoint, bucket, region, access/secret key, path-style flag, key prefix |
| Azure Blob Storage   | Connection string, container, key prefix                                 |
| SFTP                 | Host, port, username, password or private key, base path, known-hosts    |
| WebDAV               | URL, username, password, key prefix                                      |

- Test connection verifies credentials before you commit to a backend.
- The migration tool at `/admin/storage-migration` moves existing files between backends, with a preflight check first.
- Backend health is polled; the UI shows a banner when it is unreachable, and notifications can fire when it goes down
  and comes back.

### Database backups

- Scheduled backups on a cron expression, keeping the newest N. Off by default.
- On-demand backup regardless of the schedule.
- Upload a backup file from elsewhere, so restoring is not limited to backups this instance made.
- Backups are plain SQLite files produced with `VACUUM INTO`, so one can be copied over
  `db/quickdrop.db` by hand if the app will not start.
- Download a backup off the server.
- Every candidate is validated with `PRAGMA quick_check` before it is allowed to replace the live database.
- Restoring from the UI stages the file and restarts the app; the restore is applied at boot.
- Backups, uploads, restores and failures are all logged to the activity log.

### Activity log

- Every file, paste, share, short-link, admin and system event is recorded with timestamp, IP address and user agent.
- Filterable by date range, event type, IP, user agent and source type.
- Export the current filter to CSV from the Activity page.
- Optional retention sweep — per-category age limits (files, pastes, shares, short links, admin, system; 365 days each
  by default) on a cron schedule. Rows are written to a CSV archive on the configured storage backend under
  `activity-archive/` and only deleted once that archive is committed. Archives are never pruned. Off by default.

### Notifications

- Discord webhook and SMTP email, with per-event toggles for uploads, downloads, renewals, deletions, share creation and
  download, paste create/view/edit, and storage up/down.
- Noisy events (share-link downloads, paste views) default to off.
- Optional batching into a digest at a configurable interval.
- Test buttons send a live notification from the settings page.

### Security

- Optional site-wide application password.
- Separate BCrypt-verified admin password, set through a first-run setup flow.
- Rate limiting: 10 requests per 60-second window per client address on the file-password, admin-password, share,
  share-download, link-creation and `/s/{code}` endpoints; over the limit returns HTTP 429 with `Retry-After: 60`.
- Cookie-based CSRF protection on state-changing requests; session cookies are `HttpOnly` and
  `SameSite=Strict`.
- Configurable session timeout; admin and file sessions are tracked and bounded in memory.
- Outbound URLs (Discord webhook, remote storage backends, short-link destinations) are blocked from resolving to
  internal network addresses.
- `X-Forwarded-For` / `X-Real-IP` are honoured for client-IP resolution only when the trusted-proxy setting is on.

### Branding and feature switches

- Custom application name and logo.
- Choose which page `/` lands on: upload, file list, or new paste.
- Independently enable or disable uploads, the file list page, the pastebin, share links, the link shortener,
  encryption, upload passwords, previews and the admin dashboard button. Uploads and the shortener can be made
  admin-only. Admins keep access to disabled features; other visitors get a service-unavailable page.

---

## Screenshots

<img width="2116" height="1155" alt="image" src="https://github.com/user-attachments/assets/d3f8c350-f368-4182-b5ec-886be4dfd27f" />

*Upload page — drag-and-drop with configurable size limit, retention period, and encryption status.*

<img width="2175" height="1308" alt="image" src="https://github.com/user-attachments/assets/3d687b60-b3ac-4f62-ae09-8f56dad14176" />

*File view page — download, renew, and generate a share link with expiry and download limits.*

<img width="2175" height="1303" alt="image" src="https://github.com/user-attachments/assets/ed502d6b-1087-4f9a-8411-bed7f04c5af5" />

*File list — every non-hidden upload. Can be disabled in the settings.*

<img width="2217" height="1300" alt="image" src="https://github.com/user-attachments/assets/9e1cb812-0a67-4a52-a234-f33518433ff8" />

*Share link recipient view — download page, no password required.*

<img width="2148" height="1299" alt="image" src="https://github.com/user-attachments/assets/2e903985-bf93-48b0-afe8-b86882d73a4b" />

*Admin dashboard — aggregate stats for files and pastes, with links to the management pages.*

<img width="2194" height="1305" alt="image" src="https://github.com/user-attachments/assets/c3a7290a-5c1a-4771-b316-e59504ee2fcc" />

*Admin settings — applied without a restart.*

<img width="2116" height="1302" alt="image" src="https://github.com/user-attachments/assets/13117bd0-fcbb-49f5-89ad-a63b46d7c194" />

*Activity — every event recorded in the app.*

---

## Requirements

|                             |                                                                                                    |
|-----------------------------|----------------------------------------------------------------------------------------------------|
| Docker install              | Docker with Compose, or any OCI runtime. Images are published for `linux/amd64` and `linux/arm64`. |
| Manual install              | JDK 21, Maven 3.9+                                                                                 |
| Frontend rebuild (optional) | Node.js with npm, for the Tailwind CLI                                                             |
| Database                    | None to install — SQLite, created on first start                                                   |

---

## Installation

### Docker

```bash
docker run -d \
  --name quickdrop \
  -p 8080:8080 \
  --restart unless-stopped \
  -v /path/to/db:/app/db \
  -v /path/to/db-backups:/app/db-backups \
  -v /path/to/files:/app/files \
  -v /path/to/log:/app/log \
  -v /path/to/branding:/app/branding \
  -v /path/to/reputation-feeds:/app/reputation-feeds \
  roastslav/quickdrop:latest
```

Open <http://localhost:8080> and follow the admin setup prompt to set the admin password.

### Docker Compose

The repository ships a `docker-compose.yml`:

```bash
docker compose up -d
```

### Build and run manually

```bash
git clone https://github.com/RoastSlav/quickdrop.git
```

```bash
./mvnw -B clean package
```

```bash
java -jar target/quickdrop.jar
```

The app listens on port 8080. The database, files and logs are created relative to the working directory you launch
from — see [Data directories](#data-directories).

---

## Configuration

Configuration is split in two. A small set of startup properties lives in
`src/main/resources/application.properties` and is fixed once the app starts; everything else is a runtime setting
managed from the admin panel and applied immediately.

### Startup properties

Override any of these with a JVM argument (`-Dserver.port=9000`), a command-line argument (`--server.port=9000`), or the
equivalent environment variable using Spring Boot's relaxed binding (`SERVER_PORT=9000`).

| Property                                  | Default                                                          | Purpose                                                                                                                                                                                      |
|-------------------------------------------|------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `server.port`                             | `8080`                                                           | HTTP listen port. Not set in the file; this is Spring Boot's default                                                                                                                         |
| `spring.datasource.url`                   | `jdbc:sqlite:db/quickdrop.db?journal_mode=WAL&busy_timeout=5000` | SQLite location. WAL allows one writer; `busy_timeout` makes concurrent writers wait instead of failing with `SQLITE_BUSY`                                                                   |
| `logging.file.name`                       | `log/quickdrop.log`                                              | Application log file. Not set in the file — it is derived at startup from the **Log storage path** runtime setting; setting it explicitly here or on the command line overrides that setting |
| `logging.structured.format.file`          | `logstash`                                                       | The log file is structured JSON; the console stays human-readable                                                                                                                            |
| `quickdrop.cors.allowed-origins`          | `*`                                                              | Comma-separated origins for credentialed CORS                                                                                                                                                |
| `server.tomcat.max-parameter-count`       | `500`                                                            | Raised because the admin settings form posts 100+ fields                                                                                                                                     |
| `server.tomcat.connection-timeout`        | `60000`                                                          | Connection timeout, ms                                                                                                                                                                       |
| `spring.mvc.async.request-timeout`        | `3600000`                                                        | Async request timeout, ms — covers long downloads                                                                                                                                            |
| `spring.threads.virtual.enabled`          | `true`                                                           | Tomcat's request executor and Spring's `@Async` executor run on virtual threads                                                                                                              |
| `server.servlet.session.cookie.same-site` | `strict`                                                         | Session cookie `SameSite`                                                                                                                                                                    |
| `server.servlet.session.cookie.http-only` | `true`                                                           | Session cookie `HttpOnly`                                                                                                                                                                    |
| `spring.flyway.baseline-on-migrate`       | `true`                                                           | Migrations run automatically at startup                                                                                                                                                      |
| `app.version`                             | `2.0.0`                                                          | Version shown in the admin About tab                                                                                                                                                         |

The published image sets no application-specific environment variables of its own.

### Data directories

All paths are relative to the process working directory (`/app` in the image).

| Path                | Contents                                                                                                                | Declared as a Docker volume |
|---------------------|-------------------------------------------------------------------------------------------------------------------------|-----------------------------|
| `db/`               | SQLite database `quickdrop.db`                                                                                          | Yes                         |
| `db-backups/`       | Database backups                                                                                                        | Yes                         |
| `files/`            | Uploaded files, plus `.upload-chunks/` staging during chunked uploads. Only used when the storage backend is Local disk | Yes                         |
| `log/`              | `quickdrop.log`. Relocatable via the **Log storage path** runtime setting, which takes effect on the next restart       | Yes                         |
| `branding/`         | Uploaded custom logo                                                                                                    | Yes                         |
| `reputation-feeds/` | Cached Phishing Army and URLhaus feeds                                                                                  | Yes                         |

### Runtime settings

Managed at `/admin/settings`, stored in the database, applied without a restart — the one exception is **Log storage
path**, which the logging system reads before the database is open and so only picks up on the next restart. Grouped as
they appear in the settings tabs.

#### Files

| Setting                        | Default       | Description                                                                                                                                          |
|--------------------------------|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| Max file size                  | 1 GB          | Maximum upload size                                                                                                                                  |
| Max file lifetime              | 30 days       | Age at which a file becomes eligible for scheduled deletion                                                                                          |
| File storage path              | `files`       | Directory used by the Local disk backend                                                                                                             |
| Log storage path               | `log`         | Directory the application log is written to. Read at startup, so a change takes effect on the next restart — unlike every other setting on this page |
| File deletion cron             | `0 0 2 * * *` | Schedule for the cleanup job                                                                                                                         |
| Previews                       | on            | In-browser file preview                                                                                                                              |
| Max preview size               | 5 MB          | Above this, previews need a manual click                                                                                                             |
| Metadata stripping             | off           | Strip EXIF from images in the browser before upload                                                                                                  |
| Keep indefinitely (admin only) | off           | Restrict the keep-indefinitely toggle                                                                                                                |
| Hide from list (admin only)    | off           | Restrict the hide toggle                                                                                                                             |

#### Features

| Setting                                 | Default       | Description                                                                |
|-----------------------------------------|---------------|----------------------------------------------------------------------------|
| Uploads / uploads admin-only            | on / off      | Enable uploads, or restrict them to admins                                 |
| File list page                          | on            | The public list at `/file/list`                                            |
| Pastebin                                | off           | The pastebin feature                                                       |
| Share links                             | off           | Share-token generation                                                     |
| Simplified share links                  | off           | Unlimited, non-expiring links with a shorter URL                           |
| Share token length                      | 8             | Characters in a share token                                                |
| Link shortener / admin-only             | on / off      | Redirect links at `/link/new`                                              |
| Short code length                       | 5             | Characters in a generated short code                                       |
| Custom aliases / admin-only             | on / on       | Human-chosen link codes                                                    |
| Destination preview page                | `NON_ADMIN`   | `ALWAYS`, `NEVER`, or `NON_ADMIN`                                          |
| Domain rule mode / rules                | `OFF` / empty | `OFF`, allowlist or blocklist for link destinations                        |
| Link visit logging                      | on            | Record each short-link visit. Use counters always update                   |
| Reputation checking                     | off           | Master switch for threat-intelligence checks                               |
| Phishing Army / URLhaus / Safe Browsing | off           | Per-provider, each gated behind licence acceptance                         |
| Reputation fail-closed                  | off           | Block a link when a provider check cannot complete, instead of allowing it |
| Reputation feed cron                    | `0 0 4 * * *` | Feed refresh schedule. Safe Browsing is queried live                       |
| URLhaus Auth-Key, Safe Browsing API key | empty         | Provider credentials                                                       |
| Admin dashboard button                  | on            | Show the Admin link in the nav bar                                         |

#### Security

| Setting                     | Default        | Description                                                             |
|-----------------------------|----------------|-------------------------------------------------------------------------|
| App password                | off            | Site-wide access password                                               |
| Admin password              | unset          | Set through the first-run setup flow at `/admin/setup`                  |
| Session lifetime            | 30 minutes     | HTTP session timeout                                                    |
| Encryption                  | on             | AES-256/GCM encryption at rest for password-protected uploads           |
| Upload passwords            | on             | Per-file password support                                               |
| Trusted reverse proxy       | off            | Honour `X-Forwarded-For` / `X-Real-IP`. Only enable behind a real proxy |
| Activity retention          | off            | Enable the retention sweep                                              |
| Activity retention cron     | `0 30 3 * * *` | Sweep schedule                                                          |
| Retention days per category | 365 each       | Files, pastes, shares, short links, admin, system                       |

#### Appearance

| Setting           | Default  | Description                                                    |
|-------------------|----------|----------------------------------------------------------------|
| App name          | empty    | Replaces "QuickDrop" in the UI and browser tab                 |
| Logo              | none     | Uploaded to `branding/`, replaces the default logo and favicon |
| Default language  | `en`     | Default UI language for new visitors                           |
| Default home page | `upload` | Where `/` redirects: `upload`, `list` or `paste`               |

#### Notifications

| Setting                                | Default     | Description                                                                                                                                                    |
|----------------------------------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Discord webhook / URL                  | off / empty | Webhook notifications                                                                                                                                          |
| Email notifications                    | off         | SMTP notifications                                                                                                                                             |
| SMTP host / port / username / password | empty / 587 | SMTP server                                                                                                                                                    |
| SMTP TLS / SSL                         | off / off   | STARTTLS or implicit SSL                                                                                                                                       |
| Email from / to                        | empty       | Sender and recipients                                                                                                                                          |
| Notification batching / interval       | off         | Queue events and send a single digest                                                                                                                          |
| Per-event toggles                      | mixed       | Upload, download, renewal, deletion, share create, paste create, paste edit, storage up and storage down default on; share download and paste view default off |

#### Storage

| Setting           | Default                              | Description                                                              |
|-------------------|--------------------------------------|--------------------------------------------------------------------------|
| Storage backend   | `LOCAL`                              | `LOCAL`, `S3`, `AZURE`, `SFTP` or `WEBDAV`                               |
| S3                | region `us-east-1`, key prefix empty | Endpoint, bucket, region, access key, secret key, path-style, key prefix |
| Azure             | key prefix empty                     | Connection string, container name, key prefix                            |
| SFTP              | port 22, base path `/`               | Host, port, username, password or private key, base path, known-hosts    |
| WebDAV            | key prefix empty                     | URL, username, password, key prefix                                      |
| Scheduled backups | off                                  | Automatic database backups                                               |
| Backup cron       | `0 0 4 * * *`                        | Backup schedule                                                          |
| Backups to keep   | 7                                    | Older backups are pruned after each run                                  |

---

## Usage

**Upload a file.** Go to `/file/upload`, drag in a file or folder, optionally set a password and a description, and
upload. You get a file page at `/file/{uuid}` with the download link, the deletion date, and a renew button.

**Create a paste.** Go to `/file/paste/new`, pick plain text or Markdown, optionally set a password, edit-only mode or
the immutable flag, and save.

**Share something.** On a file or paste page, open the share panel. If the item is gated by a password, you get a share
link with an optional expiry date and download cap. If nothing gates it, you get the page link and a QR code instead.

**Shorten a URL.** Go to `/link/new`, paste a URL, optionally request a custom alias, an expiry date or a use cap. You
get a `/s/{code}` link and a QR code.

**Retrieve something.** Open the link. Password-protected items prompt for the password; share links carry their own key
and do not.

Key routes:

| Route             | Purpose                                       |
|-------------------|-----------------------------------------------|
| `/`               | Redirects to the configured default home page |
| `/file/upload`    | Upload page                                   |
| `/file/list`      | Public file list                              |
| `/file/{uuid}`    | File page                                     |
| `/file/paste/new` | New paste                                     |
| `/link/new`       | New short link                                |
| `/s/{code}`       | Short-link redirect                           |
| `/share/{token}`  | Share-link recipient page                     |
| `/admin`          | Admin area                                    |

---

## Admin panel

Reachable at `/admin`, protected by its own password. On first run, `/admin/setup` prompts you to set it; once set, the
setup endpoint refuses to overwrite it.

| Page              | Route                      | What it does                                                                        |
|-------------------|----------------------------|-------------------------------------------------------------------------------------|
| Dashboard         | `/admin/dashboard`         | Aggregate stats: downloads, storage used, average file size, paste counts and views |
| Files             | `/admin/files`             | Paginated, searchable list with delete, hide, extend and keep-indefinitely actions  |
| Pastes            | `/admin/pastes`            | The same for pastes, with per-paste view counts and history                         |
| Links             | `/admin/links`             | Share links and redirect links, with expiry, remaining uses and a revoke button     |
| Activity          | `/admin/activity`          | Event log filterable by date, type, IP, user agent and source; CSV export           |
| Backups           | `/admin/backups`           | Create, upload, download, restore and delete database backups                       |
| Storage migration | `/admin/storage-migration` | Move files between storage backends, with a preflight check                         |
| Settings          | `/admin/settings`          | All runtime settings                                                                |

Settings are organised into seven tabs: Appearance, Features, Files, Security, Notifications, Storage and About.
Sections with five or more rows collapse by default.

Each file and paste also has a history page listing every event — upload, download, share-link download, renewal,
deletion, share creation, expiry and revocation — with timestamp, IP address and user agent.

### Scheduled jobs

| Job                     | Schedule                                        | What it does                                                                            |
|-------------------------|-------------------------------------------------|-----------------------------------------------------------------------------------------|
| File cleanup            | `fileDeletionCron`, default `0 0 2 * * *`       | Deletes files past `maxFileLifeTime`, respecting keep-indefinitely                      |
| Database reconciliation | Daily 03:00                                     | Removes rows for files deleted outside the application                                  |
| Share token cleanup     | Daily 03:30                                     | Deletes expired or exhausted share tokens                                               |
| Activity retention      | `activityRetentionCron`, default `0 30 3 * * *` | Archives and purges old activity rows. Off by default                                   |
| Database backup         | `backupCron`, default `0 0 4 * * *`             | Creates a backup and prunes to `maxBackups`. Off by default                             |
| Reputation feed refresh | `reputationFeedCron`, default `0 0 4 * * *`     | Re-downloads the Phishing Army and URLhaus feeds. Only runs when those providers are on |

---

## Internationalization

Eight languages, selectable per session from the language picker in the navigation bar. The admin sets the default for
new visitors.

| Code | Language             | Bundle                   |
|------|----------------------|--------------------------|
| `en` | English              | `messages.properties`    |
| `de` | German               | `messages_de.properties` |
| `es` | Spanish              | `messages_es.properties` |
| `fr` | French               | `messages_fr.properties` |
| `it` | Italian              | `messages_it.properties` |
| `bg` | Bulgarian            | `messages_bg.properties` |
| `ja` | Japanese             | `messages_ja.properties` |
| `zh` | Chinese (Simplified) | `messages_zh.properties` |

---

## Updating

```bash
docker compose pull && docker compose up -d
```

For a plain `docker run` deployment, stop and remove the container, pull, and start it again with the same volume
mounts:

```bash
docker stop quickdrop && docker rm quickdrop && docker pull roastslav/quickdrop:latest
```

Flyway runs the database migrations at startup. Check the release notes for the version you are moving to before
upgrading — some releases need a configuration change as well.

---

## Development

```bash
./mvnw -B clean verify
```

```bash
./mvnw -B clean package
```

```bash
npm install && npm run tw:build
```

`verify` runs the test suite and enforces a 70% JaCoCo line-coverage gate. `tw:build` regenerates
`static/css/tailwind.css` from `tailwind-input.css`.

The running app serves resources from `target/classes`, so an edit under
`src/main/resources/static` only reaches the browser after a build or:

```bash
./mvnw process-resources
```

Images are published by Jenkins: `:develop` from the `dev` branch on every build, and `:latest`
plus `:v{version}` from `master` when the `pom.xml` version changes. Both are multi-arch (`linux/amd64`, `linux/arm64`).

| Tag        | Source                                                                    |
|------------|---------------------------------------------------------------------------|
| `:latest`  | Latest release from `master`                                              |
| `:v2.0.0`  | Specific release                                                          |
| `:develop` | Latest `dev` build — may include incomplete features and breaking changes |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Security issues are covered
by [SECURITY.md](SECURITY.md).

---

## License

MIT — see [LICENSE](LICENSE).
