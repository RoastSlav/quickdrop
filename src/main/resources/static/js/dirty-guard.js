/**
 * QuickDrop unsaved-changes guard.
 *
 * For forms that commit explicitly rather than autosaving — settings is the one that
 * matters: 131 fields behind a single Save button, where silently losing a change is
 * far worse than an extra click.
 *
 * Three things happen once a watched form goes dirty:
 *   1. a persistent bar appears saying changes are not saved yet, with Save and Discard;
 *   2. leaving the tab triggers the browser's own "leave site?" prompt;
 *   3. clicking an in-app link opens the themed confirm dialog instead of navigating.
 *
 * Usage:
 *   const guard = window.QDDirty.watch(formElement);
 *   ... after a successful save: guard.markClean();
 *
 * The bar's copy comes from window.QD_DIRTY_I18N, emitted by the page.
 */
(function () {
    'use strict';

    const T = () => window.QD_DIRTY_I18N || {};

    function buildBar(form, api) {
        const bar = document.createElement('div');
        bar.className = 'unsaved-bar';
        bar.setAttribute('role', 'status');
        bar.hidden = true;

        const text = document.createElement('span');
        text.className = 'unsaved-bar-text';
        text.textContent = T().unsaved || 'You have unsaved changes.';

        const actions = document.createElement('div');
        actions.className = 'unsaved-bar-actions';

        const discard = document.createElement('button');
        discard.type = 'button';
        discard.className = 'btn btn-ghost btn-sm';
        discard.textContent = T().discard || 'Discard';
        discard.addEventListener('click', async () => {
            const ok = await window.confirmAction({
                title: T().discardTitle || 'Discard changes?',
                body: T().discardBody || 'Your unsaved changes will be lost.',
                confirmLabel: T().discard || 'Discard',
                tone: 'danger'
            });
            if (ok) {
                api.markClean();
                window.location.reload();
            }
        });

        const save = document.createElement('button');
        save.type = 'submit';
        save.className = 'btn btn-primary btn-sm';
        save.textContent = T().save || 'Save';
        if (form.id) save.setAttribute('form', form.id);

        actions.appendChild(discard);
        actions.appendChild(save);
        bar.appendChild(text);
        bar.appendChild(actions);
        document.body.appendChild(bar);
        return bar;
    }

    function watch(form) {
        if (!form || form.dataset.dirtyWatched === 'true') return null;
        form.dataset.dirtyWatched = 'true';

        let dirty = false;
        let bar = null;

        const api = {
            isDirty: () => dirty,
            markDirty() {
                if (dirty) return;
                dirty = true;
                if (!bar) bar = buildBar(form, api);
                bar.hidden = false;
                // Force a reflow so the transition has a from-state, rather than waiting
                // on requestAnimationFrame — rAF does not fire in a backgrounded tab, which
                // would leave the bar present but permanently at opacity 0.
                void bar.offsetWidth;
                bar.classList.add('is-visible');
            },
            markClean() {
                dirty = false;
                if (bar) {
                    bar.classList.remove('is-visible');
                    setTimeout(() => { if (!dirty && bar) bar.hidden = true; }, 220);
                }
            }
        };

        // A change to any control inside the form counts. 'input' covers typing,
        // 'change' covers checkboxes, radios, selects and file pickers.
        form.addEventListener('input', api.markDirty);
        form.addEventListener('change', api.markDirty);

        window.addEventListener('beforeunload', (e) => {
            if (!dirty) return;
            e.preventDefault();
            // Modern browsers show their own wording; returnValue is still required.
            e.returnValue = '';
        });

        // In-app navigation: the browser prompt only covers unloads it initiates, and
        // it cannot be styled. For ordinary link clicks we can do better.
        document.addEventListener('click', function (e) {
            if (!dirty) return;
            const link = e.target.closest('a[href]');
            if (!link) return;
            const href = link.getAttribute('href');
            if (!href || href.startsWith('#') || link.target === '_blank'
                || link.hasAttribute('download') || href.startsWith('javascript:')) return;
            if (link.dataset.dirtyConfirmed === 'true') {
                delete link.dataset.dirtyConfirmed;
                return;
            }
            e.preventDefault();
            e.stopPropagation();
            window.confirmAction({
                title: T().leaveTitle || 'Leave without saving?',
                body: T().leaveBody || 'Your unsaved changes will be lost.',
                confirmLabel: T().leave || 'Leave',
                tone: 'danger'
            }).then(function (ok) {
                if (!ok) return;
                dirty = false;              // stop beforeunload double-prompting
                link.dataset.dirtyConfirmed = 'true';
                link.click();
            });
        }, true);

        return api;
    }

    window.QDDirty = {watch: watch};
})();
