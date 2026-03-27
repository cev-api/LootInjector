package dev.cevapi.lootinjector.paper;

import java.util.ArrayList;
import java.util.List;

public final class StructureLootProfile {
    private final String targetKey;
    private final List<LootRule> rules = new ArrayList<>();

    public StructureLootProfile(String targetKey) {
        this.targetKey = targetKey;
    }

    public String targetKey() {
        return targetKey;
    }

    public List<LootRule> rules() {
        return rules;
    }
}
