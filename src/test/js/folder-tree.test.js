import test from "node:test";
import assert from "node:assert/strict";

import {buildTreeLines} from "../../main/resources/static/js/folder-tree.js";

/** Flattens the typed segments back to the text a reader sees, one string per line. */
const render = (entries, rootName) =>
    buildTreeLines(entries, rootName).map((segments) =>
        segments.map((segment) => segment.text).join("")
    );

const file = (path, size = 1) => ({path, size, type: "file"});
const dir = (path) => ({path, type: "dir"});

test("buildTreeLines: a flat bundle lists its files under the generated root", () => {
    assert.deepEqual(render([file("report.txt"), file("notes.md")], "files"), [
        "files",
        "   ├─ notes.md",
        "   └─ report.txt",
    ]);
});

test("buildTreeLines: a folder's own name is not repeated inside the tree", () => {
    assert.deepEqual(render([dir("docs"), file("docs/a.txt"), file("docs/b.txt")], "docs"), [
        "docs",
        "   ├─ a.txt",
        "   └─ b.txt",
    ]);
});

test("buildTreeLines: nested directories keep their connectors", () => {
    const entries = [
        dir("docs"),
        dir("docs/sub"),
        file("docs/sub/deep.txt"),
        file("docs/top.txt"),
    ];
    assert.deepEqual(render(entries, "docs"), [
        "docs",
        "   ├─ sub",
        "   │  └─ deep.txt",
        "   └─ top.txt",
    ]);
});

test("buildTreeLines: an extensionless file is a file, not a directory", () => {
    const lines = buildTreeLines([file("Makefile"), file("README")], "files");
    const leaves = lines.slice(1).map((segments) => segments[1]);
    assert.deepEqual(leaves, [
        {text: "Makefile", type: "file"},
        {text: "README", type: "file"},
    ]);
});

test("buildTreeLines: a directory containing a dot is a directory, not a file", () => {
    const lines = buildTreeLines([dir("my.assets"), file("my.assets/logo.svg")], "files");
    assert.deepEqual(lines[1][1], {text: "my.assets", type: "folder"});
    assert.deepEqual(lines[2][1], {text: "logo.svg", type: "file"});
});

test("buildTreeLines: several top-level directories sit side by side", () => {
    const entries = [dir("a"), file("a/x.txt"), dir("b"), file("b/y.txt")];
    assert.deepEqual(render(entries, "files"), [
        "files",
        "   ├─ a",
        "   │  └─ x.txt",
        "   └─ b",
        "      └─ y.txt",
    ]);
});

test("buildTreeLines: backslash paths render the same as forward slashes", () => {
    assert.deepEqual(
        render([file("docs\\sub\\a.txt")], "docs"),
        render([file("docs/sub/a.txt")], "docs")
    );
});

test("buildTreeLines: entries without a path are skipped rather than throwing", () => {
    assert.deepEqual(render([null, {}, file("ok.txt")], "files"), ["files", "   └─ ok.txt"]);
});

test("buildTreeLines: an empty manifest still renders the root", () => {
    assert.deepEqual(render([], "files"), ["files"]);
});
