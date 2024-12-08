[![Build Status](https://jenkins.tyron.rocks/buildStatus/icon?job=quickdrop)](https://jenkins.tyron.rocks/job/quickdrop)
[![MIT License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

# QuickDrop

QuickDrop is an easy-to-use file sharing application that allows users to upload files without an account,
generate download links, and manage file availability, file encryption and optional password
protection.

This project is made with the self-hosting community in mind as a self-hosted file-sharing application.

## Features

- **File Upload**: Users can upload files without needing to create an account.
- **Adjustable file size limit**: The maximum file size can be ajusted in the settings.
- **Download Links**: Generate download links for easy sharing.
- **File Management**:
    - Manage file availability with options to keep files indefinitely or delete them.
    - Password-protected files can be updated (e.g. "kept indefinitely").
    - Add hidden files that are only accessible via their unique link.
- **Password Protection**: Optionally protect files with a password.
- **File Encryption**: Encrypt files to ensure privacy.
- **Shareable Links**: Share files with others via a unique link.
    - Generate secure shareable links that bypass app-level and file password protections.
    - Token-based access control with optional expiration times for shareable links.
- **Whole app password protection**: Optionally protect the entire app with a password.
- **QR Code Generation**: Generate QR codes for easy sharing.
- **Admin Panel**: Manage users, files, and settings.

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
