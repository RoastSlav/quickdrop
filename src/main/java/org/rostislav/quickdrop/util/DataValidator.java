package org.rostislav.quickdrop.util;

public class DataValidator {
    private DataValidator() {
        // To prevent instantiation
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

    public static double safeNumber(Double value) {
        if (value == null) {
            return 0;
        }
        return value;
    }

    public static int safeNumber(Integer value) {
        if (value == null) {
            return 0;
        }
        return value;
    }

    public static long safeNumber(Long value) {
        if (value == null) {
            return 0;
        }
        return value;
    }

    public static boolean safeBoolean(Boolean value) {
        if (value == null) {
            return false;
        }
        return value;
    }
}
