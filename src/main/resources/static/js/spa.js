/**
 * Shared SPA utilities — exposes window.QD
 */
(function (global) {
    /**
     * Replaces a DOM section with fresh server-rendered HTML without a full
     * page reload. Fades the container during fetch, then updates the browser
     * URL via pushState (or replaceState when opts.replace is true).
     *
     * @param {string}  url         URL to fetch
     * @param {string}  containerId ID of the element to swap
     * @param {{ replace?: boolean }} [opts]
     */
    async function loadDynamic(url, containerId, opts = {}) {
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
                if (opts && opts.replace) {
                    history.replaceState({}, '', url);
                } else {
                    history.pushState({}, '', url);
                }
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

    /**
     * Slides a card out then submits its delete form via fetch.
     * Reverts the animation if the request fails.
     *
     * @param {HTMLFormElement}  form - Form whose action is the delete endpoint
     * @param {Element|null}     card - Card element to animate and remove
     */
    async function deleteWithAnimation(form, card) {
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

    /**
     * Binds the '/' key to focus a search input, ignoring the shortcut when
     * the user is already typing in a text field.
     *
     * @param {string} inputId - ID of the search input to focus
     */
    function bindSearchShortcut(inputId) {
        document.addEventListener('keydown', (e) => {
            if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) {
                e.preventDefault();
                document.getElementById(inputId)?.focus();
            }
        });
    }

    global.QD = global.QD || {};
    global.QD.loadDynamic = loadDynamic;
    global.QD.deleteWithAnimation = deleteWithAnimation;
    global.QD.bindSearchShortcut = bindSearchShortcut;
})(window);
