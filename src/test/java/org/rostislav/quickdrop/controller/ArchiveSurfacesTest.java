package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * An archive upload is only useful if the pages a recipient actually opens say what is
 * inside it. Every surface here renders {@code FileEntityView}, so these cover the archive
 * fields reaching the templates -- the list pages by badge and count, the share landing page
 * by the manifest the contents tree is drawn from.
 *
 * <p>Each list assertion filters by a unique name so the page contains exactly one row and
 * cannot be satisfied by a file some other test left behind.
 */
class ArchiveSurfacesTest extends ControllerTestSupport {

    private static final String MANIFEST = "[{\"path\":\"docs\",\"type\":\"dir\"},"
            + "{\"path\":\"docs/alpha.txt\",\"size\":10,\"type\":\"file\"},"
            + "{\"path\":\"docs/beta.txt\",\"size\":20,\"type\":\"file\"},"
            + "{\"path\":\"docs/gamma.txt\",\"size\":30,\"type\":\"file\"}]";

    @BeforeEach
    void adminPasswordExists() {
        // Without one, AdminPasswordSetupInterceptor redirects every route to /admin/setup.
        ensureAdminPasswordSet();
    }

    private StoredFile archive(String name) throws Exception {
        return createArchiveFile(name, "zipbytes".getBytes(), "docs", MANIFEST);
    }

    private UploadShareLink readyShareToken(StoredFile file, String keyHash) {
        UploadShareLink token = createShareToken(file, LocalDate.now().plusDays(1), 5);
        token.sidecarReady = true;
        token.shareKeyHash = keyHash;
        return shareTokenRepository.save(token);
    }

    @Test
    void publicList_marksAnArchiveWithItsItemCount() throws Exception {
        archive("surfaces-archive.zip");

        mockMvc.perform(get("/file/list").param("query", "surfaces-archive"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("surfaces-archive.zip")))
                .andExpect(content().string(containsString("Folder")))
                .andExpect(content().string(containsString("3 items")));
    }

    @Test
    void publicList_leavesAPlainFileUnmarked() throws Exception {
        createFile("surfaces-plain.bin", "hello".getBytes());

        mockMvc.perform(get("/file/list").param("query", "surfaces-plain"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("surfaces-plain.bin")))
                .andExpect(content().string(not(containsString("Folder"))));
    }

    @Test
    void adminList_marksAnArchive() throws Exception {
        MockHttpSession session = adminSession();
        archive("surfaces-admin.zip");

        mockMvc.perform(get("/admin/files").session(session).param("query", "surfaces-admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("surfaces-admin.zip")))
                .andExpect(content().string(containsString("3 items")));
    }

    @Test
    void shareLanding_showsTheContentsOfAnArchive() throws Exception {
        StoredFile file = archive("surfaces-share.zip");
        UploadShareLink token = readyShareToken(file, null);

        mockMvc.perform(get("/share/" + token.code))
                .andExpect(status().isOk())
                .andExpect(view().name("file-share-view"))
                .andExpect(content().string(containsString("archiveManifestData")))
                .andExpect(content().string(containsString("docs/alpha.txt")))
                .andExpect(content().string(containsString("3 items")));
    }

    @Test
    void shareLanding_leavesAPlainFileWithoutATree() throws Exception {
        StoredFile file = createFile("surfaces-share-plain.bin", "hello".getBytes());
        UploadShareLink token = readyShareToken(file, null);

        mockMvc.perform(get("/share/" + token.code))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("archiveManifestData"))));
    }

    /**
     * The fragment key has not been verified at this point, so the token alone has not yet
     * proved access. File names say more than the archive's own name does, so they must not
     * be rendered until the key check passes.
     */
    @Test
    void shareLanding_withholdsContentsUntilTheKeyIsVerified() throws Exception {
        StoredFile file = archive("surfaces-keyed.zip");
        UploadShareLink token = readyShareToken(file, "$2a$10$abcdefghijklmnopqrstuv");

        mockMvc.perform(get("/share/" + token.code))
                .andExpect(status().isOk())
                .andExpect(view().name("file-share-view"))
                .andExpect(content().string(containsString("surfaces-keyed.zip")))
                .andExpect(content().string(not(containsString("archiveManifestData"))))
                .andExpect(content().string(not(containsString("docs/alpha.txt"))));
    }
}
