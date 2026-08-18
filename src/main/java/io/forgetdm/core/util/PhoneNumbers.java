package io.forgetdm.core.util;

import java.util.Locale;
import java.util.Set;

/** Shared structural rules for safe, deterministic phone generation and masking. */
public final class PhoneNumbers {
    private static final int[] NANP_AREAS = buildNanpAreas();
    private static final Set<String> ONE_DIGIT_CALLING_CODES = Set.of("1", "7");
    private static final Set<String> TWO_DIGIT_CALLING_CODES = Set.of(
            "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44",
            "45", "46", "47", "48", "49", "51", "52", "53", "54", "55", "56", "57", "58",
            "60", "61", "62", "63", "64", "65", "66", "81", "82", "84", "86", "90", "91",
            "92", "93", "94", "95", "98");

    private PhoneNumbers() {}

    public static int nanpAreaCount() {
        return NANP_AREAS.length;
    }

    public static int nanpAreaAt(long index) {
        return NANP_AREAS[Math.floorMod(index, NANP_AREAS.length)];
    }

    public static boolean isValidNanpArea(String value) {
        if (value == null || !value.matches("\\d{3}")) return false;
        int area = Integer.parseInt(value);
        return area >= 200 && area <= 999 && area % 100 != 11 && area != 555;
    }

    /** Returns a structurally valid NANP number in the reserved fictional 555-0100..0199 range. */
    public static String fictionalNanpDigits(int area, long lineIndex) {
        int safeArea = isValidNanpArea(String.format(Locale.ROOT, "%03d", area)) ? area : 212;
        int line = 100 + Math.floorMod(lineIndex, 100);
        return String.format(Locale.ROOT, "%03d555%04d", safeArea, line);
    }

    public static String formatNanp(String tenDigits, String style) {
        if (tenDigits == null || !tenDigits.matches("\\d{10}")) {
            throw new IllegalArgumentException("A NANP phone must contain exactly 10 digits");
        }
        String normalized = style == null || style.isBlank()
                ? "NATIONAL"
                : style.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "E164", "E_164", "INTERNATIONAL" -> "+1" + tenDigits;
            case "DIGITS", "DIGITS_ONLY" -> tenDigits;
            case "DASHED", "DASHES" -> tenDigits.substring(0, 3) + "-" + tenDigits.substring(3, 6)
                    + "-" + tenDigits.substring(6);
            default -> "(" + tenDigits.substring(0, 3) + ") " + tenDigits.substring(3, 6)
                    + "-" + tenDigits.substring(6);
        };
    }

    /**
     * Determines how many leading digits belong to an international calling code. This prevents a
     * compact E.164 value such as +14155550182 from being mistaken for one giant country code.
     */
    public static int callingCodeLength(String value) {
        if (value == null) return 0;
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
        if (start >= value.length() || value.charAt(start) != '+') return 0;
        String digits = value.substring(start + 1).replaceAll("\\D", "");
        if (digits.isEmpty()) return 0;
        if (ONE_DIGIT_CALLING_CODES.contains(digits.substring(0, 1))) return 1;
        if (digits.length() >= 2 && TWO_DIGIT_CALLING_CODES.contains(digits.substring(0, 2))) return 2;
        return Math.min(3, digits.length());
    }

    private static int[] buildNanpAreas() {
        int count = 0;
        for (int area = 200; area <= 999; area++) {
            if (area % 100 != 11 && area != 555) count++;
        }
        int[] values = new int[count];
        int index = 0;
        for (int area = 200; area <= 999; area++) {
            if (area % 100 != 11 && area != 555) values[index++] = area;
        }
        return values;
    }
}
