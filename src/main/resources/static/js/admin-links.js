(function () {
    const WRAPPER_ID = 'linksSectionWrapper';
    const CONTENT_ID = 'linksContent';

    const wrapper = document.getElementById(WRAPPER_ID);
    if (!wrapper) return;

    /** @returns {URLSearchParams} current URL query params */
    function state() {
        return new URLSearchParams(window.location.search);
    }

    /** @returns {string} the active tab, from the URL (defaults to 'share') */
    function currentKind() {
        return state().get('kind') || 'share';
    }

    /**
     * Navigates to the links list with the given param overrides applied.
     * Null/empty values delete the corresponding param; others are set/replaced.
     * Always resets to page 0 and preserves the current tab.
     *
     * @param {Record<string, string|null>} overrides - Params to set or delete
     * @param {boolean} [push=false] - Use pushState; replaceState when false
     */
    function go(overrides, push = false) {
        const p = state();
        p.set('page', '0');
        p.set('kind', currentKind());
        for (const [k, v] of Object.entries(overrides || {})) {
            if (v == null || v === '') p.delete(k);
            else p.set(k, String(v));
        }
        QD.loadDynamic('/admin/links?' + p, CONTENT_ID, {replace: !push});
    }

    function revokeMessage() {
        return currentKind() === 'redirect'
            ? (window.i18n?.adminRedirectLinks?.revokeConfirm || 'Revoke this redirect link? This action cannot be undone.')
            : (window.i18n?.adminShareLinks?.revokeConfirm || 'Revoke this share link? This action cannot be undone.');
    }

    wrapper.addEventListener('click', function (e) {
        const typeBtn = e.target.closest('[data-type-val]');
        if (typeBtn) {
            go({type: typeBtn.dataset.typeVal});
            return;
        }

        const sortDirBtn = e.target.closest('#sortDirBtn, #redirectSortDirBtn');
        if (sortDirBtn) {
            const cur = sortDirBtn.getAttribute('data-sort-dir') || 'desc';
            go({sortDir: cur === 'asc' ? 'desc' : 'asc'});
            return;
        }

        const pBtn = e.target.closest('a.pagination-btn:not(.pagination-btn-disabled)');
        if (pBtn) {
            e.preventDefault();
            window.scrollTo({top: 0, behavior: 'smooth'});
            QD.loadDynamic(pBtn.href, CONTENT_ID, {replace: false});
        }
    });

    wrapper.addEventListener('change', function (e) {
        const el = e.target;
        if (el.id === 'sortBySelect' || el.id === 'redirectSortBySelect') {
            go({sortBy: el.value});
            return;
        }
        if (el.id === 'noExpiryCheck' || el.id === 'redirectNoExpiryCheck') {
            go({noExpiry: el.checked ? 'true' : null});
            return;
        }
        if (el.id === 'unlimitedCheck' || el.id === 'redirectUnlimitedCheck') {
            go({unlimited: el.checked ? 'true' : null});
            return;
        }
        if (el.id === 'sharePageSize' || el.id === 'redirectPageSize') {
            const p = state();
            p.set('size', el.value);
            p.set('page', '0');
            QD.loadDynamic('/admin/links?' + p, CONTENT_ID, {replace: true});
        }
    });

    wrapper.addEventListener('submit', function (e) {
        const searchForm = e.target.closest('#shareSearchForm, #redirectSearchForm');
        if (searchForm) {
            e.preventDefault();
            const p = state();
            const input = searchForm.querySelector('#shareSearch, #redirectSearch');
            const q = (input?.value || '').trim();
            if (q) p.set('query', q); else p.delete('query');
            p.set('page', '0');
            QD.loadDynamic('/admin/links?' + p, CONTENT_ID, {replace: false});
            return;
        }

        const revokeForm = e.target.closest('form[data-revoke-form]');
        if (revokeForm) {
            if (revokeForm.dataset.confirmed === 'true') return;
            e.preventDefault();
            window.confirmAction({
                body: revokeMessage(),
                confirmLabel: window.QD_CONFIRM_I18N?.revoke,
                tone: 'danger'
            }).then(ok => {
                if (!ok) return;
                revokeForm.dataset.confirmed = 'true';
                revokeForm.submit();
            });
        }
    });
})();
