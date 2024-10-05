package org.rostislav.quickdrop.model;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
public class FileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;
    public String UUID;
    public String description;
    public long size;
    public boolean keepIndefinitely;
    public LocalDate uploadDate;

    @PrePersist
    public void prePersist() {
        uploadDate = LocalDate.now();
    }
}
