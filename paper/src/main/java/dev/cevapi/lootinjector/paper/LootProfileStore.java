package dev.cevapi.lootinjector.paper;

import dev.cevapi.lootinjector.common.ChanceUtil;
import dev.cevapi.lootinjector.common.TargetKey;
import dev.cevapi.lootinjector.common.TargetType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LootProfileStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, StructureLootProfile> profiles = new LinkedHashMap<>();

    public LootProfileStore(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "loot_profiles.yml");
    }

    public synchronized void load() {
        profiles.clear();
        if (!file.exists()) {
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection structures = yaml.getConfigurationSection("targets");
        if (structures == null) {
            return;
        }

        for (String targetKey : structures.getKeys(false)) {
            ConfigurationSection profileSection = structures.getConfigurationSection(targetKey);
            if (profileSection == null) {
                continue;
            }
            StructureLootProfile profile = new StructureLootProfile(targetKey);
            ConfigurationSection rulesSection = profileSection.getConfigurationSection("rules");
            if (rulesSection != null) {
                for (String ruleId : rulesSection.getKeys(false)) {
                    ConfigurationSection ruleSection = rulesSection.getConfigurationSection(ruleId);
                    if (ruleSection == null) {
                        continue;
                    }
                    String typeRaw = ruleSection.getString("type", LootRuleType.CUSTOM.name());
                    // Legacy OBSERVED entries came from old auto-learning behavior; ignore them completely.
                    if ("OBSERVED".equalsIgnoreCase(typeRaw)) {
                        continue;
                    }
                    LootRuleType type = LootRuleType.fromString(typeRaw);
                    double chance = ChanceUtil.clamp(ruleSection.getDouble("chance", 100.0D));
                    ItemStack item = ruleSection.getItemStack("item");
                    if (item == null) {
                        ConfigurationSection itemSection = ruleSection.getConfigurationSection("item");
                        if (itemSection != null) {
                            try {
                                item = ItemStack.deserialize(sectionToMap(itemSection));
                            } catch (Throwable ignored) {
                                item = null;
                            }
                        }
                    }
                    if (item == null || item.getType().isAir()) {
                        continue;
                    }
                    profile.rules().add(new LootRule(ruleId, type, chance, item));
                }
            }
            profiles.put(targetKey, profile);
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection targets = yaml.createSection("targets");
        for (Map.Entry<String, StructureLootProfile> entry : profiles.entrySet()) {
            ConfigurationSection profileSection = targets.createSection(entry.getKey());
            ConfigurationSection rules = profileSection.createSection("rules");
            for (LootRule rule : entry.getValue().rules()) {
                ConfigurationSection ruleSection = rules.createSection(rule.id());
                ruleSection.set("type", rule.type().name());
                ruleSection.set("chance", ChanceUtil.clamp(rule.chance()));
                ruleSection.set("item", rule.item());
            }
        }
        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                plugin.getLogger().warning("Could not create LootInjector data folder.");
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save loot_profiles.yml: " + ex.getMessage());
        }
    }

    public synchronized @NotNull StructureLootProfile profile(@NotNull String targetKey) {
        return profiles.computeIfAbsent(targetKey, StructureLootProfile::new);
    }

    public synchronized boolean hasProfile(@NotNull String targetKey) {
        StructureLootProfile profile = profiles.get(targetKey);
        return profile != null && !profile.rules().isEmpty();
    }

    public synchronized @NotNull List<String> configuredTargetKeys(@NotNull TargetType type) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, StructureLootProfile> entry : profiles.entrySet()) {
            if (entry.getValue().rules().isEmpty()) {
                continue;
            }
            if (TargetKey.typeOf(entry.getKey()) == type) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    public synchronized @NotNull List<String> allConfiguredTargetKeys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, StructureLootProfile> entry : profiles.entrySet()) {
            if (!entry.getValue().rules().isEmpty()) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    public synchronized @NotNull String addRule(@NotNull String targetKey, @NotNull LootRuleType type, double chance, @NotNull ItemStack item) {
        String id = UUID.randomUUID().toString();
        profile(targetKey).rules().add(new LootRule(id, type, ChanceUtil.clamp(chance), item.clone()));
        save();
        return id;
    }

    public synchronized boolean removeRule(@NotNull String targetKey, @NotNull String ruleId) {
        StructureLootProfile profile = profile(targetKey);
        boolean removed = profile.rules().removeIf(rule -> rule.id().equals(ruleId));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized void updateChance(@NotNull String targetKey, @NotNull String ruleId, double chance) {
        StructureLootProfile profile = profile(targetKey);
        List<LootRule> rules = profile.rules();
        for (int i = 0; i < rules.size(); i++) {
            LootRule rule = rules.get(i);
            if (rule.id().equals(ruleId)) {
                rules.set(i, new LootRule(rule.id(), rule.type(), ChanceUtil.clamp(chance), rule.item()));
                break;
            }
        }
        save();
    }

    public synchronized void updateItem(@NotNull String targetKey, @NotNull String ruleId, @NotNull ItemStack item) {
        StructureLootProfile profile = profile(targetKey);
        List<LootRule> rules = profile.rules();
        for (int i = 0; i < rules.size(); i++) {
            LootRule rule = rules.get(i);
            if (rule.id().equals(ruleId)) {
                rules.set(i, new LootRule(rule.id(), rule.type(), rule.chance(), item.clone()));
                break;
            }
        }
        save();
    }

    public synchronized void clearRules(@NotNull String targetKey) {
        profile(targetKey).rules().clear();
        save();
    }

    private static @NotNull Map<String, Object> sectionToMap(@NotNull ConfigurationSection section) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                map.put(key, sectionToMap(nested));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }
}
