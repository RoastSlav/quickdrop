// Apply the saved theme as soon as this file is parsed to prevent a
// flash of the default light theme during navigation.
const storedTheme = localStorage.getItem('theme') || 'light';
document.documentElement.setAttribute('data-bs-theme', storedTheme);

document.addEventListener('DOMContentLoaded', () => {
    const disableToggle = document.documentElement.hasAttribute('data-disable-theme-toggle');
    if (!disableToggle) {
        const toggleButton = document.createElement('button');
        toggleButton.id = 'themeToggle';
        toggleButton.className = 'btn btn-sm btn-outline-light ms-2';
        toggleButton.textContent = storedTheme === 'dark' ? 'Light Mode' : 'Dark Mode';
        toggleButton.addEventListener('click', () => {
            const currentTheme = document.documentElement.getAttribute('data-bs-theme') || 'light';
            const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
            setTheme(newTheme);
            toggleButton.textContent = newTheme === 'dark' ? 'Light Mode' : 'Dark Mode';
        });

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
    }
});

function setTheme(theme) {
    document.documentElement.setAttribute('data-bs-theme', theme);
    localStorage.setItem('theme', theme);
}
