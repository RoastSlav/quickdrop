(function () {
    const WRAPPER_ID = 'activitySectionWrapper';
    const CONTENT_ID = 'activityContent';

    const wrapper = document.getElementById(WRAPPER_ID);
    if (!wrapper) return;

    /** Event Type option each source button selects; keys are the data-sourcetype values. */
    const SOURCE_CATEGORIES = {
        file: 'FILE',
        paste: 'PASTE',
        link: 'SHORTLINK',
        admin: 'ADMIN',
        system: 'SYSTEM'
    };

    /** @param {Date} d @returns {string} YYYY-MM-DD */
    function localDateStr(d) {
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    }

    /**
     * Builds the activity filter URL from the current form values.
     * @param {string|number|null} [sizeOverride] - Page size to use instead of the select value
     * @returns {string} relative URL with query string
     */
    function buildUrl(sizeOverride) {
        const p = new URLSearchParams();

        const sd = wrapper.querySelector('#startDatePicker')?.value || '';
        const st = wrapper.querySelector('#startTimePicker')?.value || '00:00';
        if (sd) p.set('startDate', `${sd}T${st}`);

        const ed = wrapper.querySelector('#endDatePicker')?.value || '';
        const et = wrapper.querySelector('#endTimePicker')?.value || '23:59';
        if (ed) p.set('endDate', `${ed}T${et}`);

        const evtType = wrapper.querySelector('#eventType')?.value || '';
        if (evtType) p.set('eventType', evtType);

        const ip = (wrapper.querySelector('#ipFilter')?.value || '').trim();
        if (ip) p.set('ip', ip);

        const ua = (wrapper.querySelector('#uaFilter')?.value || '').trim();
        if (ua) p.set('ua', ua);

        const sourceTypeInput = document.getElementById('sourceTypeInput');
        if (sourceTypeInput && sourceTypeInput.value) {
            p.set('sourceType', sourceTypeInput.value);
        }

        const size = sizeOverride != null
            ? sizeOverride
            : (wrapper.querySelector('#activityPageSize')?.value
                || new URLSearchParams(window.location.search).get('size')
                || '10');
        p.set('size', size);
        p.set('page', '0');
        return '/admin/activity?' + p;
    }

    wrapper.addEventListener('submit', function (e) {
        if (!e.target.closest('#activityFilterForm')) return;
        e.preventDefault();
        QD.loadDynamic(buildUrl(), CONTENT_ID, {replace: true});
    });

    wrapper.addEventListener('click', function (e) {
        const presetBtn = e.target.closest('[data-preset]');
        if (presetBtn) {
            const now = new Date();
            let startD = '', startT = '', endD = '', endT = '';
            const preset = presetBtn.dataset.preset;

            if (preset === 'today') {
                startD = localDateStr(now);
                startT = '00:00';
                endD = localDateStr(now);
                endT = '23:59';
            } else if (preset === '7d') {
                const ago = new Date(now);
                ago.setDate(ago.getDate() - 7);
                startD = localDateStr(ago);
                startT = '00:00';
                endD = localDateStr(now);
                endT = '23:59';
            } else if (preset === '30d') {
                const ago = new Date(now);
                ago.setDate(ago.getDate() - 30);
                startD = localDateStr(ago);
                startT = '00:00';
                endD = localDateStr(now);
                endT = '23:59';
            }
            // 'all': leave all fields blank

            const sp = wrapper.querySelector('#startDatePicker');
            const st2 = wrapper.querySelector('#startTimePicker');
            const ep = wrapper.querySelector('#endDatePicker');
            const et2 = wrapper.querySelector('#endTimePicker');
            if (sp) sp.value = startD;
            if (st2) st2.value = startT;
            if (ep) ep.value = endD;
            if (et2) et2.value = endT;
            const form = document.getElementById('activityFilterForm');
            if (form) form.requestSubmit();
            return;
        }

        const srcBtn = e.target.closest('[data-sourcetype]');
        if (srcBtn) {
            const source = srcBtn.getAttribute('data-sourcetype');
            const input = wrapper.querySelector('#sourceTypeInput');
            if (input) input.value = source;
            // Fill in the matching category the way the range presets fill in the dates. A type
            // left over from the previous source usually contradicts the new one (source System
            // + event Viewed matches nothing), so it can't just be kept.
            const evtType = wrapper.querySelector('#eventType');
            if (evtType) {
                const category = SOURCE_CATEGORIES[source] || '';
                evtType.value = [...evtType.options].some(o => o.value === category) ? category : '';
            }
            const form = document.getElementById('activityFilterForm');
            if (form) form.requestSubmit();
            return;
        }

        const pBtn = e.target.closest('a.pagination-btn:not(.pagination-btn-disabled)');
        if (pBtn) {
            e.preventDefault();
            window.scrollTo({top: 0, behavior: 'smooth'});
            QD.loadDynamic(pBtn.href, CONTENT_ID, {replace: false});
        }
    });

    wrapper.addEventListener('change', function (e) {
        if (e.target.id === 'activityPageSize') {
            QD.loadDynamic(buildUrl(e.target.value), CONTENT_ID, {replace: true});
        }
    });
})();
