package dev.cevapi.lootinjector.common;

public enum TargetType {
    STRUCTURE,
    LOOT_TABLE,
    MOB,
    VILLAGER,
    ENCHANT_TABLE;

    public static TargetType fromString(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "structure", "structures" -> STRUCTURE;
            case "loot_table", "loottable", "table", "loot" -> LOOT_TABLE;
            case "mob", "mobs", "entity", "entities" -> MOB;
            case "villager", "villagers", "trade", "trades" -> VILLAGER;
            case "enchant_table", "enchanting_table", "enchant", "enchanting" -> ENCHANT_TABLE;
            default -> null;
        };
    }
}
