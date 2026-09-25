package org.rostislav.quickdrop.util;

/** Null-safe value-handling helpers. */
public class DataValidator {
    private DataValidator() {
    }

    public static boolean validateObjects(Object... objs) {
        for (Object temp : objs) {
            if (temp != null) {
                if (temp instanceof String value && value.trim().isEmpty()) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    public static String safeString(String value) {
        return value == null ? "" : value;
    }

    public static long safeNumber(Long value) {
        return value == null ? 0 : value;
    }
}
