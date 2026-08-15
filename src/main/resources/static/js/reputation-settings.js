/**
 * Greys out the reputation-provider rows when the master switch is off, and drives the
 * per-provider licence-acceptance modals. Mirrors settings.js's syncShortenerSettings()
 * pattern (inline onchange + a DOMContentLoaded call to set the initial state), kept in its
 * own file since it also owns the modal fetch flow that settings.js has no equivalent of.
 */
function syncReputationSettings() {
    const masterEnabledCb = document.getElementById("reputationCheckEnabled");
    const enabled = Boolean(masterEnabledCb?.checked);

    ["reputationPhishingArmyRow", "reputationUrlhausRow", "reputationSafeBrowsingRow",
        "reputationFailClosedRow", "reputationFeedCronRow"].forEach((rowId) => {
        const row = document.getElementById(rowId);
        if (row) {
            row.classList.toggle("opacity-60", !enabled);
            row.classList.toggle("cursor-not-allowed", !enabled);
        }
    });
}

document.addEventListener("DOMContentLoaded", function () {
    syncReputationSettings();

    // The settings layout has ancestors with CSS transforms (the animate-in entrance
    // animations), which makes `position: fixed` on a nested element behave like
    // `position: absolute` relative to that ancestor instead of the viewport. Moving each
    // modal to be a direct child of <body> sidesteps that CSS containing-block gotcha
    // entirely rather than fighting it with more CSS.
    document.querySelectorAll(".modal-overlay").forEach((modal) => {
        document.body.appendChild(modal);
    });

    // This app pairs a cookie-based CSRF repository with XorCsrfTokenRequestAttributeHandler
    // (BREACH-attack mitigation) -- the raw XSRF-TOKEN cookie value is NOT a valid header
    // value under that handler, only the server-rendered (masked) _csrf.token is. Read it
    // from the same meta tag link-new.js uses, rather than the cookie directly.
    function csrfToken() {
        return document.querySelector('meta[name="_csrf"]')?.getAttribute("content") || "";
    }

    document.querySelectorAll("[data-open-reputation-modal]").forEach((button) => {
        button.addEventListener("click", () => {
            const modal = document.getElementById("reputationModal-" + button.dataset.openReputationModal);
            if (!modal) return;
            const checkbox = modal.querySelector("[data-reputation-accept-checkbox]");
            const confirmButton = modal.querySelector("[data-confirm-reputation-modal]");
            if (checkbox) checkbox.checked = false;
            if (confirmButton) confirmButton.disabled = true;
            modal.classList.remove("hidden");
        });
    });

    function closeModal(modal) {
        modal?.classList.add("hidden");
    }

    document.querySelectorAll("[data-close-reputation-modal]").forEach((button) => {
        button.addEventListener("click", () => closeModal(button.closest(".modal-overlay")));
    });

    // The two dismissals every modal is expected to support besides its Cancel button.
    // Both are deliberately "cancel" semantics only -- nothing here can enable a provider,
    // which stays exclusively behind the explicit accept-then-confirm path.
    document.querySelectorAll(".modal-overlay").forEach((modal) => {
        modal.addEventListener("click", (e) => {
            // Backdrop only: a click that lands inside the dialog card must not dismiss it.
            if (e.target === modal) closeModal(modal);
        });
    });

    document.addEventListener("keydown", (e) => {
        if (e.key !== "Escape") return;
        document.querySelectorAll(".modal-overlay:not(.hidden)").forEach(closeModal);
    });

    document.querySelectorAll("[data-reputation-accept-checkbox]").forEach((checkbox) => {
        checkbox.addEventListener("change", () => {
            const confirmButton = checkbox.closest(".modal")?.querySelector("[data-confirm-reputation-modal]");
            if (confirmButton) confirmButton.disabled = !checkbox.checked;
        });
    });

    document.querySelectorAll("[data-confirm-reputation-modal]").forEach((button) => {
        button.addEventListener("click", async () => {
            const providerId = button.dataset.confirmReputationModal;

            // Accepting terms POSTs and then reloads, which would throw away any pending
            // edits in the settings form -- including the "Enable reputation checking"
            // master switch someone almost certainly just ticked. Commit them first.
            if (window.QDSettings?.isDirty()) {
                const t = window.QD_REPUTATION_I18N || {};
                const ok = await window.confirmAction({
                    title: t.saveFirstTitle || "Save your settings first?",
                    body: t.saveFirstBody
                        || "Enabling this provider reloads the page. Your unsaved settings changes will be saved first.",
                    confirmLabel: t.saveFirstConfirm || "Save and enable",
                    tone: "warning"
                });
                if (!ok) return;
                try {
                    await window.QDSettings.save();
                } catch (e) {
                    if (typeof window.toast === "function") {
                        window.toast(t.saveFailed || "Could not save settings. Nothing was changed.", "error");
                    }
                    return;
                }
            }
            // Stand the unsaved-changes guard down so the reload does not raise the
            // browser's own "leave site?" prompt on a navigation we initiated.
            window.QDSettings?.standDownGuard();

            button.disabled = true;
            fetch("/admin/settings/accept-reputation-terms", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded",
                    "X-XSRF-TOKEN": csrfToken()
                },
                body: "provider=" + encodeURIComponent(providerId)
            })
                .then((res) => {
                    if (!res.ok) throw new Error("request failed");
                    // Reload so the row re-renders in its "accepted" state -- the checkbox
                    // becomes interactive and the acceptance date shows up.
                    window.location.reload();
                })
                .catch(() => {
                    button.disabled = false;
                    if (typeof window.toast === "function") {
                        window.toast((window.QD_REPUTATION_I18N || {}).enableFailed
                            || "Could not enable this provider. Please try again.", "error");
                    }
                });
        });
    });
});
