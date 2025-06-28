// tailwindTheme.js
// Minimal theme toggler for Tailwind
(function () {
    const html = document.documentElement;

    function applyTheme(theme) {
        html.classList.toggle('dark', theme === 'dark');
    }

    const stored = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const initial = stored || (prefersDark ? 'dark' : 'light');

    applyTheme(initial);

    document.addEventListener('DOMContentLoaded', () => {
        const btn = document.getElementById('themeToggle');
        if (!btn) return;
        btn.textContent = html.classList.contains('dark') ? '☀️' : '🌙';
        btn.addEventListener('click', () => {
            const newTheme = html.classList.contains('dark') ? 'light' : 'dark';
            applyTheme(newTheme);
            localStorage.setItem('theme', newTheme);
            btn.textContent = newTheme === 'dark' ? '☀️' : '🌙';
        });
    });
})();
