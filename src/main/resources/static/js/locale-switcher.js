document.addEventListener("DOMContentLoaded", () => {
    const updateLocale = (lang) => {
        if (!lang) return;
        const url = new URL(window.location.href);
        url.searchParams.set("lang", lang);
        window.location.assign(url.toString());
    };

    const selects = document.querySelectorAll(".locale-switch-select[data-locale-select]");
    selects.forEach((select) => {
        select.addEventListener("change", () => {
            updateLocale(select.value);
        });
    });

    const links = document.querySelectorAll(".locale-switch-link[data-lang]");
    if (!links.length) return;

    links.forEach((link) => {
        link.addEventListener("click", (event) => {
            event.preventDefault();
            updateLocale(link.dataset.lang);
        });
    });
});


