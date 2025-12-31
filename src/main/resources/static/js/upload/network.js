// Handles chunked upload network interaction
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
  const file = candidate.file;
  const chunkSize = 1024 * 1024; // 1MB chunks
  const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize));
  let currentChunk = 0;

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

    uploadNextChunk();
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
