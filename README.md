[![Build Status](https://jenkins.tyron.rocks/buildStatus/icon?job=quickdrop)](https://jenkins.tyron.rocks/job/quickdrop)
[![MIT License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Docker Pulls](https://img.shields.io/docker/pulls/roastslav/quickdrop?logo=docker&style=flat)](https://hub.docker.com/r/roastslav/quickdrop)


# QuickDrop

QuickDrop is a self-hosted file sharing app for fast, anonymous uploads with chunked transfer, optional encryption,
per-file passwords, share tokens with expiry/download limits, and an admin console to govern storage limits,
lifetime policies, cleanup schedules, notifications, and privacy settings.

# Features

## Uploads & storage
- Anonymous uploads with **chunked upload** support for reliability on large files.
- Configurable max file size, storage paths (files/logs), and max lifetime (default 30d) with “keep indefinitely” (admin-only toggleable) and renewals.
- Optional **encryption at rest** for stored files; per-file passwords hash + encrypt uploads when enabled.

## Sharing & access
- Direct links plus **token-based share links** with expirations and max-download limits; QR code generation for quick sharing.
- Hidden files (link-only) and option to disable the public file list entirely; switchable default home page (upload vs. file list).
- On-the-fly decryption for downloads; streaming responses to avoid holding files in memory.

## Security
- Whole-app password mode and separate **admin password** gate for the admin area.
- Per-file passwords; server-side session tokens for admin and file access.
- CSRF cookie enabled.

## Admin & settings
- Single-page settings UI; adjustments apply without app restart.
- Admin dashboard with file list, history, toggle hidden/visibility, delete, extend life, keep indefinitely switches, and admin dashboard button toggle.
- Configurable session timeout, file deletion cron expression, and feature flags (file list enabled, admin button enabled, encryption enabled).

## Notifications & logging
- Unified file history log for uploads, renewals, and downloads (IP + user agent).
- Email and Discord webhook notifications with optional batching (interval minutes configurable).

## Cleanup & maintenance
- Scheduled cleanup for expired files, missing-file DB rows, and expired share tokens.

---

## Technologies Used

- **Java 21**
- **Spring Boot 3.5.x** (Spring Web/MVC, Actuator)
- **Spring Security** (app/admin/password flows, CSRF cookie)
- **Spring Data JPA with Hibernate ORM 6.6** (community dialects)
- **Spring Cloud Context** (refresh scope)
- **Flyway** for DB migrations
- **SQLite** with SQLite JDBC driver
- **HikariCP** connection pooling
- **Thymeleaf** with Spring Security extras
- **Tailwind CSS** + custom JS/CSS assets
- **Spring Mail** (SMTP notifications)
- **Maven** build tooling
- **Docker** image for deployment

## Getting Started

### Installation

**Installation with Docker**

1. Pull the Docker image:

```
docker pull roastslav/quickdrop:latest
```

2. Run the Docker container:

```
docker run -d -p 8080:8080 roastslav/quickdrop:latest
```

Optional: Use volumes to persist the uploaded files when you update the container:

```
docker run -d -p 8080:8080 \
  -v /path/to/db:/app/db \
  -v /path/to/log:/app/log \
  -v /path/to/files:/app/files \
  roastslav/quickdrop
```

**Installation without Docker**

Prerequisites

- Java 21 or higher
- Maven
- SQLite

1. Clone the repository:

```
git clone https://github.com/RoastSlav/quickdrop.git
cd quickdrop
```

2. Build the application:

```
mvn clean package
```

3. Run the application:

```
java -jar target/quickdrop.jar
```

## Updates

To update the app, you need to:

1. Stop and remove the old container.
2. Pull the new image.
3. Start the updated container.

If you want to ensure file and database persistence between updates, you can use Docker volumes. (Check docker installation above)

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
