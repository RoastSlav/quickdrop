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

function syncUploadEnabled() {
    const uploadEnabledCb = document.getElementById("uploadEnabled");
    const uploadAdminOnlyRow = document.getElementById("uploadAdminOnlyRow");
    const uploadAdminOnlyCb = document.getElementById("uploadAdminOnly");

    const enabled = Boolean(uploadEnabledCb?.checked);

    if (uploadAdminOnlyRow) {
        uploadAdminOnlyRow.classList.toggle("opacity-60", !enabled);
        uploadAdminOnlyRow.classList.toggle("cursor-not-allowed", !enabled);
    }
    if (uploadAdminOnlyCb) {
        uploadAdminOnlyCb.disabled = !enabled;
        if (!enabled) uploadAdminOnlyCb.checked = false;
    }

    syncDefaultHomePageOptions();
    syncUploadPasswordSetting();
}

function syncDefaultHomePageOptions() {
    const uploadEnabled = Boolean(document.getElementById("uploadEnabled")?.checked);
    const uploadAdminOnly = Boolean(document.getElementById("uploadAdminOnly")?.checked);
    const listEnabled = Boolean(document.getElementById("fileListPageEnabled")?.checked);
    const pasteEnabled = Boolean(document.getElementById("pastebinEnabled")?.checked);

    // Upload is only a valid public home page if it's enabled AND not admin-only
    const uploadPubliclyAccessible = uploadEnabled && !uploadAdminOnly;

    const select = document.getElementById("defaultHomePage");
    if (!select) return;

    for (const opt of select.options) {
        if (opt.value === "upload") {
            opt.disabled = !uploadPubliclyAccessible;
            opt.title = !uploadEnabled ? "" : (uploadAdminOnly ? "Upload is restricted to admins — not reachable for public visitors" : "");
        } else if (opt.value === "list") {
            opt.disabled = !listEnabled;
        } else if (opt.value === "paste") {
            opt.disabled = !pasteEnabled;
        }
        // "none" is always enabled
    }

    // If selected option is now disabled, reset to first available
    const selectedOpt = select.options[select.selectedIndex];
    if (selectedOpt && selectedOpt.disabled) {
        for (const opt of select.options) {
            if (!opt.disabled) {
                select.value = opt.value;
                break;
            }
        }
    }
}

function syncUploadPasswordSetting() {
    const uploadPwEnabled = document.getElementById("uploadPasswordEnabled");
    const encryptionEnabled = document.getElementById("encryptionEnabled");
    const encryptionRow = document.getElementById("encryptionEnabledRow");
    const uploadEnabled = Boolean(document.getElementById("uploadEnabled")?.checked);

    if (!uploadPwEnabled || !encryptionEnabled) return;

    // If uploads are globally disabled, dim the whole upload options section
    const uploadOptionsSection = document.getElementById("uploadOptionsSection");
    if (uploadOptionsSection) {
        uploadOptionsSection.classList.toggle("opacity-60", !uploadEnabled);
    }
    if (!uploadEnabled) {
        uploadPwEnabled.disabled = true;
        encryptionEnabled.disabled = true;
        return;
    }

    uploadPwEnabled.disabled = false;
    const pwEnabled = uploadPwEnabled.checked;

    encryptionEnabled.disabled = !pwEnabled;
    encryptionEnabled.classList.toggle("cursor-not-allowed", !pwEnabled);
    encryptionEnabled.classList.toggle("opacity-60", !pwEnabled);
    if (encryptionRow) {
        encryptionRow.classList.toggle("opacity-60", !pwEnabled);
    }
    if (!pwEnabled) {
        encryptionEnabled.checked = false;
    }
}

function syncShareLinkSettings() {
    const shareLinksEnabled = document.getElementById("shareLinksEnabled");
    const simplifiedShareLinks = document.getElementById("simplifiedShareLinks");
    const simplifiedRow = document.getElementById("simplifiedShareLinksRow");

    const enabled = Boolean(shareLinksEnabled?.checked);

    if (simplifiedShareLinks) {
        simplifiedShareLinks.disabled = !enabled;
        simplifiedShareLinks.classList.toggle("cursor-not-allowed", !enabled);
        simplifiedShareLinks.classList.toggle("opacity-60", !enabled);
        if (!enabled) {
            simplifiedShareLinks.checked = false;
        }
    }

    if (simplifiedRow) {
        simplifiedRow.classList.toggle("opacity-60", !enabled);
        simplifiedRow.classList.toggle("cursor-not-allowed", !enabled);
    }
}

function syncRetentionSettings() {
    const enabled = Boolean(document.getElementById("activityRetentionEnabled")?.checked);
    ["activityRetentionDaysRow", "activityRetentionCronRow"].forEach((rowId) => {
        const row = document.getElementById(rowId);
        if (row) {
            row.classList.toggle("opacity-60", !enabled);
            row.classList.toggle("cursor-not-allowed", !enabled);
        }
    });
}

function syncShortenerSettings() {
    const shortenerEnabledCb = document.getElementById("shortenerEnabled");
    const customAliasEnabledCb = document.getElementById("shortenerCustomAliasEnabled");
    const domainRuleModeSelect = document.getElementById("shortenerDomainRuleMode");

    const enabled = Boolean(shortenerEnabledCb?.checked);
    const customAliasEnabled = Boolean(customAliasEnabledCb?.checked);

    // Rows that only matter when the shortener itself is on.
    [
        ["shortenerAdminOnlyRow", "shortenerAdminOnly"],
        ["shortenerCustomAliasEnabledRow", "shortenerCustomAliasEnabled"],
    ].forEach(([rowId, inputId]) => {
        const row = document.getElementById(rowId);
        const input = document.getElementById(inputId);
        if (row) {
            row.classList.toggle("opacity-60", !enabled);
            row.classList.toggle("cursor-not-allowed", !enabled);
        }
        if (input) {
            input.disabled = !enabled;
            if (!enabled) input.checked = false;
        }
    });

    // Custom-alias-admin-only additionally needs the shortener AND custom aliases enabled.
    const aliasAdminRow = document.getElementById("shortenerCustomAliasAdminOnlyRow");
    const aliasAdminInput = document.getElementById("shortenerCustomAliasAdminOnly");
    const aliasAdminActive = enabled && customAliasEnabled;
    if (aliasAdminRow) {
        aliasAdminRow.classList.toggle("opacity-60", !aliasAdminActive);
        aliasAdminRow.classList.toggle("cursor-not-allowed", !aliasAdminActive);
    }
    if (aliasAdminInput) {
        aliasAdminInput.disabled = !aliasAdminActive;
        if (!aliasAdminActive) aliasAdminInput.checked = false;
    }

    // Interstitial mode and domain rules (below) are deliberately NOT gated by
    // shortenerEnabled: both still apply when an existing redirect link is resolved
    // (LinkGuard.checkForRedirect runs the domain check, and the resolver still shows/skips
    // the interstitial) even after an admin disables new-link creation -- see the
    // shortenerEnabled gating note on ShortLinkViewController#newLinkForm.

    // Domain rules textarea only matters once a mode other than "OFF" is selected.
    const domainRulesRow = document.getElementById("shortenerDomainRulesRow");
    const domainRulesInput = document.getElementById("shortenerDomainRules");
    const domainRulesActive = domainRuleModeSelect?.value !== "OFF";
    if (domainRulesRow) {
        domainRulesRow.classList.toggle("opacity-60", !domainRulesActive);
        domainRulesRow.classList.toggle("cursor-not-allowed", !domainRulesActive);
    }
    if (domainRulesInput) domainRulesInput.disabled = !domainRulesActive;
}

function togglePreviewSizeField() {
    const previewEnabled = document.getElementById("previewEnabled");
    const sizeInput = document.getElementById("maxPreviewSizeBytes");
    if (!previewEnabled || !sizeInput) return;

    const enabled = previewEnabled.checked;
    sizeInput.disabled = !enabled;
    sizeInput.classList.toggle("opacity-60", !enabled);
    sizeInput.classList.toggle("cursor-not-allowed", !enabled);
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
    const headers = {"X-Requested-With": "XMLHttpRequest"};
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
    input.classList.toggle("is-invalid", Boolean(message));
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
    const previewEnabled = document.getElementById("previewEnabled");
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

    // Session lifetime allows 0 (never expires) — @Min(0) on the entity
    const sessionRaw = sessionLifeTime?.value?.trim();
    const sessionNum = Number(sessionRaw);
    if (sessionRaw === '' || sessionRaw == null || !Number.isFinite(sessionNum) || sessionNum < 0) {
        markValidity(sessionLifeTime, sv('sessionLifetime', 'Enter a session lifetime in minutes (0 or greater).'));
        firstInvalid = firstInvalid || sessionLifeTime;
    }

    if (previewEnabled?.checked) {
        const previewVal = parsePositiveNumber(maxPreviewSizeBytes?.value);
        if (!previewVal) {
            markValidity(maxPreviewSizeBytes, sv('previewSize', 'Enter preview size (MB) greater than 0.'));
            firstInvalid = firstInvalid || maxPreviewSizeBytes;
        }
    }

    if (defaultHomePage && !["upload", "list", "paste", "none"].includes(defaultHomePage.value)) {
        markValidity(defaultHomePage, sv('defaultHomePage', 'Choose a valid home page.'));
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

    if (firstInvalid) {
        // Navigate to the panel that contains the first invalid field
        const panel = firstInvalid.closest('.settings-panel');
        if (panel && typeof window.switchPanel === 'function') {
            window.switchPanel(panel.id.replace('panel-', ''));
        }
        // Wait one frame for the panel to become visible before focusing/scrolling
        requestAnimationFrame(() => {
            firstInvalid.focus();
            firstInvalid.scrollIntoView({behavior: 'smooth', block: 'center'});
        });
    }
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
    syncUploadEnabled();
    syncShareLinkSettings();
    syncShortenerSettings();
    syncRetentionSettings();
    togglePreviewSizeField();
    syncDefaultHomePageOptions();

    const form = document.querySelector(
        'form[method="post"][action="/admin/save"]'
    );
    if (form) {
        // Settings commits explicitly — never on change. The guard warns before the
        // page is left with pending edits and clears itself once a save succeeds.
        const dirtyGuard = window.QDDirty?.watch(form) || null;

        // Other settings-page scripts (reputation provider enable) navigate away on
        // their own. They need to be able to ask whether the form has pending edits,
        // commit them, and stand down the guard before reloading.
        window.QDSettings = {
            isDirty: () => !!dirtyGuard && dirtyGuard.isDirty(),
            save: async () => {
                await saveSettings(getCsrfToken());
                dirtyGuard?.markClean();
            },
            standDownGuard: () => dirtyGuard?.markClean()
        };

        form.addEventListener("submit", async (e) => {
            e.preventDefault();
            e.stopPropagation();
            const saveBtns = document.querySelectorAll('[form="settingsForm"]');
            saveBtns.forEach(b => (b.disabled = true));
            try {
                await saveSettings(getCsrfToken());
                dirtyGuard?.markClean();
                window.toast?.("Settings saved", "success");
            } catch (err) {
                window.toast?.(
                    err.message === "Validation failed"
                        ? sv('failed', 'Validation failed — check highlighted fields')
                        : (err.message || "Failed to save settings"),
                    "error"
                );
            } finally {
                saveBtns.forEach(b => (b.disabled = false));
            }
        });
    }

    // Wires one cron field's preset buttons + live cronstrue feedback. presetsId is scoped
    // per field (not a shared #cronPresets id) so the file-deletion and backup cron fields'
    // preset buttons don't cross-wire into each other's input.
    function wireCronField(presetsId, inputId, feedbackId) {
        const input = document.getElementById(inputId);
        const feedback = document.getElementById(feedbackId);
        const cronLocale = window.i18n?.settings?.cron?.locale || 'en';

        document.querySelectorAll('#' + presetsId + ' [data-cron]').forEach(btn => {
            btn.addEventListener('click', () => {
                if (input) {
                    input.value = btn.dataset.cron;
                    input.dispatchEvent(new Event('input'));
                    markValidity(input, '');
                }
            });
        });

        function updateFeedback() {
            if (!input || !feedback) return;
            const val = input.value.trim();

            document.querySelectorAll('#' + presetsId + ' [data-cron]').forEach(btn => {
                const match = btn.dataset.cron === val;
                btn.classList.toggle('btn-primary', match);
                btn.classList.toggle('btn-ghost', !match);
            });

            if (!val) {
                feedback.textContent = '';
                input.setCustomValidity('');
                return;
            }

            if (!isValidSpringCron(val)) {
                const msg = sv('cronInvalid', 'Invalid cron expression (6 fields required).');
                feedback.textContent = msg;
                feedback.style.color = 'var(--c-danger, #ef4444)';
                input.setCustomValidity(msg);
                return;
            }

            input.setCustomValidity('');
            let desc = '';
            if (typeof cronstrue !== 'undefined') {
                try {
                    desc = cronstrue.toString(val, {locale: cronLocale, throwExceptionOnParseError: true});
                } catch (e) {
                    desc = '';
                }
            }
            feedback.textContent = desc;
            feedback.style.color = 'var(--c-teal)';
        }

        input?.addEventListener('input', updateFeedback);
        updateFeedback();
    }

    wireCronField('cronPresets', 'fileDeletionCron', 'cronFeedback');
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
        .getElementById("uploadEnabled")
        ?.addEventListener("change", syncUploadEnabled);
    document
        .getElementById("uploadPasswordEnabled")
        ?.addEventListener("change", syncUploadPasswordSetting);
    document
        .getElementById("previewEnabled")
        ?.addEventListener("change", togglePreviewSizeField);
    document
        .getElementById("shareLinksEnabled")
        ?.addEventListener("change", syncShareLinkSettings);
    document
        .getElementById("simplifiedShareLinks")
        ?.addEventListener("change", syncShareLinkSettings);
    document
        .getElementById("fileListPageEnabled")
        ?.addEventListener("change", syncDefaultHomePageOptions);
    document
        .getElementById("pastebinEnabled")
        ?.addEventListener("change", syncDefaultHomePageOptions);
    document
        .getElementById("uploadAdminOnly")
        ?.addEventListener("change", syncDefaultHomePageOptions);

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

