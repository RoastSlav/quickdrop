import { decryptStreamOrChunks, detectFormat } from "/js/crypto/crypto-v2.js";

const PUBLIC_ID_LENGTH = 8;
const PBKDF2_ITERATIONS = 300000;

function getContainer() {
  return document.getElementById("shareContainer");
}

function getStatusEl() {
  return document.getElementById("shareStatus");
}

function setStatus(message, isError = false) {
  const el = getStatusEl();
  if (!el) return;
  el.textContent = message || "";
  el.className = isError
    ? "mt-2 text-sm text-red-600 dark:text-red-400"
    : "mt-2 text-sm text-gray-600 dark:text-gray-300";
}

function bufferFromBase64(b64) {
  if (!b64 || b64 === "null" || b64 === "undefined") return null;
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)).buffer;
}

async function deriveKek(secret, saltBytes) {
  const encoder = new TextEncoder();
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    "PBKDF2",
    false,
    ["deriveKey"]
  );
  return crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      salt: saltBytes,
      iterations: PBKDF2_ITERATIONS,
      hash: "SHA-256",
    },
    keyMaterial,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

async function unwrapDek({ secret, publicId, wrappedDek, wrapNonce }) {
  const saltBytes = new TextEncoder().encode(publicId || "");
  const kek = await deriveKek(secret, saltBytes);
  const ivBuf = bufferFromBase64(wrapNonce);
  const cipherBuf = bufferFromBase64(wrappedDek);
  if (!ivBuf || !cipherBuf) throw new Error("Missing wrapped key data");
  const iv = new Uint8Array(ivBuf);
  const cipher = new Uint8Array(cipherBuf);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, kek, cipher);
  return new TextDecoder().decode(plain);
}

async function fetchCipherBlob(url) {
  const resp = await fetch(url, { credentials: "same-origin" });
  if (!resp.ok) throw new Error("Link invalid or expired");
  return resp.blob();
}

async function decryptFileBlob(cipherBlob, password, fileId) {
  const format = await detectFormat(cipherBlob);
  if (format.version !== 2) {
    throw new Error("Unsupported file encryption");
  }
  const { stream } = await decryptStreamOrChunks(cipherBlob, password, {
    fileId: fileId || "",
  });
  const chunks = [];
  for await (const part of stream) {
    chunks.push(part.data);
  }
  const total = chunks.reduce((sum, c) => sum + c.length, 0);
  const merged = new Uint8Array(total);
  let offset = 0;
  chunks.forEach((chunk) => {
    merged.set(chunk, offset);
    offset += chunk.length;
  });
  return new Blob([merged]);
}

function parseTokenFromPath() {
  const parts = window.location.pathname.split("/").filter(Boolean);
  return parts[parts.length - 1] || "";
}

function getSecretFromToken(token) {
  if (!token || token.length <= PUBLIC_ID_LENGTH) return null;
  return token.slice(PUBLIC_ID_LENGTH);
}

function triggerDownload(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName || "download";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

async function handleDownloadClick(event) {
  event.preventDefault();
  const container = getContainer();
  if (!container) return;

  const token = container.dataset.secretToken || parseTokenFromPath();
  const publicId = container.dataset.publicId || token.slice(0, PUBLIC_ID_LENGTH);
  const wrappedDek = container.dataset.wrappedDek;
  const wrapNonce = container.dataset.wrapNonce;
  const downloadUrl = container.dataset.downloadUrl || container.getAttribute("data-download-url");
  const fileName = container.dataset.fileName || "download";
  const fileUuid = container.dataset.fileUuid || "";
  const fileId = fileName || fileUuid || "";
  const encryptionVersion = Number(container.dataset.encryptionVersion || "0");
  const tokenMode = container.dataset.tokenMode || "legacy";

  if (!downloadUrl) {
    setStatus("Missing download link", true);
    return;
  }

  const isV2Mode = tokenMode === "encrypted-v2-share" || encryptionVersion >= 2;

  console.info("[share] download click", {
    tokenMode,
    encryptionVersion,
    isV2Mode,
    hasWrappedDek: Boolean(wrappedDek && wrappedDek !== "null"),
    hasWrapNonce: Boolean(wrapNonce && wrapNonce !== "null"),
    downloadUrl,
  });

  if (!isV2Mode || !wrappedDek || wrappedDek === "null" || !wrapNonce || wrapNonce === "null") {
    // Legacy or v1 flow: no secret-based unwrap required, just follow the link
    window.location.href = downloadUrl;
    return;
  }

  const secret = getSecretFromToken(token);
  if (!secret) {
    setStatus("Invalid share link", true);
    return;
  }

  setStatus("Decrypting…", false);
  try {
    const filePassword = await unwrapDek({ secret, publicId, wrappedDek, wrapNonce });
    console.info("[share] unwrap success", { publicId });
    const cipherBlob = await fetchCipherBlob(downloadUrl);
    console.info("[share] fetched cipher blob", { size: cipherBlob.size });
    const plainBlob = await decryptFileBlob(cipherBlob, filePassword, fileId);
    console.info("[share] decryption success", { plainSize: plainBlob.size });
    triggerDownload(plainBlob, fileName);
    setStatus("Decrypted and ready.", false);
  } catch (err) {
    console.error(err);
    setStatus(err?.message || "Decryption failed", true);
  }
}

function init() {
  const btn = document.getElementById("downloadButton");
  if (btn) {
    btn.addEventListener("click", handleDownloadClick);
  }
}

document.addEventListener("DOMContentLoaded", init);
