(function () {
    const WRAPPER_ID = 'shareLinksSectionWrapper';
    const CONTENT_ID = 'shareLinksContent';

    const wrapper = document.getElementById(WRAPPER_ID);
    if (!wrapper) return;

    /** @returns {URLSearchParams} current URL query params */
    function state() {
        return new URLSearchParams(window.location.search);
    }

    /**
     * Navigates to the share-links list with the given param overrides applied.
     * Null/empty values delete the corresponding param; others are set/replaced.
     * Always resets to page 0.
     *
     * @param {Record<string, string|null>} overrides - Params to set or delete
     * @param {boolean} [push=false] - Use pushState; replaceState when false
     */
    function go(overrides, push = false) {
        const p = state();
        p.set('page', '0');
        for (const [k, v] of Object.entries(overrides || {})) {
            if (v == null || v === '') p.delete(k);
            else p.set(k, String(v));
        }
        QD.loadDynamic('/admin/share-links?' + p, CONTENT_ID, {replace: !push});
    }

    wrapper.addEventListener('click', function (e) {
        const typeBtn = e.target.closest('[data-type-val]');
        if (typeBtn) {
            go({type: typeBtn.dataset.typeVal});
            return;
        }

        const sortDirBtn = e.target.closest('#sortDirBtn');
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
        if (el.id === 'sortBySelect') {
            go({sortBy: el.value});
            return;
        }
        if (el.id === 'noExpiryCheck') {
            go({noExpiry: el.checked ? 'true' : null});
            return;
        }
        if (el.id === 'unlimitedCheck') {
            go({unlimited: el.checked ? 'true' : null});
            return;
        }
        if (el.id === 'sharePageSize') {
            const p = state();
            p.set('size', el.value);
            p.set('page', '0');
            QD.loadDynamic('/admin/share-links?' + p, CONTENT_ID, {replace: true});
        }
    });

    wrapper.addEventListener('submit', function (e) {
        const form = e.target.closest('#shareSearchForm');
        if (!form) return;
        e.preventDefault();
        const p = state();
        const q = (form.querySelector('#shareSearch')?.value || '').trim();
        if (q) p.set('query', q); else p.delete('query');
        p.set('page', '0');
        QD.loadDynamic('/admin/share-links?' + p, CONTENT_ID, {replace: false});
    });
})();
