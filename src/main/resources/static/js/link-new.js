(function () {
    function getI18nStr(key, fallback) {
        return (window.i18n && window.i18n.linkNew && window.i18n.linkNew[key]) || fallback;
    }

    function csrfToken() {
        return document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
    }

    function showError(message) {
        const el = document.getElementById('formError');
        if (!el) return;
        el.textContent = message;
        el.classList.remove('hidden');
    }

    function hideError() {
        document.getElementById('formError')?.classList.add('hidden');
    }

    function setSubmitting(submitting) {
        const button = document.getElementById('submitButton');
        if (button) button.disabled = submitting;
    }

    function showResult(data) {
        document.getElementById('linkForm').classList.add('hidden');
        const panel = document.getElementById('resultPanel');
        panel.classList.remove('hidden');
        panel.classList.add('flex');

        document.getElementById('resultLink').value = data.shortUrl;
        document.getElementById('resultQr').src = data.qrSvgUrl + '?size=150';
    }

    function resetForm() {
        document.getElementById('linkForm').reset();
        document.getElementById('linkForm').classList.remove('hidden');
        const panel = document.getElementById('resultPanel');
        panel.classList.add('hidden');
        panel.classList.remove('flex');
        hideError();
    }

    function submitLink(event) {
        event.preventDefault();
        hideError();

        const url = document.getElementById('targetUrl').value.trim();
        if (!url) return;

        const noExpiration = document.getElementById('noExpiration').checked;
        const daysValid = parseInt(document.getElementById('daysValid').value, 10);
        const unlimitedUses = document.getElementById('unlimitedUses').checked;
        const allowedUses = parseInt(document.getElementById('allowedUsesCount').value, 10);
        const customAlias = document.getElementById('customAlias').value.trim();

        const params = new URLSearchParams();
        params.set('url', url);
        if (!noExpiration && !isNaN(daysValid) && daysValid > 0) {
            const expiry = new Date();
            expiry.setDate(expiry.getDate() + daysValid);
            params.set('expirationDate', expiry.toISOString().slice(0, 10));
        }
        if (!unlimitedUses && !isNaN(allowedUses) && allowedUses > 0) {
            params.set('maxUses', String(allowedUses));
        }
        if (customAlias) {
            params.set('customAlias', customAlias);
        }

        setSubmitting(true);
        fetch('/api/link', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'X-XSRF-TOKEN': csrfToken()
            },
            body: params.toString(),
            credentials: 'same-origin'
        })
            .then(async (res) => {
                let data = null;
                try {
                    data = await res.json();
                } catch (_) {
                    // Non-JSON response; leave data null for the generic error below.
                }
                if (!res.ok) {
                    throw new Error(data?.message || getI18nStr('errorGeneric', "Couldn't create the link."));
                }
                showResult(data);
            })
            .catch((err) => {
                showError(err.message || getI18nStr('errorGeneric', "Couldn't create the link."));
            })
            .finally(() => {
                setSubmitting(false);
            });
    }

    /**
     * Pairs an "unlimited"/"no expiration" checkbox with its number input: checked disables
     * and clears the input, unchecked re-enables it and seeds `fallback` if it's empty.
     *
     * <p>Seeding the fallback is what stops the two controls from contradicting each other.
     * submitLink() treats a blank/zero value as "no limit", so without it, unchecking the box
     * and leaving the field empty would produce an unlimited link anyway — the form saying
     * one thing and the created link doing another. Mirrors fileView.js's
     * toggleExpirationLimit/toggleDownloadLimit, which seed 30 days / 1 download the same way.
     */
    function wireLimitToggle(checkboxId, inputId, fallback) {
        const checkbox = document.getElementById(checkboxId);
        const input = document.getElementById(inputId);
        if (!checkbox || !input) return;

        function sync() {
            input.disabled = checkbox.checked;
            if (checkbox.checked) {
                input.value = '';
            } else if (!input.value) {
                input.value = fallback;
            }
        }

        checkbox.addEventListener('change', sync);
        sync();
    }

    function copyResultLink() {
        const input = document.getElementById('resultLink');
        const button = document.getElementById('copyResultButton');
        if (!input || !input.value) return;
        navigator.clipboard.writeText(input.value).then(() => {
            const original = button.textContent;
            button.textContent = getI18nStr('copied', 'Copied');
            setTimeout(() => {
                button.textContent = original;
            }, 1500);
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.getElementById('linkForm')?.addEventListener('submit', submitLink);
        document.getElementById('copyResultButton')?.addEventListener('click', copyResultLink);
        document.getElementById('createAnotherButton')?.addEventListener('click', resetForm);
        wireLimitToggle('noExpiration', 'daysValid', '30');
        wireLimitToggle('unlimitedUses', 'allowedUsesCount', '1');
    });
})();
