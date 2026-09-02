package org.rostislav.quickdrop.model;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.StoredFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list pages, the admin table and the share landing page all render this projection, so
 * whether it carries the archive fields decides whether those surfaces can show anything
 * about an archive's contents at all.
 */
class FileEntityViewArchiveTest {

    private static StoredFile archive(String folderName, String manifest) {
        StoredFile file = new StoredFile();
        file.name = "files.zip";
        file.uuid = "u1";
        file.size = 2048L;
        file.folderUpload = true;
        file.folderName = folderName;
        file.folderManifest = manifest;
        return file;
    }

    @Test
    void archiveFieldsAreCarriedThroughWithAFileCount() {
        String manifest = "[{\"path\":\"docs\",\"type\":\"dir\"},"
                + "{\"path\":\"docs/a.txt\",\"size\":10,\"type\":\"file\"},"
                + "{\"path\":\"docs/b.txt\",\"size\":20,\"type\":\"file\"}]";

        FileEntityView view = new FileEntityView(archive("docs", manifest), 3L);

        assertTrue(view.folderUpload);
        assertEquals("docs", view.folderName);
        assertEquals(manifest, view.folderManifest);
        assertEquals(2, view.itemCount, "directory entries describe shape, not contents");
    }

    @Test
    void plainFileHasNoArchiveFields() {
        StoredFile file = new StoredFile();
        file.name = "photo.jpg";
        file.uuid = "u2";
        file.size = 100L;

        FileEntityView view = new FileEntityView(file, 0L);

        assertFalse(view.folderUpload);
        assertNull(view.folderName);
        assertNull(view.folderManifest);
        assertEquals(0, view.itemCount);
    }

    @Test
    void aPasteIsNeverAnArchive() {
        Paste paste = new Paste();
        paste.name = "notes.md";
        paste.uuid = "u3";
        paste.size = 12L;

        FileEntityView view = new FileEntityView(paste, 0L);

        assertFalse(view.folderUpload);
        assertEquals(0, view.itemCount);
    }

    @Test
    void unreadableManifestCountsAsZeroRatherThanFailingTheRender() {
        FileEntityView view = new FileEntityView(archive("files", "not-json-at-all"), 0L);

        assertTrue(view.folderUpload, "the flag still holds even when the manifest is broken");
        assertEquals(0, view.itemCount);
    }

    @Test
    void missingManifestCountsAsZero() {
        assertEquals(0, new FileEntityView(archive("files", null), 0L).itemCount);
        assertEquals(0, new FileEntityView(archive("files", "  "), 0L).itemCount);
        assertEquals(0, new FileEntityView(archive("files", "{}"), 0L).itemCount);
    }

    @Test
    void aFlatBundleCountsEveryEntry() {
        String manifest = "[{\"path\":\"a.txt\",\"size\":1,\"type\":\"file\"},"
                + "{\"path\":\"b.txt\",\"size\":2,\"type\":\"file\"},"
                + "{\"path\":\"c.txt\",\"size\":3,\"type\":\"file\"}]";

        assertEquals(3, new FileEntityView(archive("files", manifest), 0L).itemCount);
    }
}
