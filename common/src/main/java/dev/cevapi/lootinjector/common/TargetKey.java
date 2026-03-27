package dev.cevapi.lootinjector.common;

import java.util.Locale;

public final class TargetKey {
    private TargetKey() {
    }

    public static String of(TargetType type, String id) {
        String normalized = normalizeId(id);
        return type.name().toLowerCase(Locale.ROOT) + ":" + normalized;
    }

    public static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public static TargetType typeOf(String key) {
        int idx = key.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        return TargetType.fromString(key.substring(0, idx));
    }

    public static String idOf(String key) {
        int idx = key.indexOf(':');
        if (idx <= 0 || idx + 1 >= key.length()) {
            return "";
        }
        return key.substring(idx + 1);
    }
}
