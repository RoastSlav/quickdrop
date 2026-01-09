import { initUploadPage } from "./upload/controller.js";

document.addEventListener("DOMContentLoaded", () => {
  initUploadPage({
    uploadPasswordEnabled:
      typeof window !== "undefined" && window.uploadPasswordEnabled !== false,
    metadataEnabled:
      typeof window !== "undefined" &&
      window.isMetadataStrippingEnabled === true,
    encryptionEnabled:
      typeof window !== "undefined" && window.isEncryptionEnabled === true,
  });
});
