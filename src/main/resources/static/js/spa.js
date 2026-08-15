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
    const prefersReducedMotion = () =>
        window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    async function loadDynamic(url, containerId, opts = {}) {
        const container = document.getElementById(containerId);
        if (!container) return;

        // Height-stable busy state: the container keeps its size and gets a shimmer
        // bar, instead of the old blunt opacity dip that let the layout jump when the
        // replacement differed in height.
        container.style.minHeight = container.offsetHeight + 'px';
        container.classList.add('is-loading');
        container.setAttribute('aria-busy', 'true');

        try {
            const res = await fetch(url);
            if (!res.ok) throw new Error(res.status);
            const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
            const next = doc.getElementById(containerId);
            if (!next) return;

            // Update the browser tab title from the fetched document
            if (doc.title) {
                document.title = doc.title;
            }

            const commit = () => {
                container.replaceWith(next);
                next.style.minHeight = '';
                markRowsForEntrance(next);
                refreshCounters(next);
            };

            // Cross-fade the swap where the browser supports it, so filtering and
            // paging read as a transition rather than a blink.
            if (document.startViewTransition && !prefersReducedMotion()) {
                await document.startViewTransition(commit).finished.catch(() => {});
            } else {
                commit();
            }

            if (opts && opts.replace) {
                history.replaceState({}, '', url);
            } else {
                history.pushState({}, '', url);
            }
        } catch (err) {
            console.error('QD.loadDynamic failed', err);
            const c = document.getElementById(containerId);
            if (c) {
                c.classList.remove('is-loading');
                c.removeAttribute('aria-busy');
                c.style.minHeight = '';
            }
            // Surface the failure to the user instead of swallowing it silently
            if (typeof window.toast === 'function') {
                window.toast((window.QD_I18N || {}).navigationFailed
                    || 'Navigation failed. Please try again.', 'error');
            }
        }
    }

    /**
     * Stagger the first rows of a freshly swapped list in. Capped so a 100-row page
     * does not spend three seconds animating.
     * @param {Element} root - container holding the rows
     */
    function markRowsForEntrance(root) {
        if (prefersReducedMotion()) return;
        // Card lists use [data-uuid]/.file-card; the activity log is a real table.
        const rows = root.querySelectorAll('[data-uuid], .file-card, .data-table tbody tr, tbody tr');
        for (let i = 0; i < rows.length && i < 12; i++) {
            rows[i].classList.add('row-enter');
            rows[i].style.animationDelay = (i * 28) + 'ms';
        }
    }

    /**
     * Re-runs the count-up animation for stat values inside swapped content.
     * @param {Element} root - container to scan
     */
    function refreshCounters(root) {
        if (typeof window.QDCounters === 'function') window.QDCounters(root);
    }

    /**
     * Slides a card out then submits its delete form via fetch.
     * Reverts the animation if the request fails.
     *
     * @param {HTMLFormElement}  form - Form whose action is the delete endpoint
     * @param {Element|null}     card - Card element to animate and remove
     */
    async function deleteWithAnimation(form, card) {
        const submitter = form.querySelector('button[type="submit"], input[type="submit"]');
        if (card) {
            card.style.transition = 'opacity 250ms, transform 250ms';
            card.style.opacity = '0.55';
            card.style.pointerEvents = 'none';
        }
        if (submitter) submitter.disabled = true;

        try {
            const res = await fetch(form.action, {
                method: 'POST',
                body: new FormData(form),
                headers: {
                    'X-Requested-With': 'XMLHttpRequest',
                    'Accept': 'application/json, text/plain, */*',
                },
            });
            if (!res.ok) throw new Error(res.status);

            if (card) {
                card.style.opacity = '0';
                card.style.transform = 'translateX(1.5rem)';
                setTimeout(() => card.remove(), 260);
            }
        } catch {
            if (card) {
                card.style.opacity = '';
                card.style.transform = '';
                card.style.pointerEvents = '';
            }
            if (submitter) submitter.disabled = false;
            if (typeof window.toast === 'function') {
                const message = window.i18n?.common?.deleteFailed
                    || 'Delete failed. Please try again.';
                window.toast(message, 'error');
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

    // Handle browser Back/Forward so the DOM reflects the new URL.
    // Probes known container IDs used by each SPA section to find the live one.
    window.addEventListener('popstate', function () {
        const knownContainers = [
            'listDynamicContent',    // file list page
            'fileDynamicContent',    // admin files
            'pasteDynamicContent',   // admin pastes
            'activityContent',       // admin activity
            'shareLinksContent',     // admin share links
        ];
        const containerId = knownContainers.find((id) => document.getElementById(id));
        if (containerId) {
            loadDynamic(window.location.href, containerId, {replace: true});
        }
    });

    /**
     * Brief confirmation pulse on a control that just committed on change.
     * @param {Element|null} el - the .toggle (or slider) to flash
     */
    function flashSaved(el) {
        if (!el) return;
        el.classList.remove('is-saved');
        void el.offsetWidth;
        el.classList.add('is-saved');
        setTimeout(() => el.classList.remove('is-saved'), 800);
    }

    /* ── Row overflow menus ───────────────────────────────────────────────
       Delegated from the document so menus inside list sections keep working
       after loadDynamic() replaces the container. */
    function closeAllRowMenus(except) {
        document.querySelectorAll('.row-menu.is-open').forEach(m => {
            if (m !== except) {
                m.classList.remove('is-open');
                m.querySelector('[data-row-menu-trigger]')?.setAttribute('aria-expanded', 'false');
            }
        });
    }

    document.addEventListener('click', function (e) {
        const trigger = e.target.closest('[data-row-menu-trigger]');
        if (trigger) {
            e.preventDefault();
            e.stopPropagation();
            const menu = trigger.closest('.row-menu');
            const willOpen = !menu.classList.contains('is-open');
            closeAllRowMenus(menu);
            menu.classList.toggle('is-open', willOpen);
            trigger.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
            return;
        }
        if (!e.target.closest('.row-menu-panel')) closeAllRowMenus(null);
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeAllRowMenus(null);
    });

    /* ── List density ─────────────────────────────────────────────────────
       Remembered per list id so the choice survives navigation and swaps. */
    function applyDensity(listId, compact) {
        const list = document.getElementById(listId);
        if (list) list.classList.toggle('is-compact', compact);
        document.querySelectorAll('[data-density-toggle="' + listId + '"]').forEach(btn => {
            btn.setAttribute('aria-pressed', compact ? 'true' : 'false');
            btn.classList.toggle('btn-primary', compact);
            btn.classList.toggle('btn-ghost', !compact);
        });
    }

    function initDensity(listId) {
        let compact = false;
        try { compact = localStorage.getItem('qd-density-' + listId) === 'compact'; } catch (e) { /* private mode */ }
        applyDensity(listId, compact);
        document.addEventListener('click', function (e) {
            const btn = e.target.closest('[data-density-toggle="' + listId + '"]');
            if (!btn) return;
            e.preventDefault();
            const next = !(document.getElementById(listId)?.classList.contains('is-compact'));
            applyDensity(listId, next);
            try { localStorage.setItem('qd-density-' + listId, next ? 'compact' : 'comfortable'); } catch (e) { /* ignore */ }
        });
    }

    global.QD = global.QD || {};
    global.QD.initDensity = initDensity;
    global.QD.flashSaved = flashSaved;
    global.QD.loadDynamic = loadDynamic;
    global.QD.markRowsForEntrance = markRowsForEntrance;
    global.QD.deleteWithAnimation = deleteWithAnimation;
    global.QD.bindSearchShortcut = bindSearchShortcut;
})(window);
