// tailwindTheme.js
// Unified theme toggler for Tailwind
(function () {
  const html = document.documentElement;

  const applyTheme = (theme) => {
    html.classList.toggle("dark", theme === "dark");
    localStorage.setItem("theme", theme);
  };

  const updateToggleButtons = (theme) => {
    document.querySelectorAll(".theme-toggle, #themeToggle").forEach((btn) => {
      btn.textContent = theme === "dark" ? "☀️" : "🌙";
      btn.setAttribute(
        "aria-label",
        theme === "dark" ? "Switch to light theme" : "Switch to dark theme"
      );
    });
  };

  const stored = localStorage.getItem("theme");
  const prefersDark =
    window.matchMedia &&
    window.matchMedia("(prefers-color-scheme: dark)").matches;
  const initial = stored || (prefersDark ? "dark" : "light");

  applyTheme(initial);

  document.addEventListener("DOMContentLoaded", () => {
    updateToggleButtons(initial);

    document.querySelectorAll(".theme-toggle, #themeToggle").forEach((btn) => {
      btn.addEventListener("click", () => {
        const nextTheme = html.classList.contains("dark") ? "light" : "dark";
        applyTheme(nextTheme);
        updateToggleButtons(nextTheme);
      });
    });

    const navToggle = document.getElementById("navToggle");
    const navMenu = document.getElementById("navMenu");
    if (navToggle && navMenu) {
      const closeMenu = () => {
        navMenu.classList.add("hidden");
        navToggle.setAttribute("aria-expanded", "false");
      };

      navToggle.addEventListener("click", () => {
        const isHidden = navMenu.classList.contains("hidden");
        navMenu.classList.toggle("hidden");
        navToggle.setAttribute("aria-expanded", isHidden ? "true" : "false");
      });

      document.addEventListener("click", (e) => {
        const isMenuClick =
          navMenu.contains(e.target) || navToggle.contains(e.target);
        if (!isMenuClick && !navMenu.classList.contains("hidden")) {
          closeMenu();
        }
      });

      navMenu.querySelectorAll("a, button").forEach((el) => {
        el.addEventListener("click", () => closeMenu());
      });
    }
  });
})();
