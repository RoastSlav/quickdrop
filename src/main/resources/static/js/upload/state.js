export const UploadState = Object.freeze({
  IDLE: "IDLE",
  PROCESSING: "PROCESSING",
  READY: "READY",
  NEEDS_CONFIRMATION: "NEEDS_CONFIRMATION",
  UPLOADING: "UPLOADING",
});

const METADATA_PRIVACY_NOTICE = () =>
    window.i18n?.upload?.metadataPrivacyNotice ||
    "All known metadata will be removed, but this format may still contain details that give information about your identity.";

function escapeHtml(value) {
  return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
}

function renderWarningDetails(entries, ui) {
  if (!ui.uploadWarningDetails || !ui.uploadWarningList) return;
  if (!entries || entries.length === 0) {
    ui.uploadWarningDetails.classList.add("hidden");
    ui.uploadWarningList.classList.add("hidden");
    ui.uploadWarningList.innerHTML = "";
    return;
  }

  const items = entries
      .map((entry) => {
        const name = entry?.name ? `<strong>${escapeHtml(entry.name)}</strong>: ` : "";
        const reason = entry?.reason ? escapeHtml(entry.reason) : "No technical details.";
        return `<li>${name}${reason}</li>`;
      })
      .join("");

  ui.uploadWarningDetails.classList.remove("hidden");
  ui.uploadWarningDetails.setAttribute("aria-expanded", "false");
  ui.uploadWarningDetails.innerHTML = "<span aria-hidden=\"true\">&#9656;</span> Technical info";
  ui.uploadWarningList.classList.add("hidden");
  ui.uploadWarningList.innerHTML = `<ul class=\"list-disc pl-5 space-y-1\">${items}</ul>`;
}

export function getUIRefs() {
  return {
    uploadPrimary: document.getElementById("uploadPrimary"),
    uploadCancel: document.getElementById("uploadCancel"),
    uploadWarning: document.getElementById("uploadWarning"),
    uploadWarningText: document.getElementById("uploadWarningText"),
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
      const i18n = window.i18n?.upload || {};
    ui.uploadPrimary.textContent =
      state === UploadState.NEEDS_CONFIRMATION
          ? (i18n.buttonConfirm || "Confirm Upload")
        : state === UploadState.UPLOADING
              ? (i18n.buttonUploading || "Uploading...")
              : (i18n.buttonIdle || "Upload");
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

export function renderStripWarning(entries = [], ui = getUIRefs()) {
  if (!ui.uploadWarning) return;
  if (!entries || entries.length === 0) {
    clearStripWarning(ui);
    return;
  }

  ui.uploadWarning.classList.remove("hidden");
  if (ui.uploadWarningText) {
      ui.uploadWarningText.textContent = METADATA_PRIVACY_NOTICE();
  } else {
      ui.uploadWarning.textContent = METADATA_PRIVACY_NOTICE();
  }
  renderWarningDetails(entries, ui);
}

export function clearStripWarning(ui = getUIRefs()) {
  if (ui.uploadWarning) {
    ui.uploadWarning.classList.add("hidden");
    if (ui.uploadWarningText) {
      ui.uploadWarningText.textContent = "";
    } else {
      ui.uploadWarning.textContent = "";
    }
  }
  if (ui.uploadWarningDetails) {
    ui.uploadWarningDetails.classList.add("hidden");
    ui.uploadWarningDetails.setAttribute("aria-expanded", "false");
    ui.uploadWarningDetails.innerHTML = "<span aria-hidden=\"true\">&#9656;</span> Technical info";
  }
  if (ui.uploadWarningList) {
    ui.uploadWarningList.classList.add("hidden");
    ui.uploadWarningList.innerHTML = "";
  }
}

export function showMessage(type, text) {
    // Prefer the global toast helper if available
    if (typeof window !== "undefined" && typeof window.toast === "function") {
        const kindMap = {danger: "error", success: "success", warning: "warning", info: "info"};
        window.toast(text, kindMap[type] || "info");
        return;
    }
  const container = document.getElementById("messageContainer");
  if (!container) return;
  container.textContent = "";
  const wrapper = document.createElement("div");
    const cls = {
        danger: "alert alert-danger",
        warning: "alert alert-warning",
        success: "alert alert-success",
        info: "alert alert-info"
    };
    wrapper.className = cls[type] || cls.info;
  wrapper.textContent = text;
  container.appendChild(wrapper);
}
