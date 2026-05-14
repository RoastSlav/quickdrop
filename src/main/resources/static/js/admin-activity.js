(function () {
    var WRAPPER_ID = 'activitySectionWrapper';
    var CONTENT_ID = 'activityContent';

    var wrapper = document.getElementById(WRAPPER_ID);
    if (!wrapper) return;

    function localDateStr(d) {
        return d.getFullYear() + '-' +
            String(d.getMonth() + 1).padStart(2, '0') + '-' +
            String(d.getDate()).padStart(2, '0');
    }

    function buildUrl(sizeOverride) {
        var p = new URLSearchParams();
        var sd = (wrapper.querySelector('#startDatePicker') || {}).value || '';
        var st = (wrapper.querySelector('#startTimePicker') || {}).value || '00:00';
        if (sd) p.set('startDate', sd + 'T' + st);

        var ed = (wrapper.querySelector('#endDatePicker') || {}).value || '';
        var et = (wrapper.querySelector('#endTimePicker') || {}).value || '23:59';
        if (ed) p.set('endDate', ed + 'T' + et);

        var evtType = (wrapper.querySelector('#eventType') || {}).value || '';
        if (evtType) p.set('eventType', evtType);

        var ip = ((wrapper.querySelector('#ipFilter') || {}).value || '').trim();
        if (ip) p.set('ip', ip);

        var ua = ((wrapper.querySelector('#uaFilter') || {}).value || '').trim();
        if (ua) p.set('ua', ua);

        var size = sizeOverride != null
            ? sizeOverride
            : ((wrapper.querySelector('#activityPageSize') || {}).value
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
        var presetBtn = e.target.closest('[data-preset]');
        if (presetBtn) {
            var now = new Date();
            var startD = '', startT = '', endD = '', endT = '';
            switch (presetBtn.dataset.preset) {
                case 'today':
                    startD = localDateStr(now);
                    startT = '00:00';
                    endD = localDateStr(now);
                    endT = '23:59';
                    break;
                case '7d':
                    var d7 = new Date(now);
                    d7.setDate(d7.getDate() - 7);
                    startD = localDateStr(d7);
                    startT = '00:00';
                    endD = localDateStr(now);
                    endT = '23:59';
                    break;
                case '30d':
                    var d30 = new Date(now);
                    d30.setDate(d30.getDate() - 30);
                    startD = localDateStr(d30);
                    startT = '00:00';
                    endD = localDateStr(now);
                    endT = '23:59';
                    break;
                case 'all':
                    break;
            }
            var sp = wrapper.querySelector('#startDatePicker');
            var st2 = wrapper.querySelector('#startTimePicker');
            var ep = wrapper.querySelector('#endDatePicker');
            var et2 = wrapper.querySelector('#endTimePicker');
            if (sp) sp.value = startD;
            if (st2) st2.value = startT;
            if (ep) ep.value = endD;
            if (et2) et2.value = endT;
            return;
        }

        var pBtn = e.target.closest('a.pagination-btn:not(.pagination-btn-disabled)');
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
