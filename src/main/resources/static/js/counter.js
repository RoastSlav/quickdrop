/**
 * Animated number counters for stat cards.
 * Targets elements with data-counter-target containing a number (e.g. data-counter-target="1280").
 * Plain text content fallback if reduced motion is preferred or the value is non-numeric.
 */
(function () {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function easeOutCubic(t) {
        return 1 - Math.pow(1 - t, 3);
    }

    function animate(el, from, to, duration, formatter) {
        const start = performance.now();

        function tick(now) {
            const t = Math.min(1, (now - start) / duration);
            const value = from + (to - from) * easeOutCubic(t);
            el.textContent = formatter(value);
            if (t < 1) requestAnimationFrame(tick);
        }

        requestAnimationFrame(tick);
    }

    function init() {
        document.querySelectorAll('[data-counter-target]').forEach((el) => {
            const target = Number(el.getAttribute('data-counter-target'));
            if (!Number.isFinite(target)) return;
            const suffix = el.getAttribute('data-counter-suffix') || '';
            const decimals = Number(el.getAttribute('data-counter-decimals') || 0);
            const formatter = (n) => {
                const rounded = decimals > 0 ? n.toFixed(decimals) : Math.round(n).toString();
                return rounded.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + suffix;
            };
            if (reduceMotion || target < 10) {
                el.textContent = formatter(target);
                return;
            }
            el.textContent = formatter(0);
            animate(el, 0, target, 1100, formatter);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
