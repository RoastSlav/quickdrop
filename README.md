# QuickDrop

QuickDrop is a secure, easy-to-use file sharing application that allows users to upload files without an account,
generate download links, and manage file availability, all with built-in malware scanning and optional password
protection.

## This project is still under development, and all but the most basic features are still missing.

**Available Features:**

- **File Upload**: Users can upload files without needing to create an account.
- **File list**: Users can view a list of uploaded files.
- **File Download**: Users can download files from the file list.
- **Download Links**: Generate download links for easy sharing.

## Features

- **File Upload**: Users can upload files without needing to create an account.
- **Download Links**: Generate download links for easy sharing.
- **File Management**: Manage file availability with options to keep files indefinitely or delete them.
- **Malware Scanning**: Built-in malware scanning to ensure file safety.
- **Password Protection**: Optionally protect files with a password.

## Technologies Used

- **Java**
- **Spring Boot**
- **Maven**
- **Thymeleaf**
- **SQLite**

## Getting Started

### Prerequisites

- Java 21 or higher
- Maven
- SQLite

### Installation

1. Clone the repository:
    ```sh
    git clone https://github.com/RoastSlav/quickdrop.git
    cd quickdrop
    ```

2. Configure the file save path in `src/main/resources/application.properties`:
    ```ini
    file.save.path=/path/to/save/files
    ```

3. Build the project using Maven:
    ```sh
    mvn clean install
    ```

4. Run the application:
    ```sh
    mvn spring-boot:run
    ```

### Usage

- Access the application at `http://localhost:8080`.
- Use the upload page to upload files.
- Manage files and generate download links from the file list page.

## Contributing

1. Fork the repository.
2. Create a new branch (`git checkout -b feature-branch`).
3. Make your changes.
4. Commit your changes (`git commit -m 'Add some feature'`).
5. Push to the branch (`git push origin feature-branch`).
6. Open a pull request.

## License

This project is licensed under the MIT License. See the `LICENSE` file for details.