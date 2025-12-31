function togglePasswordField() {
  const group = document.getElementById("passwordInputGroup");
  const enabled = document.getElementById("appPasswordEnabled")?.checked;
  if (!group) return;
  group.classList.toggle("hidden", !enabled);
}

function toggleDiscordField() {
  const enabled = document.getElementById("discordWebhookEnabled")?.checked;
  document
    .getElementById("discordWebhookUrlGroup")
    ?.classList.toggle("hidden", !enabled);
  document
    .getElementById("discordTestGroup")
    ?.classList.toggle("hidden", !enabled);
  const testBtn = document.getElementById("testDiscord");
  if (testBtn) {
    testBtn.disabled = !enabled;
  }
  updateBatchAvailability();
}

function toggleEmailFields() {
  const enabled = document.getElementById("emailNotificationsEnabled")?.checked;
  document.getElementById("emailConfig")?.classList.toggle("hidden", !enabled);
  updateBatchAvailability();
}

function toggleBatchFields() {
  const batchEnabled = document.getElementById(
    "notificationBatchEnabled"
  )?.checked;
  const batchConfig = document.getElementById("notificationBatchConfig");
  if (batchConfig) {
    batchConfig.classList.toggle("hidden", !batchEnabled);
  }
}

function syncUploadPasswordSetting() {
  const disableUploadPw = document.getElementById("disableUploadPassword");
  const encryptionDisabled = document.getElementById("encryptionDisabled");
  if (!disableUploadPw || !encryptionDisabled) return;

  const forced = disableUploadPw.checked;
  if (forced) {
    encryptionDisabled.checked = true;
    encryptionDisabled.disabled = true;
    encryptionDisabled.classList.add("cursor-not-allowed", "opacity-60");
  } else {
    encryptionDisabled.disabled = false;
    encryptionDisabled.classList.remove("cursor-not-allowed", "opacity-60");
  }
}

function togglePreviewSizeField() {
  const disablePreview = document.getElementById("disablePreview");
  const sizeInput = document.getElementById("maxPreviewSizeBytes");
  if (!disablePreview || !sizeInput) return;

  const disabled = disablePreview.checked;
  sizeInput.disabled = disabled;
  sizeInput.classList.toggle("opacity-60", disabled);
  sizeInput.classList.toggle("cursor-not-allowed", disabled);
}

function updateBatchAvailability() {
  const discordOn = document.getElementById("discordWebhookEnabled")?.checked;
  const emailOn = document.getElementById("emailNotificationsEnabled")?.checked;
  const anyChannel = Boolean(discordOn) || Boolean(emailOn);

  const batchToggle = document.getElementById("notificationBatchEnabled");
  const batchMinutes = document.getElementById("notificationBatchMinutes");
  const row = document.getElementById("notificationBatchRow");

  if (batchToggle) {
    batchToggle.disabled = !anyChannel;
    if (!anyChannel) {
      batchToggle.checked = false;
    }
  }

  if (batchMinutes) {
    batchMinutes.disabled = !anyChannel;
  }

  if (row) {
    row.classList.toggle("opacity-50", !anyChannel);
    row.classList.toggle("cursor-not-allowed", !anyChannel);
  }

  toggleBatchFields();
}

function getCsrfToken() {
  const csrfInput = document.querySelector('input[name="_csrf"]');
  return csrfInput ? csrfInput.value : null;
}

function buildCsrfHeaders(csrf) {
  const headers = { "X-Requested-With": "XMLHttpRequest" };
  if (csrf) {
    headers["X-XSRF-TOKEN"] = csrf;
    headers["X-CSRF-TOKEN"] = csrf;
  }
  return headers;
}

function parsePositiveNumber(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function markValidity(input, message) {
  if (!input) return;
  input.setCustomValidity(message || "");
  input.classList.toggle("border-red-500", Boolean(message));
  input.classList.toggle("focus:ring-red-500", Boolean(message));
  if (message) {
    input.reportValidity();
  }
}

function validateSettingsForm() {
  let firstInvalid = null;

  const maxFileSize = document.getElementById("maxFileSize");
  const maxFileLife = document.getElementById("maxFileLifeTime");
  const fileStoragePath = document.getElementById("fileStoragePath");
  const fileDeletionCron = document.getElementById("fileDeletionCron");
  const sessionLifeTime = document.getElementById("sessionLifeTime");
  const maxPreviewSizeBytes = document.getElementById("maxPreviewSizeBytes");
  const disablePreview = document.getElementById("disablePreview");
  const defaultHomePage = document.getElementById("defaultHomePage");

  const appPasswordEnabled = document.getElementById("appPasswordEnabled");
  const appPassword = document.getElementById("appPassword");

  const discordEnabled = document.getElementById("discordWebhookEnabled");
  const discordUrl = document.getElementById("discordWebhookUrl");

  const emailEnabled = document.getElementById("emailNotificationsEnabled");
  const emailFrom = document.getElementById("emailFrom");
  const emailTo = document.getElementById("emailTo");
  const smtpHost = document.getElementById("smtpHost");
  const smtpPort = document.getElementById("smtpPort");

  const batchEnabled = document.getElementById("notificationBatchEnabled");
  const batchMinutes = document.getElementById("notificationBatchMinutes");

  // reset
  [
    maxFileSize,
    maxFileLife,
    fileStoragePath,
    fileDeletionCron,
    sessionLifeTime,
    maxPreviewSizeBytes,
    defaultHomePage,
    appPassword,
    discordUrl,
    emailFrom,
    emailTo,
    smtpHost,
    smtpPort,
    batchMinutes,
  ].forEach((el) => markValidity(el, ""));

  const maxSizeVal = parsePositiveNumber(maxFileSize?.value);
  if (!maxSizeVal) {
    markValidity(maxFileSize, "Enter a max file size (MB) greater than 0.");
    firstInvalid = firstInvalid || maxFileSize;
  }

  const maxLifeVal = parsePositiveNumber(maxFileLife?.value);
  if (!maxLifeVal) {
    markValidity(
      maxFileLife,
      "Enter a max file lifetime (days) greater than 0."
    );
    firstInvalid = firstInvalid || maxFileLife;
  }

  if (!fileStoragePath?.value.trim()) {
    markValidity(fileStoragePath, "File storage path is required.");
    firstInvalid = firstInvalid || fileStoragePath;
  }

  if (!fileDeletionCron?.value.trim()) {
    markValidity(fileDeletionCron, "Cron expression is required.");
    firstInvalid = firstInvalid || fileDeletionCron;
  }

  const sessionVal = parsePositiveNumber(sessionLifeTime?.value);
  if (!sessionVal && sessionVal !== 0) {
    markValidity(
      sessionLifeTime,
      "Enter a session lifetime in minutes (positive number)."
    );
    firstInvalid = firstInvalid || sessionLifeTime;
  }

  if (!disablePreview?.checked) {
    const previewVal = parsePositiveNumber(maxPreviewSizeBytes?.value);
    if (!previewVal) {
      markValidity(
        maxPreviewSizeBytes,
        "Enter preview size (MB) greater than 0."
      );
      firstInvalid = firstInvalid || maxPreviewSizeBytes;
    }
  }

  if (defaultHomePage && !["upload", "list"].includes(defaultHomePage.value)) {
    markValidity(defaultHomePage, "Choose upload or list.");
    firstInvalid = firstInvalid || defaultHomePage;
  }

  if (appPasswordEnabled?.checked && appPassword && !appPassword.value.trim()) {
    markValidity(
      appPassword,
      "App password is required when protection is enabled."
    );
    firstInvalid = firstInvalid || appPassword;
  }

  if (discordEnabled?.checked) {
    const urlVal = discordUrl?.value.trim();
    if (
      !urlVal ||
      !(urlVal.startsWith("http://") || urlVal.startsWith("https://"))
    ) {
      markValidity(discordUrl, "Enter a valid Discord webhook URL.");
      firstInvalid = firstInvalid || discordUrl;
    }
  }

  if (emailEnabled?.checked) {
    const emailFromVal = emailFrom?.value.trim();
    const emailToVal = emailTo?.value.trim();
    const hostVal = smtpHost?.value.trim();
    const portVal = parsePositiveNumber(smtpPort?.value);

    const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailFromVal || !emailPattern.test(emailFromVal)) {
      markValidity(emailFrom, "Enter a valid From email.");
      firstInvalid = firstInvalid || emailFrom;
    }
    if (!emailToVal) {
      markValidity(emailTo, "Enter at least one recipient.");
      firstInvalid = firstInvalid || emailTo;
    }
    if (!hostVal) {
      markValidity(smtpHost, "SMTP host is required.");
      firstInvalid = firstInvalid || smtpHost;
    }
    if (!portVal) {
      markValidity(smtpPort, "Enter a valid SMTP port.");
      firstInvalid = firstInvalid || smtpPort;
    }
  }

  const anyChannel =
    Boolean(discordEnabled?.checked) || Boolean(emailEnabled?.checked);
  if (batchEnabled?.checked) {
    const minutesVal = parsePositiveNumber(batchMinutes?.value);
    if (!anyChannel) {
      markValidity(batchMinutes, "Enable Discord or Email before batching.");
      firstInvalid = firstInvalid || batchMinutes;
    } else if (!minutesVal) {
      markValidity(batchMinutes, "Enter a batch interval in minutes.");
      firstInvalid = firstInvalid || batchMinutes;
    }
  }

  if (firstInvalid) {
    firstInvalid.focus();
  }

  return !firstInvalid;
}

async function saveSettings(csrf) {
  const form = document.querySelector(
    'form[method="post"][action="/admin/save"]'
  );
  if (!form) return;

  if (!validateSettingsForm()) {
    throw new Error("Validation failed");
  }

  const formData = new FormData(form);
  await fetch("/admin/save", {
    method: "POST",
    credentials: "same-origin",
    headers: buildCsrfHeaders(csrf),
    body: formData,
  });
}

async function sendNotificationTest(target, buttonId, statusId) {
  const button = document.getElementById(buttonId);
  const status = document.getElementById(statusId);
  if (!button || !status) return;

  status.textContent = "Testing…";
  status.className = "text-slate-600 dark:text-slate-300";
  button.disabled = true;

  try {
    const csrf = getCsrfToken();

    // Always save settings before running a test so the latest values are used
    await saveSettings(csrf);

    const response = await fetch(`/admin/notification-test?target=${target}`, {
      method: "POST",
      credentials: "same-origin",
      headers: buildCsrfHeaders(csrf),
    });
    const text = await response.text();
    status.textContent = text;
    status.className = response.ok
      ? "text-green-600 dark:text-green-400"
      : "text-red-600 dark:text-red-400";
  } catch (e) {
    status.textContent = "Request failed. See logs.";
    status.className = "text-red-600 dark:text-red-400";
  } finally {
    button.disabled = false;
  }
}

document.addEventListener("DOMContentLoaded", function () {
  togglePasswordField();
  toggleDiscordField();
  toggleEmailFields();
  updateBatchAvailability();
  syncUploadPasswordSetting();
  togglePreviewSizeField();

  const form = document.querySelector(
    'form[method="post"][action="/admin/save"]'
  );
  if (form) {
    form.addEventListener("submit", (e) => {
      if (!validateSettingsForm()) {
        e.preventDefault();
        e.stopPropagation();
      }
    });
  }

  document
    .getElementById("discordWebhookEnabled")
    ?.addEventListener("change", toggleDiscordField);
  document
    .getElementById("emailNotificationsEnabled")
    ?.addEventListener("change", toggleEmailFields);
  document
    .getElementById("notificationBatchEnabled")
    ?.addEventListener("change", toggleBatchFields);
  document
    .getElementById("disableUploadPassword")
    ?.addEventListener("change", syncUploadPasswordSetting);
  document
    .getElementById("disablePreview")
    ?.addEventListener("change", togglePreviewSizeField);

  document
    .getElementById("testDiscord")
    ?.addEventListener("click", () =>
      sendNotificationTest("discord", "testDiscord", "discordTestStatus")
    );
  document
    .getElementById("testEmail")
    ?.addEventListener("click", () =>
      sendNotificationTest("email", "testEmail", "emailTestStatus")
    );
});

function attachTooltip(wrapperId, tooltipId) {
  const wrapper = document.getElementById(wrapperId);
  const tooltip = document.getElementById(tooltipId);
  if (!wrapper || !tooltip) return;

  const centerTooltip = () => {
    tooltip.style.left = "50%";
    tooltip.style.right = "auto";
    tooltip.style.transform = "translateX(-50%)";
  };

  const adjustPosition = () => {
    centerTooltip();

    const rect = tooltip.getBoundingClientRect();
    const padding = 8;

    if (rect.left < padding) {
      tooltip.style.left = "0";
      tooltip.style.right = "auto";
      tooltip.style.transform = "translateX(0)";
      return;
    }

    if (rect.right > window.innerWidth - padding) {
      tooltip.style.left = "auto";
      tooltip.style.right = "0";
      tooltip.style.transform = "translateX(0)";
    }
  };

  const show = () => {
    tooltip.style.display = "block";
    adjustPosition();
  };
  const hide = () => {
    tooltip.style.display = "none";
    centerTooltip();
  };

  wrapper.addEventListener("mouseenter", show);
  wrapper.addEventListener("mouseleave", hide);
  wrapper.addEventListener("focusin", show);
  wrapper.addEventListener("focusout", hide);

  hide();
}
