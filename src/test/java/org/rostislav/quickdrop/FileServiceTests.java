package org.rostislav.quickdrop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.rostislav.quickdrop.TestDataContainer.*;

@SpringBootTest
@ExtendWith(MockitoExtension.class)
@Disabled
public class FileServiceTests {
    @Nested
    class SaveFileTests {
        @Autowired
        FileService fileService;
        @MockBean
        FileRepository fileRepository;
        @MockBean
        PasswordEncoder passwordEncoder;
        @Value("${file.save.path}")
        private String fileSavePath;

        @AfterEach
        void tearDown() {
            //Delete the all files in the fileSavePath
            try {
                Files.walk(Path.of(fileSavePath))
                        .filter(Files::isRegularFile)
                        .forEach(file -> {
                            try {
                                Files.delete(file);
                            } catch (
                                    IOException e) {
                                e.printStackTrace();
                            }
                        });
            } catch (
                    IOException e) {
                e.printStackTrace();
            }
        }

        // Successfully saves an unencrypted file when no password is provided
        @Test
        void test_save_unencrypted_file_without_password() {
            File file = mock(File.class);

            FileEntity fileEntity = getFileEntity();
            fileEntity.passwordHash = null;
            when(fileRepository.save(any(FileEntity.class))).thenReturn(fileEntity);

            FileEntity result = fileService.saveFile(file, getFileUploadRequest());

            assertNotNull(result);
            assertEquals("test.txt", result.name);
            assertEquals("Test description", result.description);
            assertEquals(1024L, result.size);
            assertNull(result.passwordHash);
        }

        // Successfully saves an encrypted file when a password is provided
        @Test
        void test_save_encrypted_file_with_password() {
            File file = mock(File.class);

            FileEntity fileEntity = getFileEntity();
            when(passwordEncoder.encode(anyString())).thenReturn(fileEntity.passwordHash);
            when(fileRepository.save(any(FileEntity.class))).thenReturn(fileEntity);

            FileEntity result = fileService.saveFile(file, getFileUploadRequest());

            assertNotNull(result);
            assertEquals("test.txt", result.name);
            assertEquals("Test description", result.description);
            assertEquals(1024L, result.size);
            assertNotNull(result.passwordHash);
        }

        // Correctly encodes password when provided
        @Test
        void test_correctly_encodes_password_when_provided() {
            File file = mock(File.class);

            FileEntity fileEntity = getFileEntity();
            when(passwordEncoder.encode("securePassword")).thenReturn(fileEntity.passwordHash);
            when(fileRepository.save(any(FileEntity.class))).thenReturn(fileEntity);

            FileEntity result = fileService.saveFile(file, getFileUploadRequest());

            assertNotNull(result);
            assertEquals(fileEntity.passwordHash, result.passwordHash);
        }

        @Test
        void test_handles_empty_file_upload_request_gracefully() {
            File file = mock(File.class);

            when(fileRepository.save(any(FileEntity.class))).thenAnswer(invocation -> {
                FileEntity fileEntity = invocation.getArgument(0);
                fileEntity.id = 1L; // Simulate database assigning an ID
                return fileEntity;
            });

            FileEntity result = fileService.saveFile(file, getEmptyFileUploadRequest());

            assertNotNull(result);
            assertEquals("test.txt", result.name);
            assertNull(result.description); // Changed to match the empty request
            assertEquals(1024L, result.size);
            assertNull(result.passwordHash);
        }

        @Test
        void test_handles_null_file_upload_request() {
            File file = mock(File.class);
            FileUploadRequest fileUploadRequest = null;

            when(fileRepository.save(any(FileEntity.class))).thenReturn(getFileEntity());

            FileEntity result = fileService.saveFile(file, fileUploadRequest);

            assertNull(result);
        }

        @Test
        void test_handle_null_or_empty_multipartfile() {
            File file = mock(File.class);

            FileEntity result = fileService.saveFile(file, getFileUploadRequest());

            assertNull(result);
        }
    }
}