package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
public class FileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;
    public String uuid;
    public String description;
    public long size;
    public boolean keepIndefinitely;
    public LocalDate uploadDate;
    public String passwordHash;
    @Column(columnDefinition = "boolean default false")
    public boolean hidden;

    @PrePersist
    public void prePersist() {
        uploadDate = LocalDate.now();
    }

    @Override
    public String toString() {
        return "FileEntity{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", uuid='" + uuid + '\'' +
                ", description='" + description + '\'' +
                ", size=" + size +
                ", keepIndefinitely=" + keepIndefinitely +
                ", uploadDate=" + uploadDate +
                ", passwordHash='" + passwordHash + '\'' +
                '}';
    }
}
