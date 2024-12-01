package org.rostislav.quickdrop;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
public class QuickdropApplication {
    private static final Logger logger = LoggerFactory.getLogger(QuickdropApplication.class);
    @Value("${file.save.path}")
    private String fileSavePath;

    public static void main(String[] args) {
        SpringApplication.run(QuickdropApplication.class, args);
    }

    @PostConstruct
    public void createFileSavePath() {
        try {
            Files.createDirectories(Path.of(fileSavePath));
            logger.info("File save path created: {}", fileSavePath);
        } catch (
                Exception e) {
            logger.error("Failed to create file save path: {}", fileSavePath);
        }
    }
}
