import {formatBytes} from "./format.js";

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

const t = (key, fallback) => window.i18n?.upload?.[key] || fallback;

const STATUS_LABEL = {
    failed: () => t("reviewStatusFailed", "Metadata kept"),
    warning: () => t("reviewStatusWarning", "May still carry metadata"),
};

/** Collapsed/expanded label for the review toggle. The caret is decorative. */
export function setReviewToggleLabel(button, expanded) {
    button.textContent = "";
    const caret = document.createElement("span");
    caret.setAttribute("aria-hidden", "true");
    caret.textContent = expanded ? "\u25BE" : "\u25B8";
    button.append(caret, ` ${t("technicalInfo", "Technical info")}`);
}

function reviewRow(entry) {
    const row = document.createElement("li");
    row.className = "flex flex-wrap items-center gap-x-2 gap-y-1";

    const name = document.createElement("span");
    name.className = "font-mono break-all";
    name.textContent = entry.name;

    const size = document.createElement("span");
    size.className = "opacity-70";
    size.textContent = formatBytes(entry.size);

    const status = document.createElement("span");
    status.className = `badge ${entry.status === "failed" ? "badge-danger" : "badge-amber"}`;
    status.textContent = STATUS_LABEL[entry.status]?.() || entry.status;

    row.append(name, size, status);

    const reason = entry.reason || entry.warnings?.[0];
    if (reason) {
        const detail = document.createElement("span");
        detail.className = "basis-full opacity-80";
        detail.textContent = reason;
        row.appendChild(detail);
    }
    return row;
}

/**
 * Lists only the files that need attention, but counts them against the whole selection so
 * the scale is clear when three of two hundred files could not be cleaned.
 */
function renderReviewDetails(results, ui) {
    if (!ui.uploadWarningDetails || !ui.uploadWarningList) return;

    const affected = (results || []).filter((entry) => entry && entry.status !== "ok");
    if (affected.length === 0) {
        ui.uploadWarningDetails.classList.add("hidden");
        ui.uploadWarningList.classList.add("hidden");
        ui.uploadWarningList.textContent = "";
        return;
    }

    ui.uploadWarningDetails.classList.remove("hidden");
    ui.uploadWarningDetails.setAttribute("aria-expanded", "false");
    setReviewToggleLabel(ui.uploadWarningDetails, false);

    ui.uploadWarningList.classList.add("hidden");
    ui.uploadWarningList.textContent = "";

    const summary = document.createElement("p");
    summary.className = "mb-2 font-semibold";
    summary.textContent = t("reviewAffected", "{0} of {1} files need a closer look")
        .replace("{0}", affected.length)
        .replace("{1}", results.length);

    const list = document.createElement("ul");
    list.className = "flex flex-col gap-2";
    affected.forEach((entry) => list.appendChild(reviewRow(entry)));

    ui.uploadWarningList.append(summary, list);
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

    // Preparing a large selection takes a while, so the user can back out of it too.
    if (ui.uploadCancel) {
        const cancellable =
            state === UploadState.NEEDS_CONFIRMATION || state === UploadState.PROCESSING;
        ui.uploadCancel.classList.toggle("hidden", !cancellable);
        ui.uploadCancel.disabled = state === UploadState.UPLOADING;
    }

    if (ui.fileButton) ui.fileButton.disabled = isBusy;
    if (ui.folderButton) ui.folderButton.disabled = isBusy;
    if (ui.dropZone) ui.dropZone.classList.toggle("pointer-events-none", isBusy);

    if (state !== UploadState.NEEDS_CONFIRMATION) {
        clearStripWarning(ui);
    }
}

/**
 * @param {{results?: object[]}} review the candidate set returned by the metadata pass
 */
export function renderStripReview(review, ui = getUIRefs()) {
    if (!ui.uploadWarning) return;

    const results = review?.results || [];
    if (!results.some((entry) => entry && entry.status !== "ok")) {
        clearStripWarning(ui);
        return;
    }

    ui.uploadWarning.classList.remove("hidden");
    if (ui.uploadWarningText) {
        ui.uploadWarningText.textContent = METADATA_PRIVACY_NOTICE();
    } else {
        ui.uploadWarning.textContent = METADATA_PRIVACY_NOTICE();
    }
    renderReviewDetails(results, ui);
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
        setReviewToggleLabel(ui.uploadWarningDetails, false);
    }
    if (ui.uploadWarningList) {
        ui.uploadWarningList.classList.add("hidden");
        ui.uploadWarningList.textContent = "";
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
    // Fix 6: use DOM construction instead of innerHTML to avoid XSS
    container.textContent = "";
    const styles = {
        danger: "bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-100",
        warning: "bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-100",
        success: "bg-sky-100 text-sky-700 dark:bg-sky-900 dark:text-sky-100",
        info: "bg-sky-100 text-sky-700 dark:bg-sky-900 dark:text-sky-100",
    };
    const div = document.createElement("div");
    div.className = `rounded-lg p-4 mb-4 ${styles[type] || styles.info}`;
    div.textContent = text;
    container.appendChild(div);
}
