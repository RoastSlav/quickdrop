import {preprocessFileForMetadata} from "./metadata-pipeline.js";

const LARGE_FOLDER_STREAM_THRESHOLD = 1024 * 1024 * 1024;
const DEFAULT_BUNDLE_NAME = "files";
const ZIP64_EXTRA_ID = 0x0001;
const UTF8_FLAG = 0x0800;
const DATA_DESCRIPTOR_FLAG = 0x0008;
const STORE_METHOD = 0;
const DOS_TIME = 0;
const DOS_DATE = 0x0021;

const getRelativePath = (file) =>
    file?.relativePath || file?.webkitRelativePath || file?.path || file?.name;

const textEncoder = new TextEncoder();
let crcTable = null;

/** @throws {Error} if sizeLabel doesn't match a "<number><unit>" pattern */
export function parseSize(sizeLabel) {
    const units = {B: 1, KB: 1024, MB: 1024 * 1024, GB: 1024 * 1024 * 1024};
    const unitMatch = sizeLabel.match(/[a-zA-Z]+/);
    const valueMatch = sizeLabel.match(/[0-9.]+/);

    if (!unitMatch || !valueMatch) {
        throw new Error("Invalid maxFileSize format");
    }
    const unit = unitMatch[0];
    const value = parseFloat(valueMatch[0]);
    return value * (units[unit] || 1);
}

/**
 * Date part of a loose bundle's name: two-digit year, then month and day without leading
 * zeros, so 2 September 2026 reads 2692. The distinguishing code that follows it is added
 * server-side, which is the only place that can see whether a name is already taken.
 */
export function bundleDateStamp(date = new Date()) {
    const year = String(date.getFullYear() % 100).padStart(2, "0");
    return `${year}${date.getMonth() + 1}${date.getDate()}`;
}

const normalizePath = (file) => getRelativePath(file).replace(/\\/g, "/");

/** Preparation runs per file and can take a while, so it has to be interruptible. */
function throwIfAborted(signal) {
    if (!signal?.aborted) return;
    const error = new Error("Preparation was cancelled.");
    error.name = "AbortError";
    throw error;
}

/**
 * The single top-level directory shared by every entry, or null when there isn't one --
 * loose files, several picked folders, or a folder dropped alongside a file. Three things
 * depend on the answer: the archive's name, the tree's root label, and which strings the
 * upload page shows, so it is asked once here rather than guessed at each call site.
 * @param {string[]} paths normalized, slash-separated relative paths
 */
function deriveRootFolder(paths) {
    let root = null;
    for (const path of paths) {
        const parts = path.split("/").filter(Boolean);
        if (parts.length < 2) return null;
        if (root === null) root = parts[0];
        else if (root !== parts[0]) return null;
    }
    return root;
}

// A zip entry written twice keeps only one of the files, so same-named files from different
// source folders would vanish without a word. Suffix the later ones instead.
function uniquePath(path, used) {
    if (!used.has(path)) {
        used.add(path);
        return path;
    }
    const slash = path.lastIndexOf("/");
    const dir = slash === -1 ? "" : path.slice(0, slash + 1);
    const base = path.slice(slash + 1);
    const dot = base.lastIndexOf(".");
    const stem = dot > 0 ? base.slice(0, dot) : base;
    const extension = dot > 0 ? base.slice(dot) : "";

    let candidate;
    let counter = 2;
    do {
        candidate = `${dir}${stem} (${counter++})${extension}`;
    } while (used.has(candidate));
    used.add(candidate);
    return candidate;
}

/**
 * Pairs every selected file with the path it will occupy in the archive, guaranteeing those
 * paths are unique. Everything downstream -- manifest, zip entries, metadata warnings --
 * reads the path from here so they cannot disagree about what the archive contains.
 * @returns {{file: File, path: string}[]} in selection order
 */
export function collectSelectionEntries(fileList) {
    const used = new Set();
    return Array.from(fileList, (file) => ({
        file,
        path: uniquePath(normalizePath(file), used),
    }));
}

/**
 * Whether a selection archives as one named folder or as a generated bundle. Callers need
 * this before the (potentially slow) zip build, to phrase what they show while it runs.
 */
export function describeSelection(fileList) {
    const archiveName = deriveRootFolder(Array.from(fileList, normalizePath));
    return {
        archiveName: archiveName || DEFAULT_BUNDLE_NAME,
        isBundle: archiveName === null,
    };
}

/**
 * @param {{file: File, path: string}[]} entries from {@link collectSelectionEntries}
 */
export function buildArchiveManifest(entries) {
    const manifestSet = new Set();
    let totalOriginalSize = 0;
    const derivedRoot = deriveRootFolder(entries.map((entry) => entry.path));

    for (const {file, path} of entries) {
        totalOriginalSize += file.size;
        manifestSet.add(
            JSON.stringify({path, size: file.size, type: "file"})
        );
        const parts = path.split("/");
        let prefix = "";
        for (let i = 0; i < parts.length - 1; i++) {
            prefix = prefix ? `${prefix}/${parts[i]}` : parts[i];
            manifestSet.add(JSON.stringify({path: prefix, type: "dir"}));
        }
    }

    return {
        manifestArray: Array.from(manifestSet).map((s) => JSON.parse(s)),
        archiveName: derivedRoot || DEFAULT_BUNDLE_NAME,
        isBundle: derivedRoot === null,
        totalOriginalSize,
    };
}

async function zipFromEntries(entries, manifestArray) {
    if (!window.JSZip) throw new Error("JSZip is unavailable");
    const zip = new JSZip();
    manifestArray.forEach((entry) => {
        if (entry.type === "dir") {
            zip.folder(entry.path);
        }
    });
    entries.forEach((entry) => {
        zip.file(entry.path, entry.file);
    });
    return zip.generateAsync({type: "blob"});
}

async function zipRawEntries(entries, manifestArray) {
    if (!window.JSZip) throw new Error("JSZip is unavailable");
    const rawZip = new JSZip();
    manifestArray.forEach((entry) => {
        if (entry.type === "dir") {
            rawZip.folder(entry.path);
        }
    });
    for (const {file, path} of entries) {
        rawZip.file(path, file);
    }
    return rawZip.generateAsync({type: "blob"});
}

function getCrcTable() {
    if (crcTable) return crcTable;
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
        }
        crcTable[n] = c >>> 0;
    }
    return crcTable;
}

function updateCrc(crc, bytes) {
    const table = getCrcTable();
    let c = crc >>> 0;
    for (let i = 0; i < bytes.length; i++) {
        c = table[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    }
    return c >>> 0;
}

function setUint64(view, offset, value) {
    let n = BigInt(value);
    for (let i = 0; i < 8; i++) {
        view.setUint8(offset + i, Number(n & 0xffn));
        n >>= 8n;
    }
}

function makeZip64Extra(values) {
    const bytes = new Uint8Array(4 + values.length * 8);
    const view = new DataView(bytes.buffer);
    view.setUint16(0, ZIP64_EXTRA_ID, true);
    view.setUint16(2, values.length * 8, true);
    values.forEach((value, index) => setUint64(view, 4 + index * 8, value));
    return bytes;
}

function makeLocalHeader(pathBytes) {
    const bytes = new Uint8Array(30 + pathBytes.length);
    const view = new DataView(bytes.buffer);

    view.setUint32(0, 0x04034b50, true);
    view.setUint16(4, 45, true);
    view.setUint16(6, UTF8_FLAG | DATA_DESCRIPTOR_FLAG, true);
    view.setUint16(8, STORE_METHOD, true);
    view.setUint16(10, DOS_TIME, true);
    view.setUint16(12, DOS_DATE, true);
    view.setUint32(14, 0, true);
    view.setUint32(18, 0, true);
    view.setUint32(22, 0, true);
    view.setUint16(26, pathBytes.length, true);
    view.setUint16(28, 0, true);
    bytes.set(pathBytes, 30);

    return bytes;
}

function makeDataDescriptor(crc, size) {
    const bytes = new Uint8Array(24);
    const view = new DataView(bytes.buffer);
    view.setUint32(0, 0x08074b50, true);
    view.setUint32(4, crc >>> 0, true);
    setUint64(view, 8, size);
    setUint64(view, 16, size);
    return bytes;
}

function makeCentralHeader(entry) {
    const extra = makeZip64Extra([entry.size, entry.size, entry.offset]);
    const bytes = new Uint8Array(46 + entry.pathBytes.length + extra.length);
    const view = new DataView(bytes.buffer);

    view.setUint32(0, 0x02014b50, true);
    view.setUint16(4, 45, true);
    view.setUint16(6, 45, true);
    view.setUint16(8, UTF8_FLAG | DATA_DESCRIPTOR_FLAG, true);
    view.setUint16(10, STORE_METHOD, true);
    view.setUint16(12, DOS_TIME, true);
    view.setUint16(14, DOS_DATE, true);
    view.setUint32(16, entry.crc >>> 0, true);
    view.setUint32(20, 0xffffffff, true);
    view.setUint32(24, 0xffffffff, true);
    view.setUint16(28, entry.pathBytes.length, true);
    view.setUint16(30, extra.length, true);
    view.setUint16(32, 0, true);
    view.setUint16(34, 0, true);
    view.setUint16(36, 0, true);
    view.setUint32(38, 0, true);
    view.setUint32(42, 0xffffffff, true);
    bytes.set(entry.pathBytes, 46);
    bytes.set(extra, 46 + entry.pathBytes.length);

    return bytes;
}

function makeZip64EndRecord(entryCount, centralSize, centralOffset) {
    const bytes = new Uint8Array(56);
    const view = new DataView(bytes.buffer);
    view.setUint32(0, 0x06064b50, true);
    setUint64(view, 4, 44n);
    view.setUint16(12, 45, true);
    view.setUint16(14, 45, true);
    view.setUint32(16, 0, true);
    view.setUint32(20, 0, true);
    setUint64(view, 24, BigInt(entryCount));
    setUint64(view, 32, BigInt(entryCount));
    setUint64(view, 40, centralSize);
    setUint64(view, 48, centralOffset);
    return bytes;
}

function makeZip64Locator(zip64EndOffset) {
    const bytes = new Uint8Array(20);
    const view = new DataView(bytes.buffer);
    view.setUint32(0, 0x07064b50, true);
    view.setUint32(4, 0, true);
    setUint64(view, 8, zip64EndOffset);
    view.setUint32(16, 1, true);
    return bytes;
}

function makeEndRecord() {
    const bytes = new Uint8Array(22);
    const view = new DataView(bytes.buffer);
    view.setUint32(0, 0x06054b50, true);
    view.setUint16(4, 0, true);
    view.setUint16(6, 0, true);
    view.setUint16(8, 0xffff, true);
    view.setUint16(10, 0xffff, true);
    view.setUint32(12, 0xffffffff, true);
    view.setUint32(16, 0xffffffff, true);
    view.setUint16(20, 0, true);
    return bytes;
}

function makeStreamEntry(path, file) {
    const normalizedPath = path.replace(/\\/g, "/");
    return {
        path: normalizedPath,
        pathBytes: textEncoder.encode(normalizedPath),
        file,
        size: BigInt(file.size),
    };
}

function estimateZip64Size(entries) {
    let total = 56n + 20n + 22n;
    for (const entry of entries) {
        total += BigInt(30 + entry.pathBytes.length);
        total += entry.size;
        total += 24n;
        total += BigInt(46 + entry.pathBytes.length + 28);
    }
    return Number(total);
}

async function* readBlobBytes(blob) {
    if (blob.stream) {
        const reader = blob.stream().getReader();
        try {
            while (true) {
                const {done, value} = await reader.read();
                if (done) break;
                if (value && value.length) yield value;
            }
        } finally {
            reader.releaseLock();
        }
        return;
    }

    const readSize = 1024 * 1024;
    for (let offset = 0; offset < blob.size; offset += readSize) {
        const chunk = blob.slice(offset, Math.min(offset + readSize, blob.size));
        yield new Uint8Array(await chunk.arrayBuffer());
    }
}

async function* streamZip64(entries) {
    const centralEntries = [];
    let offset = 0n;

    for (const entry of entries) {
        entry.offset = offset;
        const localHeader = makeLocalHeader(entry.pathBytes);
        yield localHeader;
        offset += BigInt(localHeader.length);

        let crc = 0xffffffff;
        for await (const bytes of readBlobBytes(entry.file)) {
            crc = updateCrc(crc, bytes);
            yield bytes;
            offset += BigInt(bytes.length);
        }

        entry.crc = (crc ^ 0xffffffff) >>> 0;
        const descriptor = makeDataDescriptor(entry.crc, entry.size);
        yield descriptor;
        offset += BigInt(descriptor.length);
        centralEntries.push(entry);
    }

    const centralOffset = offset;
    let centralSize = 0n;
    for (const entry of centralEntries) {
        const centralHeader = makeCentralHeader(entry);
        yield centralHeader;
        offset += BigInt(centralHeader.length);
        centralSize += BigInt(centralHeader.length);
    }

    const zip64EndOffset = offset;
    const zip64End = makeZip64EndRecord(centralEntries.length, centralSize, centralOffset);
    yield zip64End;
    offset += BigInt(zip64End.length);

    const locator = makeZip64Locator(zip64EndOffset);
    yield locator;
    yield makeEndRecord();
}

function makeStreamingCandidate(entries, zipName, archiveName, manifestArray) {
    return {
        name: zipName,
        size: estimateZip64Size(entries),
        archiveUpload: true,
        archiveName: archiveName,
        archiveManifest: JSON.stringify(manifestArray),
        streamUpload: true,
        createStream: () => streamZip64(entries.map((entry) => ({...entry}))),
    };
}

/**
 * Zips a folder or multi-file selection into a single upload candidate, stripping metadata
 * per-file when enabled. Falls back to a streamed zip64 build (createStream instead of a
 * materialized blob) once the selection exceeds LARGE_FOLDER_STREAM_THRESHOLD, or if
 * in-memory zipping throws a blob-related error.
 * @returns {{cleanCandidate: object, fallbackCandidate: object, failures: object[], warnings: object[]}}
 */
export async function buildArchiveCandidates(fileList, {metadataEnabled, onProgress, signal}) {
    const selectionEntries = collectSelectionEntries(fileList);
    const {manifestArray, archiveName, isBundle, totalOriginalSize} =
        buildArchiveManifest(selectionEntries);
    const processedEntries = [];
    const failures = [];
    const warnings = [];
    const results = [];
    let prepared = 0;

    for (const {file, path: rel} of selectionEntries) {
        throwIfAborted(signal);
        const result = await preprocessFileForMetadata(file, rel, metadataEnabled);
        processedEntries.push({path: rel, file: result.processedFile});

        if (result.failureReason) {
            failures.push({name: rel, reason: result.failureReason});
        }
        if (result.warnings && result.warnings.length) {
            result.warnings.forEach((w) => warnings.push({name: rel, reason: w}));
        }
        // Exactly one row per file, so a review panel can count "N of M" honestly.
        results.push({
            name: rel,
            size: file.size,
            status: result.failureReason
                ? "failed"
                : (result.warnings && result.warnings.length ? "warning" : "ok"),
            reason: result.failureReason || null,
            warnings: result.warnings ? [...result.warnings] : [],
        });

        onProgress?.(++prepared, selectionEntries.length);
    }

    throwIfAborted(signal);
    const zipName = isBundle ? `${archiveName}-${bundleDateStamp()}.zip` : `${archiveName}.zip`;
    const cleanStreamEntries = processedEntries.map((entry) =>
        makeStreamEntry(entry.path, entry.file)
    );
    const fallbackStreamEntries = selectionEntries.map((entry) =>
        makeStreamEntry(entry.path, entry.file)
    );

    if (totalOriginalSize >= LARGE_FOLDER_STREAM_THRESHOLD) {
        const cleanCandidate = makeStreamingCandidate(
            cleanStreamEntries,
            zipName,
            archiveName,
            manifestArray
        );
        const fallbackCandidate = makeStreamingCandidate(
            failures.length > 0 ? fallbackStreamEntries : cleanStreamEntries,
            zipName,
            archiveName,
            manifestArray
        );

        return {
            cleanCandidate,
            fallbackCandidate,
            failures,
            warnings,
            results,
            manifestArray,
            archiveName,
            totalOriginalSize,
            isBundle,
            fileCount: selectionEntries.length,
            zipSize: cleanCandidate.size,
        };
    }

    let zipBlob;
    let fallbackZipBlob;
    try {
        zipBlob = await zipFromEntries(processedEntries, manifestArray);
        fallbackZipBlob = zipBlob;
        if (failures.length > 0) {
            fallbackZipBlob = await zipRawEntries(selectionEntries, manifestArray);
        }
    } catch (error) {
        if (!String(error?.message || error).toLowerCase().includes("blob")) {
            throw error;
        }
        const cleanCandidate = makeStreamingCandidate(
            cleanStreamEntries,
            zipName,
            archiveName,
            manifestArray
        );
        const fallbackCandidate = makeStreamingCandidate(
            failures.length > 0 ? fallbackStreamEntries : cleanStreamEntries,
            zipName,
            archiveName,
            manifestArray
        );

        return {
            cleanCandidate,
            fallbackCandidate,
            failures,
            warnings,
            results,
            manifestArray,
            archiveName,
            totalOriginalSize,
            isBundle,
            fileCount: selectionEntries.length,
            zipSize: cleanCandidate.size,
        };
    }

    const cleanCandidate = {
        file: zipBlob,
        name: zipName,
        size: zipBlob.size,
        archiveUpload: true,
        archiveName: archiveName,
        archiveManifest: JSON.stringify(manifestArray),
    };
    const fallbackCandidate = {
        ...cleanCandidate,
        file: fallbackZipBlob,
        size: fallbackZipBlob.size,
    };

    return {
        cleanCandidate,
        fallbackCandidate,
        failures,
        warnings,
        results,
        manifestArray,
        archiveName,
        totalOriginalSize,
        isBundle,
        fileCount: selectionEntries.length,
        zipSize: zipBlob.size,
    };
}
