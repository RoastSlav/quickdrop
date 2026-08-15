/**
 * QuickDrop confirmation dialogs.
 *
 * Replaces the four different things this app used to do when it needed a yes/no or an
 * "are you sure": native confirm(), native alert(), an Alpine button-swap, and a bespoke
 * in-page panel. One themed, translatable, focus-trapping dialog now covers all of them.
 *
 * Programmatic:
 *   const ok = await window.confirmAction({
 *       title: 'Delete this file?',
 *       body: 'This cannot be undone.',      // string, or array of strings for paragraphs
 *       emphasis: 'Everything since then is lost.',  // optional, rendered in the tone colour
 *       confirmLabel: 'Delete',
 *       tone: 'danger'                        // 'danger' | 'warning' | 'primary' (default)
 *   });
 *
 * Declarative — no page script needed. Put the attributes on a <form> (intercepts submit)
 * or on a button/link (intercepts click):
 *   <form data-confirm
 *         data-confirm-title="Delete this backup?"
 *         data-confirm-body="This cannot be undone."
 *         data-confirm-label="Delete"
 *         data-confirm-tone="danger">
 *
 * window.notify(message, kind) is the alert() replacement — it just forwards to the
 * existing toast so pages have one call to reach for instead of two.
 */
(function () {
    'use strict';

    const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';

    const ICONS = {
        danger: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0"/></svg>',
        warning: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z"/></svg>',
        primary: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M11.25 11.25l.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z"/></svg>'
    };

    const CONFIRM_BTN = {
        danger: 'btn btn-danger',
        warning: 'btn btn-primary',
        primary: 'btn btn-primary'
    };

    /** Labels the layout overrides with translated strings; these are the fallbacks. */
    const defaults = window.QD_CONFIRM_I18N || {};

    function labelFor(key, fallback) {
        return defaults[key] || fallback;
    }

    let openDialog = null;

    function toParagraphs(value) {
        if (value === null || value === undefined || value === '') return [];
        return Array.isArray(value) ? value.filter(Boolean) : [value];
    }

    function confirmAction(options) {
        const opts = options || {};
        const tone = CONFIRM_BTN[opts.tone] ? opts.tone : 'primary';

        // A second call while one is open resolves the first as cancelled rather than
        // stacking overlays.
        if (openDialog) openDialog.close(false);

        return new Promise(function (resolve) {
            const previouslyFocused = document.activeElement;

            const overlay = document.createElement('div');
            overlay.className = 'modal-overlay';

            const modal = document.createElement('div');
            modal.className = 'modal modal--' + tone;
            modal.setAttribute('role', 'alertdialog');
            modal.setAttribute('aria-modal', 'true');

            const titleId = 'qd-modal-title-' + Date.now();
            modal.setAttribute('aria-labelledby', titleId);

            const icon = document.createElement('div');
            icon.className = 'modal-icon';
            icon.innerHTML = ICONS[tone];
            modal.appendChild(icon);

            const title = document.createElement('h2');
            title.className = 'modal-title';
            title.id = titleId;
            title.textContent = opts.title || labelFor('title', 'Are you sure?');
            modal.appendChild(title);

            const body = document.createElement('div');
            body.className = 'modal-body';
            // textContent throughout: several call sites interpolate filenames and URLs.
            toParagraphs(opts.body).forEach(function (text) {
                const p = document.createElement('p');
                p.textContent = text;
                body.appendChild(p);
            });
            toParagraphs(opts.emphasis).forEach(function (text) {
                const em = document.createElement('em');
                em.textContent = text;
                body.appendChild(em);
            });
            if (body.childNodes.length) {
                modal.appendChild(body);
                modal.setAttribute('aria-describedby', titleId);
            }

            const actions = document.createElement('div');
            actions.className = 'modal-actions';

            const cancelBtn = document.createElement('button');
            cancelBtn.type = 'button';
            cancelBtn.className = 'btn btn-ghost';
            cancelBtn.textContent = opts.cancelLabel || labelFor('cancel', 'Cancel');

            const confirmBtn = document.createElement('button');
            confirmBtn.type = 'button';
            confirmBtn.className = CONFIRM_BTN[tone];
            confirmBtn.textContent = opts.confirmLabel || labelFor('confirm', 'Confirm');

            actions.appendChild(cancelBtn);
            actions.appendChild(confirmBtn);
            modal.appendChild(actions);
            overlay.appendChild(modal);

            let settled = false;

            function close(result) {
                if (settled) return;
                settled = true;
                openDialog = null;
                document.removeEventListener('keydown', onKeydown, true);
                overlay.remove();
                if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
                    previouslyFocused.focus();
                }
                resolve(result);
            }

            function onKeydown(e) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    close(false);
                    return;
                }
                if (e.key !== 'Tab') return;
                // Focus trap: keep Tab cycling inside the dialog.
                const items = Array.prototype.filter.call(
                    modal.querySelectorAll(FOCUSABLE),
                    function (el) { return el.offsetParent !== null; }
                );
                if (!items.length) return;
                const first = items[0];
                const last = items[items.length - 1];
                if (e.shiftKey && document.activeElement === first) {
                    e.preventDefault();
                    last.focus();
                } else if (!e.shiftKey && document.activeElement === last) {
                    e.preventDefault();
                    first.focus();
                }
            }

            cancelBtn.addEventListener('click', function () { close(false); });
            confirmBtn.addEventListener('click', function () { close(true); });
            overlay.addEventListener('mousedown', function (e) {
                if (e.target === overlay) close(false);
            });
            document.addEventListener('keydown', onKeydown, true);

            document.body.appendChild(overlay);
            openDialog = {close: close};

            // Focus Cancel, not Confirm — a stray Enter should not perform a destructive
            // action just because the dialog appeared.
            cancelBtn.focus();
        });
    }

    function optionsFrom(el) {
        const d = el.dataset;
        return {
            title: d.confirmTitle,
            body: d.confirmBody,
            emphasis: d.confirmEmphasis,
            confirmLabel: d.confirmLabel,
            cancelLabel: d.confirmCancel,
            tone: d.confirmTone
        };
    }

    // Declarative wiring. Delegated from the document so markup swapped in later
    // (htmx, the SPA-style partial loads in spa.js) keeps working without re-binding.
    document.addEventListener('submit', function (e) {
        const form = e.target.closest('form[data-confirm]');
        if (!form || form.dataset.confirmed === 'true') return;
        e.preventDefault();
        confirmAction(optionsFrom(form)).then(function (ok) {
            if (!ok) return;
            form.dataset.confirmed = 'true';
            if (typeof form.requestSubmit === 'function') {
                // Preserve which submit button was used, so name/value pairs still post.
                form.requestSubmit(e.submitter || undefined);
            } else {
                form.submit();
            }
        });
    }, true);

    document.addEventListener('click', function (e) {
        const trigger = e.target.closest('[data-confirm]:not(form)');
        if (!trigger || trigger.closest('form[data-confirm]')) return;
        if (trigger.dataset.confirmed === 'true') {
            delete trigger.dataset.confirmed;
            return;
        }
        e.preventDefault();
        e.stopPropagation();
        confirmAction(optionsFrom(trigger)).then(function (ok) {
            if (!ok) return;
            trigger.dataset.confirmed = 'true';
            trigger.click();
        });
    }, true);

    /** alert() replacement — one call site for "tell the user something went wrong". */
    function notify(message, kind) {
        if (typeof window.toast === 'function') {
            window.toast(message, kind || 'error');
        } else {
            // toast.js is in the base layout, so this is a genuine load failure.
            console.error('[quickdrop] toast unavailable:', message);
        }
    }

    window.confirmAction = confirmAction;
    window.notify = notify;
})();
