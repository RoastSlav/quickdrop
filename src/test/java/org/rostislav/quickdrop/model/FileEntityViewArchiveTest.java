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

    private static StoredFile archive(String archiveName, String manifest) {
        StoredFile file = new StoredFile();
        file.name = "files.zip";
        file.uuid = "u1";
        file.size = 2048L;
        file.archiveUpload = true;
        file.archiveName = archiveName;
        file.archiveManifest = manifest;
        return file;
    }

    @Test
    void archiveFieldsAreCarriedThroughWithAFileCount() {
        String manifest = "[{\"path\":\"docs\",\"type\":\"dir\"},"
                + "{\"path\":\"docs/a.txt\",\"size\":10,\"type\":\"file\"},"
                + "{\"path\":\"docs/b.txt\",\"size\":20,\"type\":\"file\"}]";

        FileEntityView view = new FileEntityView(archive("docs", manifest), 3L);

        assertTrue(view.archiveUpload);
        assertEquals("docs", view.archiveName);
        assertEquals(manifest, view.archiveManifest);
        assertEquals(2, view.itemCount, "directory entries describe shape, not contents");
    }

    @Test
    void plainFileHasNoArchiveFields() {
        StoredFile file = new StoredFile();
        file.name = "photo.jpg";
        file.uuid = "u2";
        file.size = 100L;

        FileEntityView view = new FileEntityView(file, 0L);

        assertFalse(view.archiveUpload);
        assertNull(view.archiveName);
        assertNull(view.archiveManifest);
        assertEquals(0, view.itemCount);
    }

    @Test
    void aPasteIsNeverAnArchive() {
        Paste paste = new Paste();
        paste.name = "notes.md";
        paste.uuid = "u3";
        paste.size = 12L;

        FileEntityView view = new FileEntityView(paste, 0L);

        assertFalse(view.archiveUpload);
        assertEquals(0, view.itemCount);
    }

    @Test
    void unreadableManifestCountsAsZeroRatherThanFailingTheRender() {
        FileEntityView view = new FileEntityView(archive("files", "not-json-at-all"), 0L);

        assertTrue(view.archiveUpload, "the flag still holds even when the manifest is broken");
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

    @Test
    void aSharedTopLevelDirectoryReadsAsAFolder() {
        String manifest = "[{\"path\":\"docs\",\"type\":\"dir\"},"
                + "{\"path\":\"docs/a.txt\",\"size\":1,\"type\":\"file\"},"
                + "{\"path\":\"docs/sub/b.txt\",\"size\":2,\"type\":\"file\"}]";

        FileEntityView view = new FileEntityView(archive("docs", manifest), 0L);

        assertFalse(view.bundle, "every entry sits under docs/");
        assertEquals(2, view.itemCount);
    }

    @Test
    void looseFilesReadAsABundle() {
        String manifest = "[{\"path\":\"a.txt\",\"size\":1,\"type\":\"file\"},"
                + "{\"path\":\"b.txt\",\"size\":2,\"type\":\"file\"}]";

        assertTrue(new FileEntityView(archive("files", manifest), 0L).bundle);
    }

    @Test
    void twoTopLevelDirectoriesReadAsABundle() {
        String manifest = "[{\"path\":\"a\",\"type\":\"dir\"},{\"path\":\"b\",\"type\":\"dir\"},"
                + "{\"path\":\"a/x.txt\",\"size\":1,\"type\":\"file\"},"
                + "{\"path\":\"b/y.txt\",\"size\":2,\"type\":\"file\"}]";

        assertTrue(new FileEntityView(archive("files", manifest), 0L).bundle,
                "no single shared root, so it was not one picked folder");
    }

    @Test
    void aFolderBesideALooseFileReadsAsABundle() {
        String manifest = "[{\"path\":\"a\",\"type\":\"dir\"},"
                + "{\"path\":\"a/x.txt\",\"size\":1,\"type\":\"file\"},"
                + "{\"path\":\"loose.txt\",\"size\":2,\"type\":\"file\"}]";

        assertTrue(new FileEntityView(archive("files", manifest), 0L).bundle);
    }
}
