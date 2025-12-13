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

document.addEventListener('DOMContentLoaded', function () {
    togglePasswordField();
    toggleDiscordField();
    toggleEmailFields();

    document.getElementById('discordWebhookEnabled')?.addEventListener('change', toggleDiscordField);
    document.getElementById('emailNotificationsEnabled')?.addEventListener('change', toggleEmailFields);
});