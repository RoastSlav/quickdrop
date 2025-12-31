// UI state machine and warning helpers for the upload page
export const UploadState = Object.freeze({
  IDLE: "IDLE",
  PROCESSING: "PROCESSING",
  READY: "READY",
  NEEDS_CONFIRMATION: "NEEDS_CONFIRMATION",
  UPLOADING: "UPLOADING",
});

export function getUIRefs() {
  return {
    uploadPrimary: document.getElementById("uploadPrimary"),
    uploadCancel: document.getElementById("uploadCancel"),
    uploadWarning: document.getElementById("uploadWarning"),
    uploadWarningDetails: document.getElementById("uploadWarningDetails"),
    uploadWarningList: document.getElementById("uploadWarningList"),
    fileButton: document.getElementById("fileSelectButton"),
    folderButton: document.getElementById("folderSelectButton"),
    dropZone: document.getElementById("dropZone"),
  };
}

export function setUploadState(state, ui = getUIRefs()) {
  const isBusy =
    state === UploadState.PROCESSING || state === UploadState.UPLOADING;

  if (ui.uploadPrimary) {
    ui.uploadPrimary.disabled = isBusy || state === UploadState.IDLE;
    ui.uploadPrimary.textContent =
      state === UploadState.NEEDS_CONFIRMATION
        ? "Confirm Upload"
        : state === UploadState.UPLOADING
        ? "Uploading..."
        : "Upload";
    ui.uploadPrimary.classList.toggle(
      "opacity-60",
      isBusy || state === UploadState.IDLE
    );
    ui.uploadPrimary.classList.toggle(
      "cursor-not-allowed",
      isBusy || state === UploadState.IDLE
    );
  }

  if (ui.uploadCancel) {
    ui.uploadCancel.classList.toggle(
      "hidden",
      state !== UploadState.NEEDS_CONFIRMATION
    );
    ui.uploadCancel.disabled = isBusy;
  }

  if (ui.fileButton) ui.fileButton.disabled = isBusy;
  if (ui.folderButton) ui.folderButton.disabled = isBusy;
  if (ui.dropZone) ui.dropZone.classList.toggle("pointer-events-none", isBusy);

  if (state !== UploadState.NEEDS_CONFIRMATION) {
    clearStripWarning(ui);
  }
}

export function renderStripWarning(
  entries = [],
  source = "single",
  ui = getUIRefs(),
  { type = "failure" } = {}
) {
  if (!ui.uploadWarning) return;
  if (!entries || entries.length === 0) {
    clearStripWarning(ui);
    return;
  }

  const count = entries.length;
  const firstReason = entries[0]?.reason;
  ui.uploadWarning.classList.remove("hidden");
  if (type === "warning") {
    const baseText =
      source === "folder"
        ? `Metadata stripping completed with warnings for ${count} item${
            count > 1 ? "s" : ""
          }. Upload will continue.`
        : "Metadata stripping completed with warnings. Upload will continue.";
    ui.uploadWarning.textContent = firstReason
      ? `${baseText} Reason: ${firstReason}`
      : baseText;
  } else {
    const baseText =
      source === "folder"
        ? `Metadata stripping failed for ${count} file${
            count > 1 ? "s" : ""
          }. Uploading may include identifying metadata.`
        : "Metadata stripping failed for this file. Uploading may include identifying metadata.";
    ui.uploadWarning.textContent = firstReason
      ? `${baseText} Reason: ${firstReason}`
      : baseText;
  }

  if (ui.uploadWarningDetails && ui.uploadWarningList) {
    // We now show reasons inline; keep the details toggle hidden to avoid duplicate panels.
    ui.uploadWarningDetails.classList.add("hidden");
    ui.uploadWarningList.classList.add("hidden");
    ui.uploadWarningList.innerHTML = "";
  }
}

export function clearStripWarning(ui = getUIRefs()) {
  if (ui.uploadWarning) {
    ui.uploadWarning.classList.add("hidden");
    ui.uploadWarning.textContent = "";
  }
  if (ui.uploadWarningDetails) {
    ui.uploadWarningDetails.classList.add("hidden");
  }
  if (ui.uploadWarningList) {
    ui.uploadWarningList.classList.add("hidden");
    ui.uploadWarningList.innerHTML = "";
  }
}

export function showMessage(type, text) {
  const styles = {
    success: "bg-sky-100 text-sky-700 dark:bg-sky-900 dark:text-sky-100",
    info: "bg-sky-100 text-sky-700 dark:bg-sky-900 dark:text-sky-100",
    danger: "bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-100",
    warning:
      "bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-100",
  };
  const container = document.getElementById("messageContainer");
  if (!container) return;
  container.innerHTML = `<div class="rounded-lg p-4 mb-4 ${
    styles[type] || styles.info
  }">${text}</div>`;
}
