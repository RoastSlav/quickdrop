package org.rostislav.quickdrop.util;

/**
 * Static utility methods for null-safe value handling.
 *
 * <p>Non-instantiable utility class following the static-factory pattern.
 */
public class DataValidator {
    private DataValidator() {
        // Prevent instantiation
    }

    /**
     * Returns {@code true} only if all provided objects are non-null and, if they are
     * strings, are not blank after trimming.
     *
     * @param objs the objects to validate
     * @return {@code true} when every argument is present and non-blank
     */
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

    /**
     * Returns the string value, or {@code ""} if it is {@code null}.
     *
     * @param value the potentially null string
     * @return non-null string value
     */
    public static String safeString(String value) {
        return value == null ? "" : value;
    }

    /**
     * Returns the {@code Double} value, or {@code 0} if it is {@code null}.
     *
     * @param value the potentially null value
     * @return primitive double, defaulting to zero
     */
    public static double safeNumber(Double value) { return value == null ? 0 : value; }

    /**
     * Returns the {@code Integer} value, or {@code 0} if it is {@code null}.
     *
     * @param value the potentially null value
     * @return primitive int, defaulting to zero
     */
    public static int safeNumber(Integer value) { return value == null ? 0 : value; }

    /**
     * Returns the {@code Long} value, or {@code 0} if it is {@code null}.
     *
     * @param value the potentially null value
     * @return primitive long, defaulting to zero
     */
    public static long safeNumber(Long value) { return value == null ? 0 : value; }

    /**
     * Returns the {@code Boolean} value, or {@code false} if it is {@code null}.
     *
     * @param value the potentially null value
     * @return primitive boolean, defaulting to false
     */
    public static boolean safeBoolean(Boolean value) { return value != null && value; }
}
