// Apply the saved theme as soon as this file is parsed to prevent a
// flash of the default light theme during navigation.
const storedTheme = localStorage.getItem('theme') || 'light';
if (storedTheme === 'dark') {
    document.documentElement.classList.add('dark');
}

document.addEventListener('DOMContentLoaded', () => {
    const disableToggle = document.documentElement.hasAttribute('data-disable-theme-toggle');
    if (!disableToggle) {
        let toggleButton = document.getElementById('themeToggle');
        if (!toggleButton) {
            toggleButton = document.createElement('button');
            toggleButton.id = 'themeToggle';
            styleButton(toggleButton);

            const navContainer = document.querySelector('.navbar-nav');
            if (navContainer) {
                const li = document.createElement('li');
                li.appendChild(toggleButton);
                navContainer.appendChild(li);
            } else {
                toggleButton.style.position = 'fixed';
                toggleButton.style.top = '10px';
                toggleButton.style.right = '10px';
                toggleButton.style.zIndex = '1030';
                document.body.appendChild(toggleButton);
            }
        } else {
            styleButton(toggleButton);
        }

        toggleButton.textContent = storedTheme === 'dark' ? '☀️' : '🌙';
        toggleButton.setAttribute('aria-label', storedTheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
        toggleButton.addEventListener('click', () => {
            const currentTheme = document.documentElement.classList.contains('dark') ? 'dark' : 'light';
            const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
            setTheme(newTheme);
            toggleButton.textContent = newTheme === 'dark' ? '☀️' : '🌙';
            toggleButton.setAttribute('aria-label', newTheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
        });
    }
});

function styleButton(button) {
    button.classList.add('ml-2', 'px-2', 'py-1', 'border', 'rounded', 'text-sm');
}

function setTheme(theme) {
    if (theme === 'dark') {
        document.documentElement.classList.add('dark');
    } else {
        document.documentElement.classList.remove('dark');
    }
    localStorage.setItem('theme', theme);
}

