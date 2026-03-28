package dev.cevapi.lootinjector.paper;

import dev.cevapi.lootinjector.common.TargetKey;
import dev.cevapi.lootinjector.common.TargetType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class StructureRuntimeInjector implements Listener {
    private final LootInjectorPlugin plugin;
    private final LootProfileStore store;
    private final NamespacedKey processedChestKey;
    private final NamespacedKey playerPlacedContainerKey;
    private final NamespacedKey enchantXpCostKey;
    private final NamespacedKey villagerEmeraldCostKey;
    private final NamespacedKey villagerSpawnChanceKey;
    private final NamespacedKey villagerLevelKey;
    private final Map<String, List<ItemStack>> observedVillagerResults = new ConcurrentHashMap<>();

    public StructureRuntimeInjector(@NotNull LootInjectorPlugin plugin,
                                    @NotNull LootProfileStore store,
                                    @NotNull NamespacedKey processedChestKey,
                                    @NotNull NamespacedKey playerPlacedContainerKey) {
        this.plugin = plugin;
        this.store = store;
        this.processedChestKey = processedChestKey;
        this.playerPlacedContainerKey = playerPlacedContainerKey;
        this.enchantXpCostKey = new NamespacedKey(plugin, "enchant_xp_cost");
        this.villagerEmeraldCostKey = new NamespacedKey(plugin, "villager_emerald_cost");
        this.villagerSpawnChanceKey = new NamespacedKey(plugin, "villager_spawn_chance");
        this.villagerLevelKey = new NamespacedKey(plugin, "villager_trade_level");
    }

    public void captureExistingVillagerCatalog() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                String professionId = villager.getProfession().name().toLowerCase(Locale.ROOT);
                int villagerLevel = Math.max(1, Math.min(5, villager.getVillagerLevel()));
                for (MerchantRecipe recipe : villager.getRecipes()) {
                    if (recipe == null || recipe.getResult() == null) {
                        continue;
                    }
                    recordObservedVillagerResult(professionId, recipe.getResult(), recipeEmeraldCost(recipe), villagerLevel);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerPlace(@NotNull BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof TileState tileState)) {
            return;
        }
        if (!(tileState instanceof Container)) {
            return;
        }
        tileState.getPersistentDataContainer().set(playerPlacedContainerKey, PersistentDataType.BYTE, (byte) 1);
        tileState.update(true, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerOpen(@NotNull PlayerInteractEvent event) {
        // Runtime loot mutation is handled by LootGenerateEvent.
        // Do not mutate inventories on open, because structure-based open-time
        // matching can bleed rules across different container loot tables
        // inside the same structure (e.g. ancient city chest vs barrel tables).
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLootGenerate(@NotNull LootGenerateEvent event) {
        if (event.getLootTable() == null || event.getLootTable().getKey() == null) {
            return;
        }
        String lootTableId = event.getLootTable().getKey().toString().toLowerCase(Locale.ROOT);
        String targetKey = TargetKey.of(TargetType.LOOT_TABLE, lootTableId);
        List<ItemStack> loot = new ArrayList<>(event.getLoot());

        if (store.hasProfile(targetKey)) {
            applyRules(targetKey, loot);
        }

        for (String structureAlias : structureAliasesForLootTable(lootTableId)) {
            String structureTarget = TargetKey.of(TargetType.STRUCTURE, structureAlias);
            if (store.hasProfile(structureTarget)) {
                applyRules(structureTarget, loot);
            }
        }

        event.setLoot(loot);
    }

    private @NotNull Set<String> structureAliasesForLootTable(@NotNull String lootTableId) {
        Set<String> out = new LinkedHashSet<>();
        int colon = lootTableId.indexOf(':');
        if (colon <= 0 || colon + 1 >= lootTableId.length()) {
            return out;
        }
        String namespace = lootTableId.substring(0, colon).toLowerCase(Locale.ROOT);
        String path = lootTableId.substring(colon + 1).toLowerCase(Locale.ROOT);
        if (!isLikelyStructureContainerTable(path)) {
            return out;
        }
        String marker = "chests/";
        int idx = path.indexOf(marker);
        String tablePath = (idx >= 0 && idx + marker.length() < path.length()) ? path.substring(idx + marker.length()) : path;

        addStructureAliasCandidates(out, namespace, tablePath);
        addCanonicalStructureAliases(out, namespace, tablePath);

        if (tablePath.startsWith("trial_chambers/") || tablePath.startsWith("trial_chamber/")) {
            if (tablePath.contains("ominous")) {
                out.add(namespace + ":trial_chambers_ominous_vault");
            } else if (tablePath.contains("vault") || tablePath.contains("reward")) {
                out.add(namespace + ":trial_chambers_vault");
            } else {
                out.add(namespace + ":trial_chambers");
            }
        }
        return out;
    }

    private void addStructureAliasCandidates(@NotNull Set<String> out, @NotNull String namespace, @NotNull String tablePath) {
        if (!tablePath.isBlank()) {
            out.add(namespace + ":" + tablePath);
        }
        String[] parts = tablePath.split("/");
        if (parts.length > 0) {
            String first = parts[0];
            if (!first.isBlank()) out.add(namespace + ":" + first);
            String last = parts[parts.length - 1];
            if (!last.isBlank()) out.add(namespace + ":" + last);
        }

        String leaf = parts.length == 0 ? tablePath : parts[parts.length - 1];
        if (leaf == null || leaf.isBlank()) return;

        String trimmed = leaf;
        String[] suffixes = {"_chest", "_barrel", "_crate", "_supply", "_loot", "_cache"};
        for (String suffix : suffixes) {
            if (trimmed.endsWith(suffix) && trimmed.length() > suffix.length()) {
                trimmed = trimmed.substring(0, trimmed.length() - suffix.length());
                break;
            }
        }
        if (!trimmed.isBlank()) out.add(namespace + ":" + trimmed);

        String[] tokens = trimmed.split("_");
        if (tokens.length >= 2) {
            out.add(namespace + ":" + tokens[0] + "_" + tokens[1]);
        }
        if (tokens.length >= 3) {
            out.add(namespace + ":" + tokens[0] + "_" + tokens[1] + "_" + tokens[2]);
        }
    }

    private void addCanonicalStructureAliases(@NotNull Set<String> out, @NotNull String namespace, @NotNull String tablePath) {
        String leaf = tablePath;
        int slash = tablePath.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < tablePath.length()) {
            leaf = tablePath.substring(slash + 1);
        }
        if ("nether_bridge".equals(leaf)) {
            out.add(namespace + ":fortress");
            out.add(namespace + ":nether_fortress");
        }
    }

    private boolean isLikelyStructureContainerTable(@NotNull String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String[] denyPrefixes = {
                "blocks/", "block/",
                "entities/", "entity/",
                "items/", "item/",
                "gameplay/", "gifts/", "gift/",
                "sheep/", "archeology/", "archaeology/"
        };
        for (String prefix : denyPrefixes) {
            if (lower.startsWith(prefix)) {
                return false;
            }
        }
        return lower.contains("chest")
                || lower.contains("barrel")
                || lower.contains("crate")
                || lower.contains("cache")
                || lower.contains("supply")
                || lower.contains("treasure")
                || lower.contains("vault")
                || lower.contains("reward")
                || lower.contains("/")
                || lower.startsWith("chests/");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity == null || entity.getType() == null) {
            return;
        }
        String targetKey = TargetKey.of(TargetType.MOB, entity.getType().getKey().toString());
        if (!store.hasProfile(targetKey)) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        applyRules(targetKey, drops);
        event.getDrops().clear();
        event.getDrops().addAll(drops);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerAcquireTrade(@NotNull VillagerAcquireTradeEvent event) {
        AbstractVillager villager = event.getEntity();
        if (!(villager instanceof Villager realVillager)) {
            return;
        }
        MerchantRecipe recipe = event.getRecipe();
        if (recipe != null) {
            int villagerLevel = Math.max(1, Math.min(5, realVillager.getVillagerLevel()));
            recordObservedVillagerResult(realVillager.getProfession().name().toLowerCase(Locale.ROOT), recipe.getResult(), recipeEmeraldCost(recipe), villagerLevel);
        }
        String professionId = realVillager.getProfession().name().toLowerCase(Locale.ROOT);
        String targetKey = TargetKey.of(TargetType.VILLAGER, professionId);
        if (!store.hasProfile(targetKey)) {
            return;
        }
        appendConfiguredVillagerTrades(realVillager, targetKey);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerInteract(@NotNull PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        String professionId = villager.getProfession().name().toLowerCase(Locale.ROOT);
        for (MerchantRecipe recipe : villager.getRecipes()) {
            if (recipe != null && recipe.getResult() != null) {
                int villagerLevel = Math.max(1, Math.min(5, villager.getVillagerLevel()));
                recordObservedVillagerResult(professionId, recipe.getResult(), recipeEmeraldCost(recipe), villagerLevel);
            }
        }
        String targetKey = TargetKey.of(TargetType.VILLAGER, professionId);
        if (!store.hasProfile(targetKey)) {
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchantBook(@NotNull EnchantItemEvent event) {
        if (event.getItem() == null || event.getItem().getType() != Material.BOOK) {
            return;
        }
        String targetKey = TargetKey.of(TargetType.ENCHANT_TABLE, "book");
        if (!store.hasProfile(targetKey)) {
            return;
        }

        List<LootRule> remove = new ArrayList<>();
        List<LootRule> custom = new ArrayList<>();
        splitRules(store.profile(targetKey).rules(), remove, custom);

        if (!remove.isEmpty()) {
            ItemStack vanillaResult = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) vanillaResult.getItemMeta();
            if (meta != null) {
                for (var entry : event.getEnchantsToAdd().entrySet()) {
                    meta.addStoredEnchant(entry.getKey(), entry.getValue(), true);
                }
                vanillaResult.setItemMeta(meta);
                if (matchesAny(remove, vanillaResult)) {
                    event.setCancelled(true);
                    Player player = event.getEnchanter();
                    if (player != null) {
                        player.sendMessage("This enchant result is blocked by LootInjector.");
                    }
                    return;
                }
            }
        }

        List<LootRule> rolled = new ArrayList<>();
        int selectedLevelCost = Math.max(1, event.getExpLevelCost());
        for (LootRule rule : custom) {
            if (!isBookType(rule.item().getType())) {
                continue;
            }
            if (enchantXpCost(rule.item()) != selectedLevelCost) {
                continue;
            }
            if (!rollFailed(rule.chance())) {
                rolled.add(rule);
            }
        }
        if (rolled.isEmpty()) {
            return;
        }
        LootRule selected = rolled.get(ThreadLocalRandom.current().nextInt(rolled.size()));
        int xpCost = enchantXpCost(selected.item());
        applyCustomEnchantSelection(event, selected.item(), xpCost);
    }

    private boolean applyCustomEnchantSelection(@NotNull EnchantItemEvent event,
                                                @NotNull ItemStack selection,
                                                int xpCost) {
        Player player = event.getEnchanter();
        if (player == null) {
            event.setCancelled(true);
            return false;
        }

        int lapisCost = Math.max(1, event.whichButton() + 1);
        boolean creative = player.getGameMode() == org.bukkit.GameMode.CREATIVE;

        if (!(event.getInventory() instanceof org.bukkit.inventory.EnchantingInventory inv)) {
            event.setCancelled(true);
            return false;
        }
        ItemStack base = inv.getItem(0);
        if (base == null || base.getType() != Material.BOOK || base.getAmount() < 1) {
            event.setCancelled(true);
            return false;
        }
        ItemStack lapis = inv.getSecondary();
        if (!creative) {
            if (player.getLevel() < xpCost) {
                player.sendMessage("Not enough levels for this custom enchant offer.");
                event.setCancelled(true);
                return false;
            }
            if (lapis == null || lapis.getType() != Material.LAPIS_LAZULI || lapis.getAmount() < lapisCost) {
                player.sendMessage("Not enough lapis for this custom enchant offer.");
                event.setCancelled(true);
                return false;
            }
        }

        event.setCancelled(true);

        if (!creative) {
            player.setLevel(player.getLevel() - xpCost);
            ItemStack lapisNext = lapis.clone();
            lapisNext.setAmount(lapisNext.getAmount() - lapisCost);
            inv.setSecondary(lapisNext.getAmount() <= 0 ? null : lapisNext);
        }

        ItemStack baseNext = base.clone();
        baseNext.setAmount(baseNext.getAmount() - 1);
        inv.setItem(0, baseNext.getAmount() <= 0 ? null : baseNext);

        if (selection.getType().isAir()) {
            return false;
        }
        ItemStack reward = selection.clone();
        reward.setAmount(Math.max(1, reward.getAmount()));
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(reward);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
        player.updateInventory();
        return true;
    }

    private boolean isBookType(@NotNull Material material) {
        return material == Material.BOOK
                || material == Material.ENCHANTED_BOOK
                || material == Material.WRITTEN_BOOK
                || material == Material.WRITABLE_BOOK;
    }

    private int enchantXpCost(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return 10;
        }
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(enchantXpCostKey, PersistentDataType.INTEGER);
        if (stored == null) {
            return 10;
        }
        return Math.max(1, Math.min(30, stored));
    }

    private int villagerTradeLevel(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return 1;
        }
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(villagerLevelKey, PersistentDataType.INTEGER);
        if (stored == null) {
            return 1;
        }
        return Math.max(1, Math.min(5, stored));
    }

    private int villagerEmeraldCost(@NotNull LootRule rule) {
        if (rule.item().hasItemMeta()) {
            Integer stored = rule.item().getItemMeta().getPersistentDataContainer().get(villagerEmeraldCostKey, PersistentDataType.INTEGER);
            if (stored != null) {
                return Math.max(1, Math.min(64, stored));
            }
        }
        // Legacy fallback: chance field used to be emerald cost.
        return Math.max(1, Math.min(64, (int) Math.round(rule.chance())));
    }

    private double villagerSpawnChance(@NotNull LootRule rule) {
        if (rule.item().hasItemMeta()) {
            Double stored = rule.item().getItemMeta().getPersistentDataContainer().get(villagerSpawnChanceKey, PersistentDataType.DOUBLE);
            if (stored != null) {
                return Math.max(0.0D, Math.min(100.0D, stored));
            }
        }
        // Legacy fallback: old rules had no spawn chance; treat as always on.
        return 100.0D;
    }

    private boolean villagerRulePasses(@NotNull Villager villager, @NotNull LootRule rule, double chancePercent) {
        if (chancePercent >= 100.0D) {
            return true;
        }
        if (chancePercent <= 0.0D) {
            return false;
        }
        int basisPoints = (int) Math.round(Math.max(0.0D, Math.min(100.0D, chancePercent)) * 100.0D);
        int roll = Math.floorMod((villager.getUniqueId().toString() + "|" + rule.id()).hashCode(), 10000);
        return roll < basisPoints;
    }

    private void appendConfiguredVillagerTrades(@NotNull Villager villager, @NotNull String targetKey) {
        StructureLootProfile profile = store.profile(targetKey);
        List<LootRule> custom = new ArrayList<>();
        splitRules(profile.rules(), new ArrayList<>(), custom);

        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
        boolean changed = false;

        for (LootRule rule : custom) {
            int requiredLevel = villagerTradeLevel(rule.item());
            if (villager.getVillagerLevel() < requiredLevel) {
                continue;
            }
            double spawnChance = villagerSpawnChance(rule);
            if (!villagerRulePasses(villager, rule, spawnChance)) {
                continue;
            }
            int emeraldCost = villagerEmeraldCost(rule);
            MerchantRecipe existing = findMatchingCustomRecipe(recipes, rule.item());
            if (existing != null) {
                continue;
            }
            MerchantRecipe recipe = new MerchantRecipe(rule.item().clone(), 999999);
            recipe.addIngredient(new ItemStack(org.bukkit.Material.EMERALD, emeraldCost));
            recipe.setVillagerExperience(1);
            recipe.setPriceMultiplier(0.0F);
            recipes.add(recipe);
            changed = true;
        }

        if (changed) {
            villager.setRecipes(recipes);
        }
        for (MerchantRecipe recipe : recipes) {
            if (recipe != null && recipe.getResult() != null) {
                int villagerLevel = Math.max(1, Math.min(5, villager.getVillagerLevel()));
                recordObservedVillagerResult(TargetKey.idOf(targetKey), recipe.getResult(), recipeEmeraldCost(recipe), villagerLevel);
            }
        }
    }

    private @Nullable MerchantRecipe findMatchingCustomRecipe(@NotNull List<MerchantRecipe> recipes, @NotNull ItemStack result) {
        for (MerchantRecipe recipe : recipes) {
            if (recipe == null || recipe.getResult() == null) {
                continue;
            }
            ItemStack existing = recipe.getResult().clone();
            existing.setAmount(1);
            ItemStack wanted = result.clone();
            wanted.setAmount(1);
            if (!existing.isSimilar(wanted)) {
                continue;
            }
            int wantedEmeralds = villagerEmeraldCostFromResult(result);
            int existingEmeralds = recipeEmeraldCost(recipe);
            if (wantedEmeralds != existingEmeralds) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    private int villagerEmeraldCostFromResult(@NotNull ItemStack result) {
        if (!result.hasItemMeta()) {
            return 1;
        }
        Integer stored = result.getItemMeta().getPersistentDataContainer().get(villagerEmeraldCostKey, PersistentDataType.INTEGER);
        if (stored == null) {
            return 1;
        }
        return Math.max(1, Math.min(64, stored));
    }

    private int recipeEmeraldCost(@NotNull MerchantRecipe recipe) {
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient != null && ingredient.getType() == Material.EMERALD) {
                return Math.max(1, Math.min(64, ingredient.getAmount()));
            }
        }
        return 1;
    }

    private void recordObservedVillagerResult(@NotNull String professionId, ItemStack result) {
        recordObservedVillagerResult(professionId, result, 1, 1);
    }

    private void recordObservedVillagerResult(@NotNull String professionId,
                                              ItemStack result,
                                              int emeraldCost,
                                              int villagerLevel) {
        if (result == null || result.getType().isAir()) {
            return;
        }
        observedVillagerResults.compute(professionId.toLowerCase(Locale.ROOT), (key, existing) -> {
            List<ItemStack> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            ItemStack candidate = result.clone();
            candidate.setAmount(1);
            try {
                var meta = candidate.getItemMeta();
                if (meta != null) {
                    List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                    lore.add(net.kyori.adventure.text.Component.text("Typical cost: " + emeraldCost + " emerald", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                    lore.add(net.kyori.adventure.text.Component.text("Typical level: " + villagerLevel, net.kyori.adventure.text.format.NamedTextColor.GRAY));
                    meta.lore(lore);
                    candidate.setItemMeta(meta);
                }
            } catch (Throwable ignored) {
            }
            boolean present = false;
            for (ItemStack stack : list) {
                ItemStack left = stack.clone();
                left.setAmount(1);
                if (left.isSimilar(candidate)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                list.add(candidate);
            }
            return list;
        });
    }

    public @NotNull List<ItemStack> observedVillagerItems(@NotNull String professionId) {
        List<ItemStack> list = observedVillagerResults.getOrDefault(professionId.toLowerCase(Locale.ROOT), List.of());
        List<ItemStack> out = new ArrayList<>(list.size());
        for (ItemStack stack : list) {
            out.add(stack.clone());
        }
        return out;
    }

    private void applyRules(@NotNull String targetKey, @NotNull Inventory inventory) {
        StructureLootProfile profile = store.profile(targetKey);
        List<LootRule> remove = new ArrayList<>();
        List<LootRule> custom = new ArrayList<>();
        splitRules(profile.rules(), remove, custom);

        if (!remove.isEmpty()) {
            ItemStack[] contents = inventory.getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                if (matchesAny(remove, stack)) {
                    contents[i] = null;
                }
            }
            inventory.setContents(contents);
        }

        for (LootRule rule : custom) {
            if (!rollFailed(rule.chance())) {
                inventory.addItem(rule.item().clone());
            }
        }
    }

    private void applyRules(@NotNull String targetKey, @NotNull List<ItemStack> loot) {
        StructureLootProfile profile = store.profile(targetKey);
        List<LootRule> remove = new ArrayList<>();
        List<LootRule> custom = new ArrayList<>();
        splitRules(profile.rules(), remove, custom);

        if (!remove.isEmpty()) {
            loot.removeIf(stack -> stack != null && !stack.getType().isAir() && matchesAny(remove, stack));
        }

        for (LootRule rule : custom) {
            if (!rollFailed(rule.chance())) {
                loot.add(rule.item().clone());
            }
        }
    }

    private void splitRules(@NotNull List<LootRule> all,
                            @NotNull List<LootRule> remove,
                            @NotNull List<LootRule> custom) {
        for (LootRule rule : all) {
            if (rule.type() == LootRuleType.REMOVE) {
                remove.add(rule);
            } else if (rule.type() == LootRuleType.CUSTOM) {
                custom.add(rule);
            }
        }
    }

    private boolean matchesAny(@NotNull List<LootRule> remove, @NotNull ItemStack stack) {
        for (LootRule rule : remove) {
            if (similar(rule.item(), stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean similar(@NotNull ItemStack left, @NotNull ItemStack right) {
        if (!left.hasItemMeta()) {
            return left.getType() == right.getType();
        }
        ItemStack leftCopy = left.clone();
        leftCopy.setAmount(1);
        ItemStack rightCopy = right.clone();
        rightCopy.setAmount(1);
        return leftCopy.isSimilar(rightCopy);
    }

    private boolean rollFailed(double chance) {
        double safe = Math.max(0.0D, Math.min(100.0D, chance));
        return ThreadLocalRandom.current().nextDouble(100.0D) > safe;
    }


    private @Nullable String detectConfiguredStructureTargetKey(@NotNull Block block) {
        List<String> keys = store.configuredTargetKeys(TargetType.STRUCTURE);
        if (keys.isEmpty()) {
            return null;
        }
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            NamespacedKey namespacedKey = NamespacedKey.fromString(TargetKey.idOf(key));
            if (namespacedKey == null) {
                continue;
            }
            Structure structure = Registry.STRUCTURE.get(namespacedKey);
            if (structure == null) {
                continue;
            }
            if (isInsideStructure(block, structure)) {
                return key;
            }
        }
        return null;
    }

    private boolean isInsideStructure(@NotNull Block block, @NotNull Structure structure) {
        World world = block.getWorld();
        int chunkX = block.getChunk().getX();
        int chunkZ = block.getChunk().getZ();
        for (GeneratedStructure generated : world.getStructures(chunkX, chunkZ, structure)) {
            if (generated.getBoundingBox().contains(block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D)) {
                return true;
            }
        }
        return false;
    }
}
