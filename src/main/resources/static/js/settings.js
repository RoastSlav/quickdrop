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
  syncSmtpSecurityModes();
  updateBatchAvailability();
}

function syncSmtpSecurityModes(changedMode = null) {
  const startTls = document.getElementById("smtpUseTls");
  const implicitSsl = document.getElementById("smtpUseSsl");
  if (!startTls || !implicitSsl) return;

  if (changedMode === "ssl" && implicitSsl.checked) {
    startTls.checked = false;
  } else if (changedMode === "tls" && startTls.checked) {
    implicitSsl.checked = false;
  } else if (startTls.checked && implicitSsl.checked) {
    // Prefer explicit STARTTLS unless the user explicitly toggled SSL.
    implicitSsl.checked = false;
  }
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

function syncShareLinkSettings() {
  const disableShareLinks = document.getElementById("shareLinksDisabled");
  const simplifiedShareLinks = document.getElementById("simplifiedShareLinks");
  const simplifiedRow = document.getElementById("simplifiedShareLinksRow");

  const disabled = Boolean(disableShareLinks?.checked);

  if (simplifiedShareLinks) {
    simplifiedShareLinks.disabled = disabled;
    simplifiedShareLinks.classList.toggle("cursor-not-allowed", disabled);
    simplifiedShareLinks.classList.toggle("opacity-60", disabled);
    if (disabled) {
      simplifiedShareLinks.checked = false;
    }
  }

  if (simplifiedRow) {
    simplifiedRow.classList.toggle("opacity-60", disabled);
    simplifiedRow.classList.toggle("cursor-not-allowed", disabled);
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

function setLogoLabel(text) {
  const label = document.getElementById("appLogoLabelText");
  if (label) {
    label.textContent = text || "Browse";
  }
}

function showLogoStatus(message, toneClass) {
  const status = document.getElementById("logoStatus");
  if (!status) return;
  status.textContent = message || "";
  status.classList.remove(
    "text-sky-600",
    "dark:text-sky-400",
    "text-amber-600",
    "dark:text-amber-400",
    "hidden"
  );
  if (toneClass) {
    status.classList.add(toneClass);
  } else {
    status.classList.add("text-sky-600", "dark:text-sky-400");
  }
}

function hideLogoStatus() {
  const status = document.getElementById("logoStatus");
  if (status) {
    status.classList.add("hidden");
  }
}

function resetLogoSelection() {
  const fileInput = document.getElementById("appLogo");
  const clearInput = document.getElementById("clearLogo");
  if (fileInput) {
    fileInput.value = "";
  }
  if (clearInput) {
    clearInput.value = "true";
  }
  setLogoLabel("Browse");
  showLogoStatus("Default logo will be restored on save.");
}

function markLogoAsReplaced(event) {
  const clearInput = document.getElementById("clearLogo");
  if (clearInput) {
    clearInput.value = "false";
  }
  const fileName = event?.target?.files?.[0]?.name || "Browse";
  setLogoLabel(fileName);
  hideLogoStatus();
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

function hasStoredAppPassword() {
  const form = document.getElementById("settingsForm");
  return form?.dataset?.appPasswordSet === "true";
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

function sv(key, fallback) {
  return window.i18n?.settings?.validation?.[key] || fallback;
}

/**
 * Returns true when `expr` is a valid Spring 6-field cron expression.
 * Does not validate ranges — only structural format (six whitespace-separated tokens).
 * @param {string} expr
 * @returns {boolean}
 */
function isValidSpringCron(expr) {
  if (!expr || typeof expr !== 'string') return false;
  const parts = expr.trim().split(/\s+/);
  if (parts.length !== 6) return false;
  const field = /^(\*|\?|(\d+(-\d+)?(\/\d+)?)(,(\d+(-\d+)?(\/\d+)?))*|\*\/\d+|[A-Z]{3}(-[A-Z]{3})?(,[A-Z]{3}(-[A-Z]{3})?)*)$/i;
  return parts.every(p => field.test(p));
}

/**
 * Validates all settings form inputs, marking invalid fields with native
 * constraint messages. Focuses the first invalid field.
 * @returns {boolean} true when the form is fully valid
 */
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

  const storedAppPassword = hasStoredAppPassword();

  // reset
  [
    maxFileSize, maxFileLife, fileStoragePath, fileDeletionCron,
    sessionLifeTime, maxPreviewSizeBytes, defaultHomePage,
    appPassword, discordUrl, emailFrom, emailTo, smtpHost, smtpPort, batchMinutes,
  ].forEach((el) => markValidity(el, ""));

  const maxSizeVal = parsePositiveNumber(maxFileSize?.value);
  if (!maxSizeVal) {
    markValidity(maxFileSize, sv('maxFileSize', 'Enter a max file size (MB) greater than 0.'));
    firstInvalid = firstInvalid || maxFileSize;
  }

  const maxLifeVal = parsePositiveNumber(maxFileLife?.value);
  if (!maxLifeVal) {
    markValidity(maxFileLife, sv('maxFileLifetime', 'Enter a max file lifetime (days) greater than 0.'));
    firstInvalid = firstInvalid || maxFileLife;
  }

  if (!fileStoragePath?.value.trim()) {
    markValidity(fileStoragePath, sv('fileStoragePath', 'File storage path is required.'));
    firstInvalid = firstInvalid || fileStoragePath;
  }

  const cronVal = fileDeletionCron?.value.trim();
  if (!cronVal) {
    markValidity(fileDeletionCron, sv('cron', 'Cron expression is required.'));
    firstInvalid = firstInvalid || fileDeletionCron;
  } else if (!isValidSpringCron(cronVal)) {
    markValidity(fileDeletionCron, sv('cronInvalid', 'Invalid cron expression (6 fields required, e.g. 0 0 2 * * *).'));
    firstInvalid = firstInvalid || fileDeletionCron;
  } else {
    markValidity(fileDeletionCron, '');
  }

  const sessionVal = parsePositiveNumber(sessionLifeTime?.value);
  if (!sessionVal && sessionVal !== 0) {
    markValidity(sessionLifeTime, sv('sessionLifetime', 'Enter a session lifetime in minutes (positive number).'));
    firstInvalid = firstInvalid || sessionLifeTime;
  }

  if (!disablePreview?.checked) {
    const previewVal = parsePositiveNumber(maxPreviewSizeBytes?.value);
    if (!previewVal) {
      markValidity(maxPreviewSizeBytes, sv('previewSize', 'Enter preview size (MB) greater than 0.'));
      firstInvalid = firstInvalid || maxPreviewSizeBytes;
    }
  }

  if (defaultHomePage && !["upload", "list", "paste"].includes(defaultHomePage.value)) {
    markValidity(defaultHomePage, sv('defaultHomePage', 'Choose upload, list, or paste.'));
    firstInvalid = firstInvalid || defaultHomePage;
  }

  if (appPasswordEnabled?.checked && appPassword && !appPassword.value.trim() && !storedAppPassword) {
    markValidity(appPassword, sv('appPasswordRequired', 'App password is required when protection is enabled.'));
    firstInvalid = firstInvalid || appPassword;
  } else if (appPassword) {
    markValidity(appPassword, "");
  }

  if (discordEnabled?.checked) {
    const urlVal = discordUrl?.value.trim();
    if (!urlVal || !(urlVal.startsWith("http://") || urlVal.startsWith("https://"))) {
      markValidity(discordUrl, sv('discordWebhook', 'Enter a valid Discord webhook URL.'));
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
      markValidity(emailFrom, sv('emailFrom', 'Enter a valid From email.'));
      firstInvalid = firstInvalid || emailFrom;
    }
    if (!emailToVal) {
      markValidity(emailTo, sv('emailRecipients', 'Enter at least one recipient.'));
      firstInvalid = firstInvalid || emailTo;
    }
    if (!hostVal) {
      markValidity(smtpHost, sv('smtpHost', 'SMTP host is required.'));
      firstInvalid = firstInvalid || smtpHost;
    }
    if (!portVal) {
      markValidity(smtpPort, sv('smtpPort', 'Enter a valid SMTP port.'));
      firstInvalid = firstInvalid || smtpPort;
    }
  }

  const anyChannel = Boolean(discordEnabled?.checked) || Boolean(emailEnabled?.checked);
  if (batchEnabled?.checked) {
    const minutesVal = parsePositiveNumber(batchMinutes?.value);
    if (!anyChannel) {
      markValidity(batchMinutes, sv('enableChannelBeforeBatch', 'Enable Discord or Email before batching.'));
      firstInvalid = firstInvalid || batchMinutes;
    } else if (!minutesVal) {
      markValidity(batchMinutes, sv('batchInterval', 'Enter a batch interval in minutes.'));
      firstInvalid = firstInvalid || batchMinutes;
    }
  }

  if (firstInvalid) firstInvalid.focus();
  return !firstInvalid;
}

/**
 * Validates the settings form and POSTs to /admin/api/save.
 * Sends FormData when a logo file is selected; URL-encoded params otherwise
 * (avoids a multipart boundary when no binary data is present).
 * @param {string|null} csrf - CSRF token to include in request headers
 * @throws {Error} if validation fails or the server returns a non-OK response
 */
async function saveSettings(csrf) {
  const form = document.querySelector(
    'form[method="post"][action="/admin/save"]'
  );
  if (!form) return;

  if (!validateSettingsForm()) {
    throw new Error("Validation failed");
  }

  const logoInput = document.getElementById("appLogo");
  const hasFile = logoInput?.files?.length > 0;
  let body;
  if (hasFile) {
    body = new FormData(form);
  } else {
    const params = new URLSearchParams();
    new FormData(form).forEach((value, key) => {
      if (!(value instanceof File)) params.append(key, value);
    });
    body = params;
  }
  const response = await fetch("/admin/api/save", {
    method: "POST",
    credentials: "same-origin",
    headers: buildCsrfHeaders(csrf),
    body,
  });
  if (!response.ok) {
    throw new Error((await response.text()) || "Save failed");
  }
}

/**
 * Saves the current settings and fires a notification test for the given
 * channel. Updates the status element with the server's plain-text response.
 * @param {'discord'|'email'} target  - Notification channel to test
 * @param {string}            buttonId - ID of the trigger button
 * @param {string}            statusId - ID of the status message element
 */
async function sendNotificationTest(target, buttonId, statusId) {
  const button = document.getElementById(buttonId);
  const status = document.getElementById(statusId);
  if (!button || !status) return;

  const testingText = button.dataset.testingText || "Testing…";
  const errorText = button.dataset.errorText || "Request failed. See logs.";

  status.textContent = testingText;
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
    status.textContent = errorText;
    status.className = "text-red-600 dark:text-red-400";
  } finally {
    button.disabled = false;
  }
}

document.addEventListener("DOMContentLoaded", function () {
  togglePasswordField();
  toggleDiscordField();
  toggleEmailFields();
  syncSmtpSecurityModes();
  updateBatchAvailability();
  syncUploadPasswordSetting();
  syncShareLinkSettings();
  togglePreviewSizeField();

  const form = document.querySelector(
    'form[method="post"][action="/admin/save"]'
  );
  if (form) {
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      e.stopPropagation();
      const saveBtns = document.querySelectorAll('[form="settingsForm"]');
      saveBtns.forEach(b => (b.disabled = true));
      try {
        await saveSettings(getCsrfToken());
        window.toast?.("Settings saved", "success");
      } catch (err) {
        if (err.message !== "Validation failed") {
          window.toast?.(err.message || "Failed to save settings", "error");
        }
      } finally {
        saveBtns.forEach(b => (b.disabled = false));
      }
    });
  }

  document.querySelectorAll('#cronPresets [data-cron]').forEach(btn => {
    btn.addEventListener('click', () => {
      const input = document.getElementById('fileDeletionCron');
      if (input) {
        input.value = btn.dataset.cron;
        input.dispatchEvent(new Event('input'));
        markValidity(input, '');
      }
    });
  });

  const cronInput = document.getElementById('fileDeletionCron');
  const cronFeedback = document.getElementById('cronFeedback');
  const cronLocale = window.i18n?.settings?.cron?.locale || 'en';

  function updateCronFeedback() {
    if (!cronInput || !cronFeedback) return;
    const val = cronInput.value.trim();

    document.querySelectorAll('#cronPresets [data-cron]').forEach(btn => {
      const match = btn.dataset.cron === val;
      btn.classList.toggle('btn-primary', match);
      btn.classList.toggle('btn-ghost', !match);
    });

    if (!val) {
      cronFeedback.textContent = '';
      cronInput.setCustomValidity('');
      return;
    }

    if (!isValidSpringCron(val)) {
      const msg = sv('cronInvalid', 'Invalid cron expression (6 fields required).');
      cronFeedback.textContent = msg;
      cronFeedback.style.color = 'var(--c-danger, #ef4444)';
      cronInput.setCustomValidity(msg);
      return;
    }

    cronInput.setCustomValidity('');
    let desc = '';
    if (typeof cronstrue !== 'undefined') {
      try {
        desc = cronstrue.toString(val, {locale: cronLocale, throwExceptionOnParseError: true});
      } catch (e) {
        desc = '';
      }
    }
    cronFeedback.textContent = desc;
    cronFeedback.style.color = 'var(--c-teal)';
  }

  cronInput?.addEventListener('input', updateCronFeedback);
  updateCronFeedback();

  document
    .getElementById("discordWebhookEnabled")
    ?.addEventListener("change", toggleDiscordField);
  document
    .getElementById("emailNotificationsEnabled")
    ?.addEventListener("change", toggleEmailFields);
  document
      .getElementById("smtpUseTls")
      ?.addEventListener("change", () => syncSmtpSecurityModes("tls"));
  document
      .getElementById("smtpUseSsl")
      ?.addEventListener("change", () => syncSmtpSecurityModes("ssl"));
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
    .getElementById("shareLinksDisabled")
    ?.addEventListener("change", syncShareLinkSettings);
  document
    .getElementById("simplifiedShareLinks")
    ?.addEventListener("change", syncShareLinkSettings);

  document
    .getElementById("clearLogoButton")
    ?.addEventListener("click", (event) => {
      event.preventDefault();
      resetLogoSelection();
    });

  document
    .getElementById("appLogo")
    ?.addEventListener("change", markLogoAsReplaced);

  const appPassword = document.getElementById("appPassword");
  if (appPassword) {
    appPassword.addEventListener("input", () => markValidity(appPassword, ""));
  }

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

