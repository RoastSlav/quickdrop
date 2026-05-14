/**
 * QuickDrop toast notifications.
 * Usage: window.toast('Message', 'success' | 'error' | 'info' | 'warning', { duration })
 */
(function () {
    const ICONS = {
        success: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path stroke-linecap="round" stroke-linejoin="round" d="m4.5 12.75 6 6 9-13.5"/></svg>',
        error: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"/></svg>',
        info: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path stroke-linecap="round" stroke-linejoin="round" d="M11.25 11.25l.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z"/></svg>',
        warning: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z"/></svg>'
    };

    let container = null;

    function ensureContainer() {
        if (container && document.documentElement.contains(container)) return container;
        container = document.createElement('div');
        container.className = 'toast-container';
        container.setAttribute('role', 'status');
        container.setAttribute('aria-live', 'polite');
        document.documentElement.appendChild(container);
        return container;
    }

    function toast(message, kind = 'info', options = {}) {
        const {duration = 2400} = options;
        const el = document.createElement('div');
        el.className = `toast toast--${kind}`;
        el.innerHTML = `${ICONS[kind] || ICONS.info}<span class="toast-message"></span>`;
        el.querySelector('.toast-message').textContent = String(message ?? '');

        const c = ensureContainer();
        c.appendChild(el);

        const remove = () => {
            el.classList.add('toast--leaving');
            setTimeout(() => el.remove(), 220);
        };
        const timer = setTimeout(remove, duration);
        el.addEventListener('click', () => {
            clearTimeout(timer);
            remove();
        });
        return remove;
    }

    window.toast = toast;
})();
