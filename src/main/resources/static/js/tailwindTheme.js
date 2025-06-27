// tailwindTheme.js
(function(){
    const html = document.documentElement;
    const stored = localStorage.getItem('theme');
    const initial = stored || 'light';
    html.classList.toggle('dark', initial === 'dark');

    document.addEventListener('DOMContentLoaded', () => {
        const btn = document.getElementById('themeToggle');
        if(!btn) return;
        btn.textContent = html.classList.contains('dark') ? '☀️' : '🌙';
        btn.addEventListener('click', () => {
            const newTheme = html.classList.contains('dark') ? 'light' : 'dark';
            html.classList.toggle('dark', newTheme === 'dark');
            localStorage.setItem('theme', newTheme);
            btn.textContent = newTheme === 'dark' ? '☀️' : '🌙';
        });
    });
})();
