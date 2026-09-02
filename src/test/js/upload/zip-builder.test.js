import test from "node:test";
import assert from "node:assert/strict";

import {
    buildArchiveCandidates,
    buildArchiveManifest,
    bundleDateStamp,
    collectSelectionEntries,
    describeSelection,
    MAX_ARCHIVE_FILES,
    parseSize,
} from "../../../main/resources/static/js/upload/zip-builder.js";

// The functions under test only read `size` and a relative path off each entry, so a plain
// object stands in for a File. Anything needing JSZip or a Blob is deliberately not covered
// here -- that belongs to the browser pass.
const f = (relativePath, size = 10) => ({
    name: relativePath.split(/[\\/]/).pop(),
    size,
    relativePath,
});

const paths = (entries) => entries.map((entry) => entry.path);

test("describeSelection: one shared top-level directory is a folder", () => {
    const result = describeSelection([f("docs/a.txt"), f("docs/sub/b.txt")]);
    assert.deepEqual(result, {archiveName: "docs", isBundle: false});
});

test("describeSelection: a folder holding a single file is still a folder", () => {
    assert.deepEqual(describeSelection([f("docs/only.txt")]), {
        archiveName: "docs",
        isBundle: false,
    });
});

test("describeSelection: backslash paths resolve the same as forward slashes", () => {
    assert.deepEqual(describeSelection([f("docs\\a.txt"), f("docs\\b.txt")]), {
        archiveName: "docs",
        isBundle: false,
    });
});

test("describeSelection: two top-level directories is a bundle", () => {
    assert.deepEqual(describeSelection([f("a/x.txt"), f("b/y.txt")]), {
        archiveName: "files",
        isBundle: true,
    });
});

test("describeSelection: a directory next to a loose file is a bundle", () => {
    assert.deepEqual(describeSelection([f("a/x.txt"), f("loose.txt")]), {
        archiveName: "files",
        isBundle: true,
    });
});

test("describeSelection: loose files only is a bundle", () => {
    assert.deepEqual(describeSelection([f("one.txt"), f("two.txt")]), {
        archiveName: "files",
        isBundle: true,
    });
});

test("collectSelectionEntries: pairs every file with its path, in selection order", () => {
    const files = [f("b.txt"), f("a.txt"), f("c.txt")];
    const entries = collectSelectionEntries(files);

    assert.deepEqual(paths(entries), ["b.txt", "a.txt", "c.txt"]);
    assert.equal(entries[0].file, files[0]);
    assert.equal(entries[2].file, files[2]);
});

test("collectSelectionEntries: normalizes backslashes to archive separators", () => {
    const entries = collectSelectionEntries([f("docs\\sub\\a.txt")]);
    assert.deepEqual(paths(entries), ["docs/sub/a.txt"]);
});

test("collectSelectionEntries: colliding names are suffixed before the extension", () => {
    const entries = collectSelectionEntries([f("dup.txt"), f("dup.txt"), f("dup.txt")]);
    assert.deepEqual(paths(entries), ["dup.txt", "dup (2).txt", "dup (3).txt"]);
});

test("collectSelectionEntries: extensionless names are suffixed at the end", () => {
    const entries = collectSelectionEntries([f("Makefile"), f("Makefile")]);
    assert.deepEqual(paths(entries), ["Makefile", "Makefile (2)"]);
});

test("collectSelectionEntries: a leading dot is not treated as an extension", () => {
    const entries = collectSelectionEntries([f(".gitignore"), f(".gitignore")]);
    assert.deepEqual(paths(entries), [".gitignore", ".gitignore (2)"]);
});

test("collectSelectionEntries: same name in different directories does not collide", () => {
    const entries = collectSelectionEntries([f("a/x.txt"), f("b/x.txt")]);
    assert.deepEqual(paths(entries), ["a/x.txt", "b/x.txt"]);
});

test("collectSelectionEntries: a suffix never lands on an already-taken name", () => {
    const entries = collectSelectionEntries([
        f("dup.txt"),
        f("dup (2).txt"),
        f("dup.txt"),
    ]);
    assert.deepEqual(paths(entries), ["dup.txt", "dup (2).txt", "dup (3).txt"]);
    assert.equal(new Set(paths(entries)).size, 3);
});

test("buildArchiveManifest: flat bundle has file entries and no directories", () => {
    const entries = collectSelectionEntries([f("a.txt", 100), f("b.txt", 200)]);
    const manifest = buildArchiveManifest(entries);

    assert.equal(manifest.archiveName, "files");
    assert.equal(manifest.isBundle, true);
    assert.equal(manifest.totalOriginalSize, 300);
    assert.deepEqual(manifest.manifestArray, [
        {path: "a.txt", size: 100, type: "file"},
        {path: "b.txt", size: 200, type: "file"},
    ]);
});

test("buildArchiveManifest: every intermediate directory is emitted exactly once", () => {
    const entries = collectSelectionEntries([
        f("docs/a.txt", 1),
        f("docs/sub/b.txt", 2),
        f("docs/sub/c.txt", 3),
    ]);
    const manifest = buildArchiveManifest(entries);
    const dirs = manifest.manifestArray.filter((e) => e.type === "dir").map((e) => e.path);

    assert.equal(manifest.archiveName, "docs");
    assert.equal(manifest.isBundle, false);
    assert.equal(manifest.totalOriginalSize, 6);
    assert.deepEqual(dirs.sort(), ["docs", "docs/sub"]);
});

test("buildArchiveManifest: a multi-root bundle keeps each directory in the manifest", () => {
    const entries = collectSelectionEntries([f("a/x.txt"), f("b/y.txt")]);
    const manifest = buildArchiveManifest(entries);
    const dirs = manifest.manifestArray.filter((e) => e.type === "dir").map((e) => e.path);

    assert.equal(manifest.isBundle, true);
    assert.deepEqual(dirs.sort(), ["a", "b"]);
});

test("parseSize: reads the size limit the upload page renders", () => {
    assert.equal(parseSize("1 GB"), 1024 * 1024 * 1024);
    assert.equal(parseSize("500 MB"), 500 * 1024 * 1024);
    assert.equal(parseSize("1024 B"), 1024);
    assert.equal(parseSize("1.5 GB"), 1.5 * 1024 * 1024 * 1024);
});

test("parseSize: rejects a label it cannot read", () => {
    assert.throws(() => parseSize("unlimited"), /Invalid maxFileSize format/);
});

test("bundleDateStamp: two-digit year, then month and day without leading zeros", () => {
    assert.equal(bundleDateStamp(new Date(2026, 8, 2)), "2692");
    assert.equal(bundleDateStamp(new Date(2026, 11, 25)), "261225");
    assert.equal(bundleDateStamp(new Date(2026, 0, 1)), "2611");
    assert.equal(bundleDateStamp(new Date(2007, 4, 9)), "0759");
});

test("buildArchiveCandidates: refuses a selection past the file cap", async () => {
    const tooMany = Array.from({length: MAX_ARCHIVE_FILES + 1}, (_, i) => f(`f${i}.txt`));

    // Rejects before any zipping, so this needs neither JSZip nor a Blob.
    await assert.rejects(
        () => buildArchiveCandidates(tooMany, {metadataEnabled: false}),
        (err) => err.name === "TooManyFilesError" && err.limit === MAX_ARCHIVE_FILES
    );
});
