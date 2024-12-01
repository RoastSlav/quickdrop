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
- **File Management**: Manage file availability with options to keep files indefinitely or delete them.
- **Password Protection**: Optionally protect files with a password.
- **File Encryption**: Encrypt files to ensure privacy.
- **Whole app password protection**: Optionally protect the entire app with a password.

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

Optional: Use volumes to persist the uploaded files:

```
docker run -d -p 8080:8080 \
  -v /path/to/db:/app/db \
  -v /path/to/log:/app/log \
  -v /path/to/files:/files \
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

Using an external application.properties file:

- Create an **application.properties** file in the same directory as the JAR file or specify its location in the
  start command.

    - Add your custom settings, for example (Listed below are the default values):

```
file.save.path=files
file.max.age=30 # days
logging.file.name=log/quickdrop.log
file.deletion.cron=0 0 2 * * *
app.basic.password=test
app.enable.password=false
max-upload-file-size=1GB
```

- Run the application with the external configuration (If not in the same directory as the JAR file):

```
java -jar target/quickdrop-0.0.1-SNAPSHOT.jar --spring.config.location=./application.properties
```

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
