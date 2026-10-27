package com.illini.grades.util;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Matches catalog initials and full names while keeping the surname exact. */
public final class InstructorNameSearch {
    private InstructorNameSearch() {}

    public static final String NORMALIZED_NAME =
            "BTRIM(regexp_replace(LOWER(i.name), '[[:space:],.]+', ' ', 'g'))";

    /** PostgreSQL regex for either name order, or empty for a partial one-word query. */
    public static String patternFor(String query) {
        if (query == null || query.isBlank()) return "";
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        String surname;
        String given;
        if (normalized.contains(",")) {
            String[] parts = normalized.split(",", 2);
            surname = normalize(parts[0]);
            given = normalize(parts[1]);
        } else {
            String[] parts = normalize(normalized).split(" ");
            if (parts.length < 2) return "";
            surname = parts[parts.length - 1];
            given = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
        }
        if (surname.isBlank() || given.isBlank()) return "";
        String givenPattern = Arrays.stream(given.split(" "))
                .map(InstructorNameSearch::givenPattern)
                .collect(Collectors.joining(" "));
        String middleNames = "( [[:alpha:]]+)*";
        return "^(" + literal(surname) + " " + givenPattern + middleNames
                + "|" + givenPattern + middleNames + " " + literal(surname) + ")$";
    }

    private static String normalize(String name) {
        return name.replaceAll("[\\s,.]+", " ").strip();
    }

    private static String givenPattern(String name) {
        if (name.codePointCount(0, name.length()) == 1) return literal(name) + "[[:alpha:]]*";
        String initial = new String(Character.toChars(name.codePointAt(0)));
        return "(" + literal(name) + "|" + literal(initial) + ")";
    }

    private static String literal(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }
}
