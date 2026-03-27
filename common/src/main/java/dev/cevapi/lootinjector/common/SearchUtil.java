package dev.cevapi.lootinjector.common;

public final class SearchUtil {
    private SearchUtil() {
    }

    public static boolean contains(String haystack, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (haystack == null) {
            return false;
        }
        return haystack.toLowerCase().contains(query.trim().toLowerCase());
    }
}
