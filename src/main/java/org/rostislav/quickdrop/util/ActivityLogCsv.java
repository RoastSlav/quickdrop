package org.rostislav.quickdrop.util;

import org.rostislav.quickdrop.entity.ActivityLog;

import java.io.IOException;
import java.io.Writer;
import java.time.format.DateTimeFormatter;

/**
 * Serialises {@link ActivityLog} rows to RFC 4180 CSV for the retention archives and the
 * admin activity download. Row-at-a-time so large exports stream instead of buffering.
 */
public final class ActivityLogCsv {

    public static final String HEADER =
            "event_date,event_type,category,file_uuid,short_link_code,ip_address,user_agent,detail";

    private static final String CRLF = "\r\n";

    private ActivityLogCsv() {
    }

    public static void writeHeader(Writer writer) throws IOException {
        writer.write(HEADER);
        writer.write(CRLF);
    }

    public static void writeRow(Writer writer, ActivityLog log) throws IOException {
        writer.write(escape(log.getEventDate() == null ? null
                : log.getEventDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        writer.write(',');
        writer.write(escape(log.getEventType() == null ? null : log.getEventType().name()));
        writer.write(',');
        writer.write(escape(log.getEventType() == null ? null : log.getEventType().getCategory().name()));
        writer.write(',');
        writer.write(escape(log.getFile() == null ? null : log.getFile().uuid));
        writer.write(',');
        writer.write(escape(log.getShortLink() == null ? null : log.getShortLink().code));
        writer.write(',');
        writer.write(escape(log.getIpAddress()));
        writer.write(',');
        writer.write(escape(log.getUserAgent()));
        writer.write(',');
        writer.write(escape(log.getDetail()));
        writer.write(CRLF);
    }

    /**
     * Quotes per RFC 4180, and prefixes a leading {@code = + - @} with an apostrophe:
     * user_agent is attacker-controlled and would otherwise run as a spreadsheet formula.
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value;
        char first = escaped.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            escaped = "'" + escaped;
        }
        if (escaped.indexOf('"') >= 0 || escaped.indexOf(',') >= 0
                || escaped.indexOf('\n') >= 0 || escaped.indexOf('\r') >= 0) {
            return '"' + escaped.replace("\"", "\"\"") + '"';
        }
        return escaped;
    }
}
