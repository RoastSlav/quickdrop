// Handles chunked upload network interaction
export async function uploadCandidate(
  candidate,
  {
    uploadPasswordEnabled,
    form,
    progressBar,
    statusEl,
    indicatorEl,
    encryptionPlan,
    onSuccess,
    onWarn,
    onError,
  }
) {
  const file = candidate.file;
  const chunkSize = 1024 * 1024; // 1MB chunks
  const usingEncryption = Boolean(encryptionPlan);
  const totalChunks = usingEncryption
    ? encryptionPlan.payloadChunkCount
    : Math.max(1, Math.ceil(file.size / chunkSize));
  let currentChunk = 0;
  let encIterator = null;
  if (usingEncryption) {
    encIterator = encryptionPlan.stream[Symbol.asyncIterator]();
  }

  const progressElement =
    progressBar || document.getElementById("uploadProgress");
  const statusElement = statusEl || document.getElementById("uploadStatus");
  const indicatorElement =
    indicatorEl || document.getElementById("uploadIndicator");

  indicatorElement?.classList.remove("hidden");
  if (statusElement) statusElement.innerText = "Upload started...";
  if (progressElement) {
    progressElement.style.width = "0%";
    progressElement.setAttribute("aria-valuenow", "0");
  }

  return new Promise((resolve, reject) => {
    const uploadNextChunk = async () => {
      let chunkData;
      if (usingEncryption) {
        const next = await encIterator.next();
        if (next.done) {
          onWarn?.();
          reject(new Error("Unexpected end of encrypted stream."));
          return;
        }
        chunkData = new Blob([next.value.data]);
      } else {
        const start = currentChunk * chunkSize;
        const end = Math.min(start + chunkSize, file.size);
        chunkData = file.slice(start, end);
      }
      const formData = buildChunkFormData(
        chunkData,
        currentChunk,
        candidate.name,
        totalChunks,
        usingEncryption
          ? encryptionPlan.totalSize || candidate.file.size
          : file.size,
        candidate,
        uploadPasswordEnabled,
        form
      );

      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/file/upload-chunk", true);

      const csrfTokenElement = document.querySelector('input[name="_csrf"]');
      if (csrfTokenElement) {
        xhr.setRequestHeader("X-CSRF-TOKEN", csrfTokenElement.value);
      }

      xhr.onload = () => {
        if (xhr.status === 200) {
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
              statusElement.innerText = "Upload complete. Encrypting...";
            }
            uploadNextChunk();
          } else {
            if (statusElement) statusElement.innerText = "Upload complete.";
            if (response && response.uuid) {
              onSuccess?.(response.uuid);
              resolve(response);
            } else {
              onWarn?.();
              reject(new Error("Upload finished without file information."));
            }
          }
        } else {
          console.error("Upload error:", xhr.responseText);
          onError?.();
          reject(new Error("Upload failed."));
        }
      };

      xhr.onerror = () => {
        onError?.();
        reject(new Error("An error occurred during upload."));
      };

      xhr.send(formData);
    };

    uploadNextChunk().catch((err) => {
      console.error("Upload failed during chunk processing", err);
      onError?.();
      reject(err);
    });
  });
}

function buildChunkFormData(
  chunk,
  chunkNumber,
  fileName,
  totalChunks,
  fileSize,
  candidate,
  uploadPasswordEnabled,
  form
) {
  const uploadForm = form || document.getElementById("uploadForm");
  const formData = new FormData();

  formData.append("file", chunk);
  formData.append("fileName", fileName);
  formData.append("chunkNumber", chunkNumber);
  formData.append("totalChunks", totalChunks);
  formData.append("fileSize", fileSize);
  const encVersionInput = uploadForm?.querySelector('input[name="encryptionVersion"]');
  const plaintextSizeInput = uploadForm?.querySelector('input[name="plaintextSize"]');
  const encVersion = encVersionInput?.value || uploadForm?.dataset?.encryptionVersion;
  const plainSize = plaintextSizeInput?.value || uploadForm?.dataset?.plaintextSize;
  if (encVersion) formData.append("encryptionVersion", encVersion);
  if (plainSize) formData.append("plaintextSize", plainSize);

  if (chunkNumber === 0) {
    console.info("[upload] chunk form data prepared", {
      chunkNumber,
      totalChunks,
      fileSize,
      encVersion,
      plainSize,
      folderUpload: candidate?.folderUpload || false,
    });
  }

  if (candidate) {
    formData.append("folderUpload", candidate.folderUpload ? "true" : "false");
    if (candidate.folderUpload) {
      formData.append("folderName", candidate.folderName || "");
      formData.append("folderManifest", candidate.folderManifest || "[]");
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
