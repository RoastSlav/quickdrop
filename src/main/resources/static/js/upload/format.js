const UNITS = ["B", "KB", "MB", "GB", "TB"];

/**
 * Mirrors FileUtils.formatFileSize so a selection reads the same on the upload page as it
 * does on the file page after it lands. Hard-coding MB there made every small selection
 * show as "0.00 MB".
 */
export function formatBytes(bytes) {
    let size = Number(bytes);
    if (!Number.isFinite(size) || size < 0) size = 0;

    let unitIndex = 0;
    while (size >= 1024 && unitIndex < UNITS.length - 1) {
        size /= 1024;
        unitIndex++;
    }
    return `${size.toFixed(2)} ${UNITS[unitIndex]}`;
}
