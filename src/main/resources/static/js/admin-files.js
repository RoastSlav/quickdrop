(function () {
    const wrapper = document.getElementById('fileSectionWrapper');
    if (!wrapper) return;

    const DELETE_MSG = window.i18n?.dashboard?.deleteConfirm
        || 'Are you sure you want to delete this file? This action cannot be undone.';

    document.getElementById('adminSearchForm')?.addEventListener('submit', function (e) {
        e.preventDefault();
        QD.loadDynamic('/admin/files?' + new URLSearchParams(new FormData(this)), 'fileDynamicContent');
    });

    wrapper.addEventListener('click', e => {
        const btn = e.target.closest('a.pagination-btn:not(.pagination-btn-disabled)');
        if (!btn) return;
        e.preventDefault();
        window.scrollTo({top: 0, behavior: 'smooth'});
        QD.loadDynamic(btn.href, 'fileDynamicContent');
    });

    wrapper.addEventListener('change', e => {
        const el = e.target;
        if (el.closest('[data-perpage-form]')) {
            QD.loadDynamic('/admin/files?' + new URLSearchParams(new FormData(el.closest('form'))), 'fileDynamicContent');
            return;
        }
        if (el.type === 'checkbox' && (el.name === 'keepIndefinitely' || el.name === 'hidden')) {
            handleToggle(el);
        }
    });

    wrapper.addEventListener('submit', e => {
        const form = e.target.closest('form[data-delete-form]');
        if (!form) return;
        e.preventDefault();
        if (!confirm(DELETE_MSG)) return;
        QD.deleteWithAnimation(form, form.closest('[data-uuid]'));
    });

    function refreshCardStyle(card) {
        const keep = card.dataset.keep === 'true';
        const hidden = card.dataset.isHidden === 'true';
        const color = (keep && hidden) ? 'var(--c-rose)'
            : keep ? 'var(--c-teal)'
                : hidden ? 'var(--c-rose)'
                    : 'var(--c-primary)';
        card.style.borderLeft = `4px solid ${color}`;
        const icon = card.querySelector('[data-file-icon]');
        if (!icon) return;
        if (keep && hidden) {
            icon.style.background = 'var(--c-rose-muted)';
            icon.style.color = 'var(--c-rose)';
        } else if (keep) {
            icon.style.background = 'var(--c-teal-muted)';
            icon.style.color = 'var(--c-teal)';
        } else if (hidden) {
            icon.style.background = 'var(--c-rose-muted)';
            icon.style.color = 'var(--c-rose)';
        } else {
            icon.style.background = 'var(--c-primary-muted)';
            icon.style.color = 'var(--c-primary)';
        }
    }

    async function handleToggle(checkbox) {
        const form = checkbox.form;
        const hiddenField = form.querySelector(`input[name="${checkbox.name}"][type="hidden"]`);
        if (hiddenField) hiddenField.value = checkbox.checked;
        const card = checkbox.closest('[data-uuid]');
        if (card) {
            if (checkbox.name === 'keepIndefinitely') card.dataset.keep = checkbox.checked;
            else card.dataset.isHidden = checkbox.checked;
            refreshCardStyle(card);
        }
        try {
            const res = await fetch(form.action, {method: 'POST', body: new FormData(form)});
            if (!res.ok && !res.redirected) throw new Error(res.status);
        } catch {
            checkbox.checked = !checkbox.checked;
            if (hiddenField) hiddenField.value = checkbox.checked;
            if (card) {
                if (checkbox.name === 'keepIndefinitely') card.dataset.keep = checkbox.checked;
                else card.dataset.isHidden = checkbox.checked;
                refreshCardStyle(card);
            }
        }
    }

    QD.bindSearchShortcut('adminSearch');
})();
