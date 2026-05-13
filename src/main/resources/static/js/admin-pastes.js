(function () {
    const wrapper = document.getElementById('pasteSectionWrapper');
    if (!wrapper) return;

    const DELETE_MSG = window.i18n?.pastes?.deleteConfirm
        || 'Are you sure you want to delete this paste? This action cannot be undone.';

    document.getElementById('pasteSearchForm')?.addEventListener('submit', function (e) {
        e.preventDefault();
        QD.loadDynamic('/admin/pastes?' + new URLSearchParams(new FormData(this)), 'pasteDynamicContent');
    });

    wrapper.addEventListener('click', e => {
        const btn = e.target.closest('a.pagination-btn:not(.pagination-btn-disabled)');
        if (!btn) return;
        e.preventDefault();
        window.scrollTo({top: 0, behavior: 'smooth'});
        QD.loadDynamic(btn.href, 'pasteDynamicContent');
    });

    wrapper.addEventListener('change', e => {
        const el = e.target;
        if (el.closest('[data-perpage-form]')) {
            QD.loadDynamic('/admin/pastes?' + new URLSearchParams(new FormData(el.closest('form'))), 'pasteDynamicContent');
        }
    });

    wrapper.addEventListener('submit', e => {
        const form = e.target.closest('form[data-delete-form]');
        if (!form) return;
        e.preventDefault();
        if (!confirm(DELETE_MSG)) return;
        deletePaste(form, form.closest('[data-uuid]'));
    });

    async function deletePaste(form, card) {
        if (card) {
            card.style.transition = 'opacity 250ms, transform 250ms';
            card.style.opacity = '0';
            card.style.transform = 'translateX(1.5rem)';
        }
        try {
            const res = await fetch(form.action, {method: 'POST', body: new FormData(form)});
            if (res.ok || res.redirected) setTimeout(() => card?.remove(), 260);
            else throw new Error(res.status);
        } catch {
            if (card) {
                card.style.opacity = '';
                card.style.transform = '';
            }
        }
    }

    document.addEventListener('keydown', e => {
        if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) {
            e.preventDefault();
            document.getElementById('pasteSearch')?.focus();
        }
    });
})();
