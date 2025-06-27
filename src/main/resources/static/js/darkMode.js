// Apply the saved theme as soon as this file is parsed to prevent a
// flash of the default light theme during navigation.
const storedTheme = localStorage.getItem('theme') || 'light';
document.documentElement.setAttribute('data-bs-theme', storedTheme);

document.addEventListener('DOMContentLoaded', () => {
    const disableToggle = document.documentElement.hasAttribute('data-disable-theme-toggle');
    if (!disableToggle) {
        let toggleButton = document.getElementById('themeToggle');
        if (!toggleButton) {
            toggleButton = document.createElement('button');
            toggleButton.id = 'themeToggle';
            styleButton(toggleButton);

            const navContainer = document.querySelector('.navbar .navbar-nav.ms-auto');
            if (navContainer) {
                const li = document.createElement('li');
                li.className = 'nav-item';
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

        applyButtonStyle(toggleButton);
        toggleButton.textContent = storedTheme === 'dark' ? '☀️' : '🌙';
        toggleButton.setAttribute('aria-label', storedTheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
        toggleButton.addEventListener('click', () => {
            const currentTheme = document.documentElement.getAttribute('data-bs-theme') || 'light';
            const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
            setTheme(newTheme);
            applyButtonStyle(toggleButton);
            toggleButton.textContent = newTheme === 'dark' ? '☀️' : '🌙';
            toggleButton.setAttribute('aria-label', newTheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
        });
    }

    updateTableHeaders(storedTheme);
});

function styleButton(button) {
    button.classList.add('btn', 'btn-sm', 'ms-2', 'd-flex', 'align-items-center', 'justify-content-center');
    button.style.width = '2rem';
    button.style.height = '2rem';
    button.style.padding = '0';
}

function setTheme(theme) {
    document.documentElement.setAttribute('data-bs-theme', theme);
    localStorage.setItem('theme', theme);
    updateTableHeaders(theme);
}

function applyButtonStyle(button) {
    button.classList.remove('btn-outline-light', 'btn-outline-dark');
    const theme = document.documentElement.getAttribute('data-bs-theme') || 'light';
    const navbarDark = button.closest('.navbar')?.classList.contains('navbar-dark');
    if (theme === 'dark' || navbarDark) {
        button.classList.add('btn-outline-light');
    } else {
        button.classList.add('btn-outline-dark');
    }
}

function updateTableHeaders(theme) {
    document.querySelectorAll('thead.table-dark, thead.table-light').forEach((thead) => {
        thead.classList.remove('table-dark', 'table-light');
        if (theme === 'dark') {
            thead.classList.add('table-dark');
        } else {
            thead.classList.add('table-light');
        }
    });
}
