export const METADATA_STRIP_MAX_BYTES = 25 * 1024 * 1024;

export async function preprocessFileForMetadata(
  file,
  displayName = "",
  metadataEnabled = false
) {
  if (!metadataEnabled || !window.MetadataRegistry) {
    return {
      processedFile: file,
      stripped: false,
      warnings: [],
      supported: false,
      failureReason: null,
    };
  }

  const result = await window.MetadataRegistry.stripIfPossible(
    file,
    displayName
  );

  return {
    processedFile: result.processedFile,
    stripped: Boolean(result.wasModified || result.stripped),
    supported: Boolean(result.supported),
    failureReason: result.supported
      ? result.failureReason
      : result.failureReason || "Unsupported type",
    warnings: result.warnings || [],
  };
}

export async function buildSingleCandidates(file, metadataEnabled) {
  const base = {
    file,
    name: file.name,
    size: file.size,
    folderUpload: false,
    folderName: null,
    folderManifest: null,
  };

  if (!metadataEnabled) {
    return {
      cleanCandidate: base,
      fallbackCandidate: base,
      failures: [],
      warnings: [],
    };
  }

  const result = await preprocessFileForMetadata(
    file,
    file.name,
    metadataEnabled
  );
  const cleanCandidate = {
    ...base,
    file: result.processedFile,
    size: result.processedFile.size,
  };

  const failures = result.failureReason
    ? [{ name: file.name, reason: result.failureReason }]
    : [];
  const warnings = (result.warnings || []).map((w) => ({
    name: file.name,
    reason: w,
  }));
  const fallbackCandidate = { ...base, file, size: file.size };
  return { cleanCandidate, fallbackCandidate, failures, warnings };
}
