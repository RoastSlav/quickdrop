function togglePasswordField() {
    const group = document.getElementById('passwordInputGroup');
    const enabled = document.getElementById('appPasswordEnabled')?.checked;
    if (!group) return;
    group.classList.toggle('hidden', !enabled);
}

function toggleDiscordField() {
    const enabled = document.getElementById('discordWebhookEnabled')?.checked;
    document.getElementById('discordWebhookUrlGroup')?.classList.toggle('hidden', !enabled);
}

function toggleEmailFields() {
    const enabled = document.getElementById('emailNotificationsEnabled')?.checked;
    document.getElementById('emailConfig')?.classList.toggle('hidden', !enabled);
}

function getCsrfToken() {
    const csrfInput = document.querySelector('input[name="_csrf"]');
    return csrfInput ? csrfInput.value : null;
}

function buildCsrfHeaders(csrf) {
    const headers = {'X-Requested-With': 'XMLHttpRequest'};
    if (csrf) {
        headers['X-XSRF-TOKEN'] = csrf;
        headers['X-CSRF-TOKEN'] = csrf;
    }
    return headers;
}

async function saveSettings(csrf) {
    const form = document.querySelector('form[method="post"][action="/admin/save"]');
    if (!form) return;

    const formData = new FormData(form);
    await fetch('/admin/save', {
        method: 'POST',
        credentials: 'same-origin',
        headers: buildCsrfHeaders(csrf),
        body: formData
    });
}

async function sendNotificationTest(target, buttonId, statusId) {
    const button = document.getElementById(buttonId);
    const status = document.getElementById(statusId);
    if (!button || !status) return;

    status.textContent = '';
    button.disabled = true;

    try {
        const csrf = getCsrfToken();

        // Always save settings before running a test so the latest values are used
        await saveSettings(csrf);

        const response = await fetch(`/admin/notification-test?target=${target}`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: buildCsrfHeaders(csrf)
        });
        const text = await response.text();
        status.textContent = text;
        status.className = response.ok ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400';
    } catch (e) {
        status.textContent = 'Request failed. See logs.';
        status.className = 'text-red-600 dark:text-red-400';
    } finally {
        button.disabled = false;
    }
}

document.addEventListener('DOMContentLoaded', function () {
    togglePasswordField();
    toggleDiscordField();
    toggleEmailFields();

    document.getElementById('discordWebhookEnabled')?.addEventListener('change', toggleDiscordField);
    document.getElementById('emailNotificationsEnabled')?.addEventListener('change', toggleEmailFields);

    document.getElementById('testDiscord')?.addEventListener('click', () => sendNotificationTest('discord', 'testDiscord', 'discordTestStatus'));
    document.getElementById('testEmail')?.addEventListener('click', () => sendNotificationTest('email', 'testEmail', 'emailTestStatus'));
});