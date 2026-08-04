// Handles chunked upload network interaction
const activeUploadIds = new Set();
let unloadAbortListenerAttached = false;
const UPLOAD_CHUNK_SIZE = 4 * 1024 * 1024;
const UPLOAD_STATUS_POLL_INTERVAL_MS = 2000;
const UPLOAD_STATUS_MAX_FAILURES = 15;

// Extracts the server's {"error": "..."} message from a failed response body, if present,
// so callers can surface the specific reason instead of a generic fallback.
function parseServerErrorMessage(responseText) {
    if (!responseText) return null;
    try {
        const parsed = JSON.parse(responseText);
        return typeof parsed?.error === "string" ? parsed.error : null;
    } catch (e) {
        return null;
    }
}

function registerActiveUpload(uploadId) {
    activeUploadIds.add(uploadId);
    ensureUnloadAbortListener();
    return () => activeUploadIds.delete(uploadId);
}

function ensureUnloadAbortListener() {
    if (unloadAbortListenerAttached) return;
    unloadAbortListenerAttached = true;
    window.addEventListener("pagehide", abortActiveUploads, {capture: true});
    window.addEventListener("beforeunload", abortActiveUploads, {capture: true});
}

function abortActiveUploads() {
    for (const uploadId of Array.from(activeUploadIds)) {
        sendUploadAbort(uploadId);
    }
    activeUploadIds.clear();
}

function abortUpload(uploadId) {
    activeUploadIds.delete(uploadId);
    sendUploadAbort(uploadId);
}

function sendUploadAbort(uploadId) {
    if (!uploadId) return;

    const formData = buildAbortFormData(uploadId);
    if (navigator.sendBeacon && navigator.sendBeacon("/api/file/upload-abort", formData)) {
        return;
    }

    const headers = {};
    const csrfToken = getCsrfToken();
    if (csrfToken) {
        headers["X-XSRF-TOKEN"] = csrfToken;
    }

    fetch("/api/file/upload-abort", {
        method: "POST",
        body: formData,
        headers,
        credentials: "same-origin",
        keepalive: true,
    }).catch(() => {
    });
}

function buildAbortFormData(uploadId) {
    const formData = new FormData();
    formData.append("uploadId", uploadId);

    const csrfToken = getCsrfFormToken();
    if (csrfToken) {
        formData.append("_csrf", csrfToken);
    }

    return formData;
}

function getCsrfFormToken() {
    return (
        document.querySelector('input[name="_csrf"]')?.value ||
        document.querySelector('meta[name="_csrf"]')?.content ||
        getCookieValue("XSRF-TOKEN") ||
        ""
    );
}

function isSuccessfulHttpStatus(status) {
    return status >= 200 && status < 300;
}

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function setFinalizingStatus(statusElement, uploadPasswordEnabled) {
    if (!statusElement) return;
    const passwordValue = document.getElementById("password")?.value.trim();
    statusElement.innerText =
        uploadPasswordEnabled && passwordValue
            ? window.i18n?.upload?.statusEncrypting || "Upload complete. Encrypting..."
            : window.i18n?.upload?.statusFinalizing || "Finalizing upload...";
}

async function waitForUploadCompletion(uploadId, statusElement, uploadPasswordEnabled) {
    let consecutiveFailures = 0;
    setFinalizingStatus(statusElement, uploadPasswordEnabled);

    while (true) {
        try {
            const response = await fetch(`/api/file/upload-status/${encodeURIComponent(uploadId)}`, {
                credentials: "same-origin",
                headers: {Accept: "application/json"},
            });
            if (!response.ok) {
                throw new Error(`Upload status failed with status ${response.status}.`);
            }

            const status = await response.json();
            consecutiveFailures = 0;

            if (status.status === "complete" && status.uuid) {
                return status;
            }
            if (status.status === "failed" || status.status === "aborted" || status.status === "unknown") {
                const error = new Error(status.error || "Upload processing failed.");
                error.terminalUploadStatus = true;
                throw error;
            }
        } catch (error) {
            if (error.terminalUploadStatus) {
                throw error;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= UPLOAD_STATUS_MAX_FAILURES) {
                throw error;
            }
        }

        await delay(UPLOAD_STATUS_POLL_INTERVAL_MS);
    }
}

export async function uploadCandidate(
    candidate,
    {
        uploadPasswordEnabled,
        form,
        progressBar,
        statusEl,
        indicatorEl,
        onSuccess,
        onWarn,
        onError,
    }
) {
    if (candidate.streamUpload) {
        return uploadStreamCandidate(candidate, {
            uploadPasswordEnabled,
            form,
            progressBar,
            statusEl,
            indicatorEl,
            onSuccess,
            onWarn,
            onError,
        });
    }

    const file = candidate.file;
    const chunkSize = UPLOAD_CHUNK_SIZE;
    const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize));
    let currentChunk = 0;
    // Stable per-upload identifier so concurrent uploads of the same filename
    // never collide server-side and temp-file names are path-traversal-safe.
    const uploadId = crypto.randomUUID();
    let unregisterAbort = registerActiveUpload(uploadId);
    const finishUpload = () => {
        if (!unregisterAbort) return;
        unregisterAbort();
        unregisterAbort = null;
    };
    const failUpload = () => {
        abortUpload(uploadId);
        finishUpload();
    };

    const progressElement =
        progressBar || document.getElementById("uploadProgress");
    const statusElement = statusEl || document.getElementById("uploadStatus");
    const indicatorElement =
        indicatorEl || document.getElementById("uploadIndicator");

    indicatorElement?.classList.remove("hidden");
    if (statusElement) statusElement.innerText = window.i18n?.upload?.statusStarted || "Upload started...";
    if (progressElement) {
        progressElement.style.width = "0%";
        progressElement.setAttribute("aria-valuenow", "0");
    }

    return new Promise((resolve, reject) => {
        const uploadNextChunk = () => {
            const start = currentChunk * chunkSize;
            const end = Math.min(start + chunkSize, file.size);
            const chunk = file.slice(start, end);
            const formData = buildChunkFormData(
                chunk,
                currentChunk,
                candidate.name,
                totalChunks,
                file.size,
                candidate,
                uploadPasswordEnabled,
                form,
                uploadId
            );

            const xhr = new XMLHttpRequest();
            xhr.open("POST", "/api/file/upload-chunk", true);

            const csrfToken = getCsrfToken();
            if (csrfToken) {
                xhr.setRequestHeader("X-XSRF-TOKEN", csrfToken);
            }

            xhr.onload = async () => {
                if (isSuccessfulHttpStatus(xhr.status)) {
                    let response = null;
                    if (xhr.responseText && xhr.responseText.trim().length > 0) {
                        try {
                            response = JSON.parse(xhr.responseText);
                        } catch (e) {
                            console.warn("Failed to parse server response:", e);
                        }
                    }

                    currentChunk++;
                    const percentComplete = (currentChunk / totalChunks) * 100;
                    if (progressElement) {
                        progressElement.style.width = percentComplete + "%";
                        progressElement.setAttribute(
                            "aria-valuenow",
                            String(percentComplete)
                        );
                    }

                    if (currentChunk < totalChunks) {
                        const passwordValue = document
                            .getElementById("password")
                            ?.value.trim();
                        if (
                            uploadPasswordEnabled &&
                            currentChunk === totalChunks - 1 &&
                            passwordValue &&
                            statusElement
                        ) {
                            statusElement.innerText = window.i18n?.upload?.statusEncrypting || "Upload complete. Encrypting...";
                        }
                        uploadNextChunk();
                    } else {
                        try {
                            if (response && response.uuid) {
                                if (statusElement) statusElement.innerText = window.i18n?.upload?.statusComplete || "Upload complete.";
                                finishUpload();
                                onSuccess?.(response.uuid);
                                resolve(response);
                            } else if (response?.status === "processing") {
                                const completed = await waitForUploadCompletion(
                                    response.uploadId || uploadId,
                                    statusElement,
                                    uploadPasswordEnabled
                                );
                                if (statusElement) statusElement.innerText = window.i18n?.upload?.statusComplete || "Upload complete.";
                                finishUpload();
                                onSuccess?.(completed.uuid);
                                resolve(completed);
                            } else {
                                failUpload();
                                onWarn?.();
                                reject(new Error("Upload finished without file information."));
                            }
                        } catch (error) {
                            failUpload();
                            onError?.(error?.message);
                            reject(error);
                        }
                    }
                } else {
                    console.error("Upload error:", xhr.responseText);
                    failUpload();
                    const serverMessage = parseServerErrorMessage(xhr.responseText);
                    onError?.(serverMessage);
                    reject(new Error(serverMessage || "Upload failed."));
                }
            };

            xhr.onerror = () => {
                failUpload();
                onError?.();
                reject(new Error("An error occurred during upload."));
            };

            xhr.send(formData);
        };

        uploadNextChunk();
    });
}

async function uploadStreamCandidate(
    candidate,
    {
        uploadPasswordEnabled,
        form,
        progressBar,
        statusEl,
        indicatorEl,
        onSuccess,
        onWarn,
        onError,
    }
) {
    const chunkSize = UPLOAD_CHUNK_SIZE;
    const totalChunks = Math.max(1, Math.ceil(candidate.size / chunkSize));
    const uploadId = crypto.randomUUID();
    let unregisterAbort = registerActiveUpload(uploadId);
    const finishUpload = () => {
        if (!unregisterAbort) return;
        unregisterAbort();
        unregisterAbort = null;
    };
    const failUpload = () => {
        abortUpload(uploadId);
        finishUpload();
    };
    const progressElement =
        progressBar || document.getElementById("uploadProgress");
    const statusElement = statusEl || document.getElementById("uploadStatus");
    const indicatorElement =
        indicatorEl || document.getElementById("uploadIndicator");

    indicatorElement?.classList.remove("hidden");
    if (statusElement) statusElement.innerText = window.i18n?.upload?.statusStarted || "Upload started...";
    if (progressElement) {
        progressElement.style.width = "0%";
        progressElement.setAttribute("aria-valuenow", "0");
    }

    let currentChunk = 0;
    let bufferedSize = 0;
    let bufferedParts = [];
    let generatedSize = 0;

    const appendPart = (part) => {
        if (!part || part.length === 0) return;
        bufferedParts.push(part);
        bufferedSize += part.length;
        generatedSize += part.length;
    };

    const takeBytes = (size) => {
        const chunk = new Uint8Array(size);
        let offset = 0;

        while (offset < size) {
            const part = bufferedParts[0];
            const needed = size - offset;
            if (part.length <= needed) {
                chunk.set(part, offset);
                offset += part.length;
                bufferedParts.shift();
            } else {
                chunk.set(part.subarray(0, needed), offset);
                bufferedParts[0] = part.subarray(needed);
                offset += needed;
            }
        }

        bufferedSize -= size;
        return chunk;
    };

    const sendNextChunk = async (bytes) => {
        const chunkNumber = currentChunk;
        const blob = new Blob([bytes], {type: "application/zip"});
        const response = await sendChunk(
            blob,
            chunkNumber,
            candidate.name,
            totalChunks,
            candidate.size,
            candidate,
            uploadPasswordEnabled,
            form,
            uploadId
        );

        currentChunk++;
        const percentComplete = (currentChunk / totalChunks) * 100;
        if (progressElement) {
            progressElement.style.width = percentComplete + "%";
            progressElement.setAttribute("aria-valuenow", String(percentComplete));
        }

        return response;
    };

    try {
        for await (const part of candidate.createStream()) {
            appendPart(part);
            while (currentChunk < totalChunks - 1 && bufferedSize >= chunkSize) {
                await sendNextChunk(takeBytes(chunkSize));
            }
        }

        if (generatedSize !== candidate.size) {
            throw new Error(`Generated archive size ${generatedSize} did not match expected size ${candidate.size}.`);
        }
        if (currentChunk !== totalChunks - 1) {
            throw new Error("Archive stream ended before all chunks were produced.");
        }

        const response = await sendNextChunk(takeBytes(bufferedSize));
        if (response && response.uuid) {
            if (statusElement) statusElement.innerText = window.i18n?.upload?.statusComplete || "Upload complete.";
            finishUpload();
            onSuccess?.(response.uuid);
            return response;
        }
        if (response?.status === "processing") {
            const completed = await waitForUploadCompletion(
                response.uploadId || uploadId,
                statusElement,
                uploadPasswordEnabled
            );
            if (statusElement) statusElement.innerText = window.i18n?.upload?.statusComplete || "Upload complete.";
            finishUpload();
            onSuccess?.(completed.uuid);
            return completed;
        }

        onWarn?.();
        throw new Error("Upload finished without file information.");
    } catch (error) {
        console.error("Upload error:", error);
        failUpload();
        onError?.();
        throw error;
    }
}

function sendChunk(
    chunk,
    chunkNumber,
    fileName,
    totalChunks,
    fileSize,
    candidate,
    uploadPasswordEnabled,
    form,
    uploadId
) {
    const formData = buildChunkFormData(
        chunk,
        chunkNumber,
        fileName,
        totalChunks,
        fileSize,
        candidate,
        uploadPasswordEnabled,
        form,
        uploadId
    );

    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/file/upload-chunk", true);

        const csrfToken = getCsrfToken();
        if (csrfToken) {
            xhr.setRequestHeader("X-XSRF-TOKEN", csrfToken);
        }

        xhr.onload = () => {
            if (!isSuccessfulHttpStatus(xhr.status)) {
                reject(new Error(xhr.responseText || `Upload failed with status ${xhr.status}.`));
                return;
            }

            if (!xhr.responseText || xhr.responseText.trim().length === 0) {
                resolve(null);
                return;
            }

            try {
                resolve(JSON.parse(xhr.responseText));
            } catch (error) {
                console.warn("Failed to parse server response:", error);
                resolve(null);
            }
        };

        xhr.onerror = () => reject(new Error("An error occurred during upload."));
        xhr.send(formData);
    });
}

function getCookieValue(name) {
    const prefix = `${name}=`;
    const cookie = document.cookie
        .split(";")
        .map((value) => value.trim())
        .find((value) => value.startsWith(prefix));
    if (!cookie) return "";

    try {
        return decodeURIComponent(cookie.substring(prefix.length));
    } catch (_) {
        return cookie.substring(prefix.length);
    }
}

function getCsrfToken() {
    return (
        getCookieValue("XSRF-TOKEN") ||
        document.querySelector('input[name="_csrf"]')?.value ||
        document.querySelector('meta[name="_csrf"]')?.content ||
        ""
    );
}

function buildChunkFormData(
    chunk,
    chunkNumber,
    fileName,
    totalChunks,
    fileSize,
    candidate,
    uploadPasswordEnabled,
    form,
    uploadId
) {
    const uploadForm = form || document.getElementById("uploadForm");
    const formData = new FormData();

    formData.append("file", chunk);
    formData.append("fileName", fileName);
    if (uploadId) {
        formData.append("uploadId", uploadId);
    }
    formData.append("chunkNumber", chunkNumber);
    formData.append("totalChunks", totalChunks);
    formData.append("fileSize", fileSize);

    if (candidate) {
        formData.append("folderUpload", candidate.folderUpload ? "true" : "false");
        if (candidate.folderUpload) {
            formData.append("folderName", candidate.folderName || "");
            // Fix 5: only send the manifest on the last chunk to avoid wasted bandwidth
            if (chunkNumber === totalChunks - 1) {
                formData.append("folderManifest", candidate.folderManifest || "[]");
            }
        }
    }

    const keepIndefinitelyCheckbox = document.getElementById("keepIndefinitely");
    formData.append(
        "keepIndefinitely",
        keepIndefinitelyCheckbox && keepIndefinitelyCheckbox.checked
            ? "true"
            : "false"
    );
    const hiddenCheckbox = document.getElementById("hidden");
    if (hiddenCheckbox) {
        formData.append("hidden", hiddenCheckbox.checked ? "true" : "false");
    }

    Array.from(uploadForm.elements).forEach((el) => {
        if (!uploadPasswordEnabled && el.name === "password") {
            return;
        }
        if (el.name && el.type !== "file" && el.type !== "checkbox") {
            formData.append(el.name, el.value.trim());
        }
    });

    return formData;
}
