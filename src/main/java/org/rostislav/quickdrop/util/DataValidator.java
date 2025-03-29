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

    public static double nullToZero(Double value) {
        if (value == null) {
            return 0;
        }
        return value;
    }

    public static int nullToZero(Integer value) {
        if (value == null) {
            return 0;
        }
        return value;
    }

    public static long nullToZero(Long value) {
        if (value == null) {
            return 0;
        }
        return value;
    }

    public static boolean nullToFalse(Boolean value) {
        if (value == null) {
            return false;
        }
        return value;
    }

    public static String nullToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }
}
