import { initUploadPage } from "./upload/controller.js";

document.addEventListener("DOMContentLoaded", () => {
  initUploadPage({
    uploadPasswordEnabled:
      typeof window !== "undefined" && window.uploadPasswordEnabled !== false,
    metadataEnabled:
      typeof window !== "undefined" &&
      window.isMetadataStrippingEnabled === true,
  });
});
