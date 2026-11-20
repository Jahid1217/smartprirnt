package com.smartprint.order;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Parses the customer's page selection into an ordered list of 1-based page numbers.
 * Supported syntax: ALL, 1-5, 1,3,7, and combinations such as 1-3,7,9.
 * Out-of-range pages are rejected rather than silently dropped so the displayed
 * cost always matches the pages that will print.
 */
public final class PageSelection {
    public static final String ALL = "ALL";

    private PageSelection() {}

    public static List<Integer> parse(String expression, int pageCount) {
        if (pageCount <= 0) {
            throw new IllegalArgumentException("The document has no printable pages");
        }
        String normalised = expression == null ? ALL : expression.trim().toUpperCase();
        if (normalised.isEmpty() || ALL.equals(normalised)) {
            List<Integer> all = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) all.add(page);
            return all;
        }

        TreeSet<Integer> pages = new TreeSet<>();
        for (String part : normalised.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            if (token.contains("-")) {
                String[] bounds = token.split("-", -1);
                if (bounds.length != 2) {
                    throw new IllegalArgumentException("Invalid page range '" + token + "'");
                }
                int from = number(bounds[0], token);
                int to = number(bounds[1], token);
                if (from > to) {
                    throw new IllegalArgumentException("Page range '" + token + "' is backwards");
                }
                if (to > pageCount) {
                    throw new IllegalArgumentException(
                        "Page " + to + " does not exist; this document has " + pageCount + " pages");
                }
                for (int page = from; page <= to; page++) pages.add(page);
            } else {
                int page = number(token, token);
                if (page > pageCount) {
                    throw new IllegalArgumentException(
                        "Page " + page + " does not exist; this document has " + pageCount + " pages");
                }
                pages.add(page);
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("Select at least one page to print");
        }
        return new ArrayList<>(pages);
    }

    public static String normalise(String expression, int pageCount) {
        List<Integer> pages = parse(expression, pageCount);
        if (pages.size() == pageCount) return ALL;
        return join(pages);
    }

    public static String join(List<Integer> pages) {
        StringBuilder builder = new StringBuilder();
        for (int page : pages) {
            if (builder.length() > 0) builder.append(',');
            builder.append(page);
        }
        return builder.toString();
    }

    private static int number(String value, String token) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid page number in '" + token + "'");
        }
    }
}
