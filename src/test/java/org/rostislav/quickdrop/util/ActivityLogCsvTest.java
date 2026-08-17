package org.rostislav.quickdrop.util;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.model.EventType;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityLogCsvTest {

    @Test
    void plainValuesAreNotQuoted() {
        assertEquals("curl/8.0", ActivityLogCsv.escape("curl/8.0"));
    }

    @Test
    void nullAndEmptyBecomeEmptyFields() {
        assertEquals("", ActivityLogCsv.escape(null));
        assertEquals("", ActivityLogCsv.escape(""));
    }

    @Test
    void commasQuotesAndNewlinesAreQuotedAndEscaped() {
        assertEquals("Mozilla/5.0 (X11; Linux)", ActivityLogCsv.escape("Mozilla/5.0 (X11; Linux)"));
        assertEquals("\"a,b\"", ActivityLogCsv.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ActivityLogCsv.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", ActivityLogCsv.escape("line1\nline2"));
    }

    /**
     * user_agent is attacker-controlled, and a leading =/+/-/@ is executed as a formula when
     * the export is opened in a spreadsheet.
     */
    @Test
    void formulaLeadingCharactersAreNeutralised() {
        assertEquals("'=1+1", ActivityLogCsv.escape("=1+1"));
        assertEquals("'+cmd", ActivityLogCsv.escape("+cmd"));
        assertEquals("'-2", ActivityLogCsv.escape("-2"));
        assertEquals("'@SUM(A1)", ActivityLogCsv.escape("@SUM(A1)"));
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\")\"", ActivityLogCsv.escape("=HYPERLINK(\"http://evil\")"));
    }

    @Test
    void rowHasOneFieldPerHeaderColumn() throws IOException {
        ActivityLog entry = new ActivityLog(EventType.ADMIN_LOGIN, "10.0.0.1", "curl/8.0");
        entry.setEventDate(LocalDateTime.of(2026, 8, 17, 21, 38, 4));
        entry.setDetail("some detail");

        StringWriter out = new StringWriter();
        ActivityLogCsv.writeRow(out, entry);

        String row = out.toString();
        assertTrue(row.endsWith("\r\n"), "RFC 4180 line terminator");
        assertEquals(ActivityLogCsv.HEADER.split(",", -1).length, row.strip().split(",", -1).length);
        assertTrue(row.startsWith("2026-08-17T21:38:04,ADMIN_LOGIN,ADMIN,,,10.0.0.1,curl/8.0,some detail"), row);
    }
}
