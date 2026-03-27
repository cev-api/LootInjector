package dev.cevapi.lootinjector.paper;

import java.util.Locale;

public enum LootRuleType {
    CUSTOM,
    REMOVE;

    public static LootRuleType fromString(String raw) {
        if (raw == null) {
            return CUSTOM;
        }
        try {
            return LootRuleType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CUSTOM;
        }
    }
}
