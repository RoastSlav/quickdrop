[![Build Status](https://jenkins.tyron.rocks/buildStatus/icon?job=quickdrop)](https://jenkins.tyron.rocks/job/quickdrop)
[![MIT License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Docker Pulls](https://img.shields.io/docker/pulls/roastslav/quickdrop?logo=docker&style=flat)](https://hub.docker.com/r/roastslav/quickdrop)


# QuickDrop

QuickDrop is an easy-to-use file sharing application that allows users to upload files without an account,
generate download links, and manage file availability, file encryption and optional password
protection.

This project is made with the self-hosting community in mind as a self-hosted file-sharing application.

# Features

## File Upload
- Users can upload files without needing to create an account.  
- Supports **Chunked Uploads** for reliable file transfers.
  
---

## File Management
- Manage file availability with options to keep files indefinitely or delete them.
- Add hidden files that are only accessible via their unique link.
- Password-protected files can be updated (e.g., "kept indefinitely").

---

## Whole App Password Protection
- Protect the entire app with a password to restrict access.

---

## Shareable Links
- **Two types of share links** managed through a single, streamlined modal:
  - **Normal Links**: Require passwords if the file or app is password-protected.
  - **Token-Based (Unrestricted) Links**: Single-use links with customizable expiration days. These bypass password requirements but provide controlled access.
  - **QR Code Generation**: Generates QR codes for easy sharing.

---

## Password Protection
- Files can be protected with a password for added security.
- Password-protected files are encrypted to ensure privacy and secure storage.

---

## Admin Panel
- Centralized management for files and settings.
- Adjustable file size limits and file lifetime configurations in the admin panel.
- Logs and activity tracking for enhanced oversight.
- Disable “View Files” : Turn off the built-in file listing page for enhanced privacy (removes the "hidden" files option as it removes the need for it).

## Technologies Used

- **Java**
- **SQLite**
- **Spring Framework**
- **Spring Security**
- **Spring Data JPA (Hibernate)**
- **Spring Web**
- **Spring Boot**
- **Thymeleaf**
- **Bootstrap**
- **Maven**

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
  quickdrop
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
java -jar target/quickdrop-0.0.1-SNAPSHOT.jar
```

## Updates

To update the app, you need to:

1. Stop and remove the old container.
2. Pull the new image.
3. Start the updated container.

If you want to ensure file and database persistence between updates, you can use Docker volumes. (Check docker instalation above)

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
