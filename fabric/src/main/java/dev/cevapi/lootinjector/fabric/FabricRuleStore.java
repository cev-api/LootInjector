package dev.cevapi.lootinjector.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.cevapi.lootinjector.common.TargetKey;
import dev.cevapi.lootinjector.common.TargetType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class FabricRuleStore {
    private static final List<String> VIRTUAL_STRUCTURE_IDS = List.of(
            "minecraft:trial_chambers_vault",
            "minecraft:trial_chambers_ominous_vault"
    );
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ROOT_TYPE = new TypeToken<LinkedHashMap<String, List<FabricStoredRule>>>() {}.getType();

    private final Path file;
    private final Map<String, List<FabricStoredRule>> byTarget = new LinkedHashMap<>();

    FabricRuleStore(Path configDir) {
        this.file = configDir.resolve("lootinjector_rules.json");
    }

    synchronized void load() {
        byTarget.clear();
        if (!Files.exists(file)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, List<FabricStoredRule>> loaded = GSON.fromJson(reader, ROOT_TYPE);
            if (loaded != null) {
                byTarget.putAll(loaded);
            }
        } catch (IOException ignored) {
        }
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(byTarget, ROOT_TYPE, writer);
            }
        } catch (IOException ignored) {
        }
    }

    synchronized List<FabricStoredRule> rulesFor(String targetKey) {
        return new ArrayList<>(byTarget.getOrDefault(targetKey, List.of()));
    }

    synchronized void addRule(String targetKey, String type, double chance, String itemId) {
        List<FabricStoredRule> rules = byTarget.computeIfAbsent(targetKey, unused -> new ArrayList<>());
        rules.add(new FabricStoredRule(UUID.randomUUID().toString(), type, clamp(chance), itemId));
        save();
    }

    synchronized void clearTarget(String targetKey) {
        byTarget.remove(targetKey);
        save();
    }

    synchronized List<String> configuredKeys(TargetType type) {
        List<String> keys = new ArrayList<>();
        for (String key : byTarget.keySet()) {
            if (TargetKey.typeOf(key) == type && !byTarget.getOrDefault(key, List.of()).isEmpty()) {
                keys.add(key);
            }
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    synchronized boolean hasRules(String targetKey) {
        return !byTarget.getOrDefault(targetKey, List.of()).isEmpty();
    }

    List<String> searchStructures(String query) {
        List<String> out = new ArrayList<>();
        for (String targetKey : configuredKeys(TargetType.STRUCTURE)) {
            String id = TargetKey.idOf(targetKey);
            if (contains(id, query)) {
                out.add(id);
            }
        }
        for (String id : VIRTUAL_STRUCTURE_IDS) {
            if (contains(id, query)) {
                out.add(id);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    List<String> searchLootTables(String query) {
        List<String> out = new ArrayList<>();
        for (String targetKey : configuredKeys(TargetType.LOOT_TABLE)) {
            String id = TargetKey.idOf(targetKey);
            if (contains(id, query)) {
                out.add(id);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    List<String> searchMobs(String query) {
        List<String> out = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.forEach(entityType -> {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString();
            if (contains(id, query)) {
                out.add(id);
            }
        });
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    List<String> searchVillagers(String query) {
        List<String> out = new ArrayList<>();
        for (String profession : List.of(
                "armorer", "butcher", "cartographer", "cleric", "farmer",
                "fisherman", "fletcher", "leatherworker", "librarian",
                "mason", "shepherd", "toolsmith", "weaponsmith")) {
            if (contains(profession, query)) {
                out.add(profession);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    List<String> searchEnchantTargets(String query) {
        List<String> out = new ArrayList<>();
        if (contains("book", query)) {
            out.add("book");
        }
        return out;
    }

    static ItemStack toStack(FabricStoredRule rule) {
        Identifier id = Identifier.tryParse(rule.itemId());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    private static boolean contains(String id, String query) {
        return query == null || query.isBlank() || id.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static double clamp(double chance) {
        if (Double.isNaN(chance) || Double.isInfinite(chance)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(100.0D, chance));
    }
}

record FabricStoredRule(String id, String type, double chance, String itemId) {}
