(function () {
    var WRAPPER_ID = 'shareLinksSectionWrapper';
    var CONTENT_ID = 'shareLinksContent';

    var wrapper = document.getElementById(WRAPPER_ID);
    if (!wrapper) return;

    function state() {
        return new URLSearchParams(window.location.search);
    }

    function go(overrides, push) {
        var p = state();
        p.set('page', '0');
        var keys = Object.keys(overrides || {});
        for (var i = 0; i < keys.length; i++) {
            var k = keys[i];
            var v = overrides[k];
            if (v == null || v === '') p.delete(k);
            else p.set(k, String(v));
        }
        QD.loadDynamic('/admin/share-links?' + p, CONTENT_ID, {replace: !push});
    }

    wrapper.addEventListener('click', function (e) {
        var typeBtn = e.target.closest('[data-type-val]');
        if (typeBtn) {
            go({type: typeBtn.dataset.typeVal});
            return;
        }

        if (e.target.closest('#sortDirBtn')) {
            var btn = e.target.closest('#sortDirBtn');
            var cur = btn.getAttribute('data-sort-dir') || 'desc';
            go({sortDir: cur === 'asc' ? 'desc' : 'asc'});
            return;
        }

        var pBtn = e.target.closest('a.pagination-btn:not(.pagination-btn-disabled)');
        if (pBtn) {
            e.preventDefault();
            window.scrollTo({top: 0, behavior: 'smooth'});
            QD.loadDynamic(pBtn.href, CONTENT_ID, {replace: false});
        }
    });

    wrapper.addEventListener('change', function (e) {
        var el = e.target;
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
            var p = state();
            p.set('size', el.value);
            p.set('page', '0');
            QD.loadDynamic('/admin/share-links?' + p, CONTENT_ID, {replace: true});
        }
    });

    wrapper.addEventListener('submit', function (e) {
        var form = e.target.closest('#shareSearchForm');
        if (!form) return;
        e.preventDefault();
        var p = state();
        var q = (form.querySelector('#shareSearch') || {}).value || '';
        q = q.trim();
        if (q) p.set('query', q); else p.delete('query');
        p.set('page', '0');
        QD.loadDynamic('/admin/share-links?' + p, CONTENT_ID, {replace: false});
    });
})();
