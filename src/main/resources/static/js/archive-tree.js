/**
 * Draws an archive upload's contents as an ASCII tree, on any page that provides a
 * `#archiveTree` container and a `#archiveManifestData` JSON block. Self-mounting, so a page
 * only has to include it -- which is why the file page and the share landing page can both
 * show the same tree without either one knowing how it is built.
 */

const t = (key, fallback) => window.i18n?.archiveTree?.[key] || fallback;

function createTreeNode(name) {
    return {name, children: [], files: []};
}

/**
 * Inserts a manifest path into the in-memory tree, creating intermediate directory nodes
 * as needed. Skips a leading segment that duplicates the root name.
 * @param {{name: string, children: object[], files: string[]}} root
 * @param {string} path      - slash-separated manifest path
 * @param {string} rootName  - root label, used to detect a duplicated prefix
 * @param {boolean} isDir    - the entry's own manifest type. Guessing from a dot in the
 *                             name instead would file Makefile under directories.
 */
function addPathToTree(root, path, rootName, isDir = false) {
    const parts = path.split(/[\\/]/).filter(Boolean);
    let idx = 0;
    if (parts[0] === rootName) {
        idx = 1; // skip duplicated root segment
    }

    const leafIsDir = isDir || Boolean(path && path.endsWith("/"));
    let node = root;
    for (; idx < parts.length; idx++) {
        const part = parts[idx];
        const isLeaf = idx === parts.length - 1;
        if (isLeaf && !leafIsDir) {
            node.files.push(part);
        } else {
            let child = node.children.find((c) => c.name === part);
            if (!child) {
                child = createTreeNode(part);
                node.children.push(child);
            }
            node = child;
        }
    }
}

/**
 * Recursively serializes a tree node into typed line-segment arrays. Each entry in `lines`
 * is an array of `{text, type}` objects where type is one of:
 * "connector" | "root" | "folder" | "file".
 */
function printTree(node, prefix, isLast, lines, isRoot = false) {
    const connector = prefix === "" ? "" : isLast ? "└─ " : "├─ ";
    lines.push([
        {text: `${prefix}${connector}`, type: "connector"},
        {text: node.name, type: isRoot ? "root" : "folder"},
    ]);

    const nextPrefix =
        prefix === "" ? "   " : isLast ? `${prefix}   ` : `${prefix}│  `;
    const children = [
        ...node.children.sort((a, b) => a.name.localeCompare(b.name)),
        ...node.files.sort(),
    ];

    children.forEach((child, index) => {
        const lastChild = index === children.length - 1;
        if (typeof child === "string") {
            lines.push([
                {text: `${nextPrefix}${lastChild ? "└─ " : "├─ "}`, type: "connector"},
                {text: child, type: "file"},
            ]);
        } else {
            printTree(child, nextPrefix, lastChild, lines, false);
        }
    });
}

/**
 * @param {{path: string, type?: string}[]} entries parsed manifest entries
 * @param {string} rootName label for the top of the tree
 * @returns {{text: string, type: string}[][]} one segment array per rendered line
 */
export function buildTreeLines(entries, rootName) {
    const root = createTreeNode(rootName);
    (entries || []).forEach((entry) => {
        if (!entry || !entry.path) return;
        addPathToTree(root, entry.path, rootName, entry.type === "dir");
    });

    const lines = [];
    printTree(root, "", true, lines, true);
    return lines;
}

const SEGMENT_CLASS = {
    root: "folder-tree-root",
    folder: "folder-tree-folder",
    file: "folder-tree-file",
    connector: "folder-tree-connector",
};

// One DOM node per line, rebuilt on every page view: a large archive would otherwise make
// the file page crawl before the reader has decided they want the whole listing.
export const INITIAL_TREE_LINES = 500;

function lineElement(segments) {
    const lineEl = document.createElement("div");
    lineEl.style.whiteSpace = "pre";
    segments.forEach((segment) => {
        const span = document.createElement("span");
        span.className = SEGMENT_CLASS[segment.type] || "";
        span.textContent = segment.text;
        lineEl.appendChild(span);
    });
    return lineEl;
}

function appendLines(treeEl, lines) {
    const fragment = document.createDocumentFragment();
    lines.forEach((segments) => fragment.appendChild(lineElement(segments)));
    treeEl.appendChild(fragment);
}

export function renderArchiveTree(treeEl, entries, rootName, limit = INITIAL_TREE_LINES) {
    treeEl.textContent = "";

    const lines = buildTreeLines(entries, rootName);
    if (lines.length <= limit) {
        appendLines(treeEl, lines);
        return;
    }

    appendLines(treeEl, lines.slice(0, limit));

    const remaining = lines.length - limit;
    const showAll = document.createElement("button");
    showAll.type = "button";
    showAll.className = "mt-2 text-left underline opacity-80 hover:opacity-100";
    showAll.textContent = t("showAll", "Show {0} more").replace("{0}", remaining);
    showAll.addEventListener("click", () => {
        showAll.remove();
        appendLines(treeEl, lines.slice(limit));
    });
    treeEl.appendChild(showAll);
}

function mount() {
    const treeEl = document.getElementById("archiveTree");
    if (!treeEl) return;

    const manifestScript = document.getElementById("archiveManifestData");
    const rootName = treeEl.dataset.archiveName || t("fallbackName", "folder");
    if (!manifestScript || !manifestScript.textContent) {
        treeEl.textContent = t("noManifest", "No manifest available.");
        return;
    }

    let entries;
    try {
        entries = JSON.parse(manifestScript.textContent);
    } catch (e) {
        console.warn("Archive manifest parse failed", e);
        treeEl.textContent = t("renderFailed", "Unable to render folder contents.");
        return;
    }

    renderArchiveTree(treeEl, entries, rootName);
}

// Guarded so the tree-building functions above can be imported and tested outside a browser.
if (typeof document !== "undefined") {
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", mount);
    } else {
        mount();
    }
}
