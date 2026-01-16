import { preprocessFileForMetadata } from "./metadata-pipeline.js";

export function parseSize(sizeLabel) {
  const units = { B: 1, KB: 1024, MB: 1024 * 1024, GB: 1024 * 1024 * 1024 };
  const unitMatch = sizeLabel.match(/[a-zA-Z]+/);
  const valueMatch = sizeLabel.match(/[0-9.]+/);

  if (!unitMatch || !valueMatch) {
    throw new Error("Invalid maxFileSize format");
  }
  const unit = unitMatch[0];
  const value = parseFloat(valueMatch[0]);
  return value * (units[unit] || 1);
}

export function buildFolderManifest(fileList) {
  const manifestSet = new Set();
  let totalOriginalSize = 0;
  const firstPath = fileList[0].webkitRelativePath || fileList[0].name;
  const rootFolder = firstPath.split(/[/\\]/)[0];

  for (const file of fileList) {
    totalOriginalSize += file.size;
    const rel = file.webkitRelativePath || file.name;
    manifestSet.add(
      JSON.stringify({ path: rel, size: file.size, type: "file" })
    );
    const parts = rel.split(/[/\\]/);
    let prefix = "";
    for (let i = 0; i < parts.length - 1; i++) {
      prefix = prefix ? `${prefix}/${parts[i]}` : parts[i];
      manifestSet.add(JSON.stringify({ path: prefix, type: "dir" }));
    }
  }

  return {
    manifestArray: Array.from(manifestSet).map((s) => JSON.parse(s)),
    rootFolder,
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
  return zip.generateAsync({ type: "blob" });
}

async function zipRawFiles(fileList, manifestArray) {
  if (!window.JSZip) throw new Error("JSZip is unavailable");
  const rawZip = new JSZip();
  manifestArray.forEach((entry) => {
    if (entry.type === "dir") {
      rawZip.folder(entry.path);
    }
  });
  for (const file of fileList) {
    const rel = file.webkitRelativePath || file.name;
    rawZip.file(rel, file);
  }
  return rawZip.generateAsync({ type: "blob" });
}

export async function buildFolderCandidates(fileList, { metadataEnabled }) {
  const { manifestArray, rootFolder, totalOriginalSize } =
    buildFolderManifest(fileList);
  const processedEntries = [];
  const failures = [];
  const warnings = [];
  const results = [];

  for (const file of fileList) {
    const rel = file.webkitRelativePath || file.name;
    const result = await preprocessFileForMetadata(file, rel, metadataEnabled);
    processedEntries.push({ path: rel, file: result.processedFile });

    if (result.failureReason) {
      failures.push({ name: rel, reason: result.failureReason });
      results.push({
        name: rel,
        status: "failed",
        reason: result.failureReason,
      });
    }
    if (result.warnings && result.warnings.length) {
      result.warnings.forEach((w) => warnings.push({ name: rel, reason: w }));
      results.push({
        name: rel,
        status: "warning",
        warnings: [...result.warnings],
      });
    }
    if (
      !result.failureReason &&
      (!result.warnings || result.warnings.length === 0)
    ) {
      results.push({ name: rel, status: "ok" });
    }
  }

  const zipBlob = await zipFromEntries(processedEntries, manifestArray);
  let fallbackZipBlob = zipBlob;
  if (failures.length > 0) {
    fallbackZipBlob = await zipRawFiles(fileList, manifestArray);
  }

  const zipName = `${rootFolder}.zip`;
  const cleanCandidate = {
    file: zipBlob,
    name: zipName,
    size: zipBlob.size,
    folderUpload: true,
    folderName: rootFolder,
    folderManifest: JSON.stringify(manifestArray),
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
    rootFolder,
    totalOriginalSize,
    fileCount: fileList.length,
    zipSize: zipBlob.size,
  };
}
