/**
 * Shared SPA utilities — exposes window.QD.loadDynamic
 */
(function (global) {
    async function loadDynamic(url, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;
        container.style.transition = 'opacity 150ms';
        container.style.opacity = '0.4';
        container.style.pointerEvents = 'none';
        try {
            const res = await fetch(url);
            if (!res.ok) throw new Error(res.status);
            const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
            const next = doc.getElementById(containerId);
            if (next) {
                container.replaceWith(next);
                history.pushState({}, '', url);
            }
        } catch (err) {
            console.error('QD.loadDynamic failed', err);
            const c = document.getElementById(containerId);
            if (c) {
                c.style.opacity = '';
                c.style.pointerEvents = '';
            }
        }
    }

    global.QD = global.QD || {};
    global.QD.loadDynamic = loadDynamic;
})(window);
