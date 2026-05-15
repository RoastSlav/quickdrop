(function () {
    document.getElementById('listSearchForm')?.addEventListener('submit', function (e) {
        e.preventDefault();
        QD.loadDynamic('/file/list?' + new URLSearchParams(new FormData(this)), 'listDynamicContent');
    });

    document.addEventListener('click', function (e) {
        const btn = e.target.closest('#listDynamicContent a.pagination-btn:not(.pagination-btn-disabled)');
        if (!btn) return;
        e.preventDefault();
        window.scrollTo({top: 0, behavior: 'smooth'});
        QD.loadDynamic(btn.href, 'listDynamicContent');
    });

    document.addEventListener('change', function (e) {
        const select = e.target.closest('#listDynamicContent [data-perpage-form] select');
        if (!select) return;
        window.scrollTo({top: 0, behavior: 'smooth'});
        QD.loadDynamic('/file/list?' + new URLSearchParams(new FormData(select.closest('form'))), 'listDynamicContent');
    });

    QD.bindSearchShortcut('searchInput');
})();
