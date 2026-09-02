// Runtime controls for the pre-paint theme bootstrap in fragments/header.html.
(function () {
    const storageKey = "theme";
    const validThemes = ["light", "dark", "system"];
    const html = document.documentElement;
    const media = window.matchMedia
        ? window.matchMedia("(prefers-color-scheme: dark)")
        : null;

    const normalizePreference = (value) =>
        validThemes.includes(value) ? value : "system";

    const getStoredPreference = () => {
        try {
            return normalizePreference(localStorage.getItem(storageKey));
        } catch (e) {
            return normalizePreference(html.dataset.themePreference);
        }
    };

    const getSystemTheme = () =>
        media && media.matches ? "dark" : "light";

    const resolveTheme = (preference) =>
        preference === "system" ? getSystemTheme() : preference;

    const emitThemeChange = (preference, resolved) => {
        window.dispatchEvent(new CustomEvent("quickdrop:theme-change", {
            detail: {preference, resolved}
        }));
    };

    const updateThemeIcons = (resolved) => {
        document.querySelectorAll("#themeIconSun").forEach((icon) => {
            icon.classList.toggle("hidden", resolved !== "dark");
        });
        document.querySelectorAll("#themeIconMoon").forEach((icon) => {
            icon.classList.toggle("hidden", resolved === "dark");
        });
    };

    const nextPreference = (preference, resolved) => {
        if (preference === "system") {
            return resolved === "dark" ? "light" : "dark";
        }
        return preference === "dark" ? "light" : "system";
    };

    const updateToggleButtons = (preference, resolved) => {
        const next = nextPreference(preference, resolved);
        const currentLabel = preference === "system"
            ? `system (${resolved})`
            : preference;
        const nextLabel = next === "system" ? "system" : next;

        document.querySelectorAll(".theme-toggle, #themeToggle").forEach((btn) => {
            btn.dataset.themePreference = preference;
            btn.dataset.theme = resolved;
            btn.setAttribute("aria-label", `Theme: ${currentLabel}. Switch to ${nextLabel} theme`);
            btn.setAttribute("title", `Theme: ${currentLabel}`);

            if (!btn.querySelector("svg")) {
                btn.textContent = preference === "system"
                    ? `System (${resolved})`
                    : preference.charAt(0).toUpperCase() + preference.slice(1);
            }
        });

        updateThemeIcons(resolved);
    };

    const applyPreference = (preference, options = {}) => {
        const normalized = normalizePreference(preference);
        const resolved = resolveTheme(normalized);
        html.classList.toggle("dark", resolved === "dark");
        html.dataset.themePreference = normalized;
        html.dataset.theme = resolved;
        html.style.colorScheme = resolved;

        if (options.persist !== false) {
            try {
                localStorage.setItem(storageKey, normalized);
            } catch (e) {
                // Storage can be unavailable in private or locked-down contexts.
            }
        }

        updateToggleButtons(normalized, resolved);
        emitThemeChange(normalized, resolved);
    };

    const applyCurrentPreference = (options = {}) => {
        applyPreference(getStoredPreference(), options);
    };

    window.quickdropTheme = {
        apply: applyPreference,
        getPreference: getStoredPreference,
        getResolvedTheme: () => resolveTheme(getStoredPreference())
    };

    document.addEventListener("DOMContentLoaded", () => {
        applyCurrentPreference({persist: false});

        document.querySelectorAll(".theme-toggle, #themeToggle").forEach((btn) => {
            btn.addEventListener("click", () => {
                const preference = getStoredPreference();
                const resolved = resolveTheme(preference);
                applyPreference(nextPreference(preference, resolved));
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

    if (media) {
        const onSystemThemeChange = () => {
            if (getStoredPreference() === "system") {
                applyPreference("system", {persist: false});
            }
        };

        if (typeof media.addEventListener === "function") {
            media.addEventListener("change", onSystemThemeChange);
        } else if (typeof media.addListener === "function") {
            media.addListener(onSystemThemeChange);
        }
    }
})();
