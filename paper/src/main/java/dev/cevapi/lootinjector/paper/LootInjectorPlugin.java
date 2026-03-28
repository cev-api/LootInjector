package dev.cevapi.lootinjector.paper;

import dev.cevapi.lootinjector.common.TargetKey;
import dev.cevapi.lootinjector.common.TargetType;
import dev.cevapi.lootinjector.common.SearchUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LootInjectorPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final List<String> VIRTUAL_STRUCTURE_IDS = List.of(
            "minecraft:trial_chambers_vault",
            "minecraft:trial_chambers_ominous_vault"
    );

    private LootProfileStore profileStore;
    private LootInjectorMenuService menuService;
    private StructureRuntimeInjector runtimeInjector;
    private StructureBaseLootCatalog baseLootCatalog;
    private NamespacedKey processedChestKey;
    private NamespacedKey playerPlacedContainerKey;
    private NamespacedKey villagerEmeraldCostKey;
    private NamespacedKey villagerSpawnChanceKey;
    private NamespacedKey villagerLevelKey;

    @Override
    public void onEnable() {
        this.processedChestKey = new NamespacedKey(this, "processed_chest");
        this.playerPlacedContainerKey = new NamespacedKey(this, "player_placed_container");
        this.villagerEmeraldCostKey = new NamespacedKey(this, "villager_emerald_cost");
        this.villagerSpawnChanceKey = new NamespacedKey(this, "villager_spawn_chance");
        this.villagerLevelKey = new NamespacedKey(this, "villager_trade_level");
        this.profileStore = new LootProfileStore(this);
        this.profileStore.load();
        this.baseLootCatalog = new StructureBaseLootCatalog(this);
        this.baseLootCatalog.reload();
        this.menuService = new LootInjectorMenuService(this, profileStore, baseLootCatalog);
        this.runtimeInjector = new StructureRuntimeInjector(this, profileStore, processedChestKey, playerPlacedContainerKey);

        if (getCommand("lootinjector") != null) {
            getCommand("lootinjector").setExecutor(this);
            getCommand("lootinjector").setTabCompleter(this);
        }

        Bukkit.getPluginManager().registerEvents(menuService, this);
        Bukkit.getPluginManager().registerEvents(runtimeInjector, this);
        runtimeInjector.captureExistingVillagerCatalog();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lootinjector.admin")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Run this as a player: /lootinjector", NamedTextColor.RED));
                return true;
            }
            menuService.openNamespaceList(player, 0);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                profileStore.load();
                baseLootCatalog.reload();
                sender.sendMessage(Component.text("LootInjector config reloaded.", NamedTextColor.GREEN));
                return true;
            }
            case "search" -> {
                return handleSearch(sender, args);
            }
            case "add" -> {
                return handleAdd(sender, args);
            }
            case "addhand" -> {
                return handleAddHand(sender, args);
            }
            case "block" -> {
                return handleBlock(sender, args);
            }
            case "blockhand" -> {
                return handleBlockHand(sender, args);
            }
            case "open" -> {
                return handleOpen(sender, args);
            }
            case "clear" -> {
                return handleClear(sender, args);
            }
            case "debugbase" -> {
                return handleDebugBase(sender, args);
            }
            case "debughere" -> {
                return handleDebugHere(sender);
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /lootinjector [gui|reload|search|add|addhand|block|blockhand|open|clear|debugbase|debughere]", NamedTextColor.RED));
                return true;
            }
        }
    }

    private boolean handleOpen(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /lootinjector open <structure|loot_table|mob|villager|enchant_table> <id>", NamedTextColor.RED));
            return true;
        }
        TargetType type = TargetType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Use structure|loot_table|mob|villager|enchant_table", NamedTextColor.RED));
            return true;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (type == TargetType.STRUCTURE) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Run this as a player to open GUI.", NamedTextColor.RED));
                return true;
            }
            menuService.openStructureList(player, 0, LootInjectorMenuService.StructureListMode.ALL);
            return true;
        }
        String targetKey = TargetKey.of(type, id);
        int count = profileStore.profile(targetKey).rules().size();
        sender.sendMessage(Component.text("Target " + targetKey + " has " + count + " rule(s). Use add/addhand/search to edit.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleClear(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /lootinjector clear <structure|loot_table|mob|villager|enchant_table> <id>", NamedTextColor.RED));
            return true;
        }
        TargetType type = TargetType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Use structure|loot_table|mob|villager|enchant_table", NamedTextColor.RED));
            return true;
        }
        String targetKey = TargetKey.of(type, args[2]);
        profileStore.clearRules(targetKey);
        sender.sendMessage(Component.text("Cleared rules for " + targetKey, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleSearch(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /lootinjector search <structure|loot_table|mob|villager|enchant_table> <query>", NamedTextColor.RED));
            return true;
        }
        TargetType type = TargetType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Use structure|loot_table|mob|villager|enchant_table", NamedTextColor.RED));
            return true;
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        List<String> matches = switch (type) {
            case STRUCTURE -> searchStructures(query);
            case LOOT_TABLE -> searchLootTables(query);
            case MOB -> searchMobs(query);
            case VILLAGER -> searchVillagers(query);
            case ENCHANT_TABLE -> searchEnchantTargets(query);
        };
        sender.sendMessage(Component.text("Matches (" + matches.size() + "):", NamedTextColor.AQUA));
        for (int i = 0; i < Math.min(20, matches.size()); i++) {
            sender.sendMessage(Component.text(" - " + matches.get(i), NamedTextColor.GRAY));
        }
        if (matches.size() > 20) {
            sender.sendMessage(Component.text("...and " + (matches.size() - 20) + " more", NamedTextColor.DARK_GRAY));
        }
        return true;
    }

    private boolean handleAdd(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text("Usage: /lootinjector add <structure|loot_table|mob|villager|enchant_table> <id> <chance> <item_id> [nbt]", NamedTextColor.RED));
            return true;
        }
        TargetType type = TargetType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Use structure|loot_table|mob|villager|enchant_table", NamedTextColor.RED));
            return true;
        }
        String targetKey = TargetKey.of(type, args[2]);
        Double chance = parseChance(args[3]);
        if (chance == null) {
            sender.sendMessage(Component.text("Invalid chance: " + args[3], NamedTextColor.RED));
            return true;
        }

        ItemStack stack = parseStackFromArgs(sender, args, 4);
        if (stack == null) {
            return true;
        }
        if (type == TargetType.ENCHANT_TABLE && !isBookType(stack.getType())) {
            sender.sendMessage(Component.text("Enchant table injections only accept book items.", NamedTextColor.RED));
            return true;
        }
        if (type == TargetType.VILLAGER) {
            stack = withVillagerRuleMeta(stack, chance, 1, 1);
        }

        profileStore.addRule(targetKey, LootRuleType.CUSTOM, chance, stack);
        sender.sendMessage(Component.text("Added custom rule to " + targetKey, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleAddHand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /lootinjector addhand <structure|loot_table|mob|villager|enchant_table> <id> <chance>", NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Run this as a player.", NamedTextColor.RED));
            return true;
        }
        TargetType type = TargetType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Use structure|loot_table|mob|villager|enchant_table", NamedTextColor.RED));
            return true;
        }
        String targetKey = TargetKey.of(type, args[2]);
        Double chance = parseChance(args[3]);
        if (chance == null) {
            sender.sendMessage(Component.text("Invalid chance: " + args[3], NamedTextColor.RED));
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            sender.sendMessage(Component.text("Hold an item in your main hand.", NamedTextColor.RED));
            return true;
        }
        if (type == TargetType.ENCHANT_TABLE && !isBookType(hand.getType())) {
            sender.sendMessage(Component.text("Enchant table injections only accept book items.", NamedTextColor.RED));
            return true;
        }
        ItemStack toAdd = hand.clone();
        if (type == TargetType.VILLAGER) {
            toAdd = withVillagerRuleMeta(toAdd, chance, 1, 1);
        }

        profileStore.addRule(targetKey, LootRuleType.CUSTOM, chance, toAdd);
        sender.sendMessage(Component.text("Added main-hand item to " + targetKey, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleBlock(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /lootinjector block <structure|loot_table|mob|villager|enchant_table> <id> <item_id> [nbt]", NamedTextColor.RED));
            return true;
        }
        TargetType type = TargetType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Use structure|loot_table|mob|villager|enchant_table", NamedTextColor.RED));
            return true;
        }
        String targetKey = TargetKey.of(type, args[2]);
        ItemStack stack = parseStackFromArgs(sender, args, 3);
        if (stack == null) {
            return true;
        }
        if (type == TargetType.ENCHANT_TABLE && !isBookType(stack.getType())) {
            sender.sendMessage(Component.text("Enchant table injections only accept book items.", NamedTextColor.RED));
            return true;
        }
        profileStore.addRule(targetKey, LootRuleType.REMOVE, 100.0D, stack);
        sender.sendMessage(Component.text("Added remove/block rule to " + targetKey, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleBlockHand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /lootinjector blockhand <structure|loot_table|mob|villager|enchant_table> <id>", NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Run this as a player.", NamedTextColor.RED));
            return true;
        }
        TargetType type = TargetType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Use structure|loot_table|mob|villager|enchant_table", NamedTextColor.RED));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            sender.sendMessage(Component.text("Hold an item in your main hand.", NamedTextColor.RED));
            return true;
        }
        if (type == TargetType.ENCHANT_TABLE && !isBookType(hand.getType())) {
            sender.sendMessage(Component.text("Enchant table injections only accept book items.", NamedTextColor.RED));
            return true;
        }
        String targetKey = TargetKey.of(type, args[2]);
        profileStore.addRule(targetKey, LootRuleType.REMOVE, 100.0D, hand.clone());
        sender.sendMessage(Component.text("Added remove/block rule from main-hand item to " + targetKey, NamedTextColor.GREEN));
        return true;
    }

    private @Nullable ItemStack parseStackFromArgs(@NotNull CommandSender sender, @NotNull String[] args, int materialArgIndex) {
        Material material = Material.matchMaterial(args[materialArgIndex], true);
        if (material == null || material.isAir()) {
            sender.sendMessage(Component.text("Unknown item_id/material: " + args[materialArgIndex], NamedTextColor.RED));
            return null;
        }

        ItemStack stack = new ItemStack(material);
        if (args.length > materialArgIndex + 1) {
            String nbt = String.join(" ", java.util.Arrays.copyOfRange(args, materialArgIndex + 1, args.length));
            try {
                stack = Bukkit.getUnsafe().modifyItemStack(stack, nbt);
            } catch (Throwable throwable) {
                sender.sendMessage(Component.text("NBT parse failed: " + throwable.getMessage(), NamedTextColor.RED));
                return null;
            }
        }
        return stack;
    }

    private @Nullable Double parseChance(@NotNull String raw) {
        try {
            double parsed = Double.parseDouble(raw);
            return Math.max(0.0D, Math.min(100.0D, parsed));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public @NotNull List<ItemStack> observedVillagerItems(@NotNull String professionId) {
        if (runtimeInjector == null) {
            return List.of();
        }
        return runtimeInjector.observedVillagerItems(professionId);
    }

    private boolean isBookType(@NotNull Material material) {
        return material == Material.BOOK
                || material == Material.ENCHANTED_BOOK
                || material == Material.WRITTEN_BOOK
                || material == Material.WRITABLE_BOOK;
    }

    private @NotNull ItemStack withVillagerRuleMeta(@NotNull ItemStack source, double spawnChance, int emeraldCost, int level) {
        ItemStack out = source.clone();
        var meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        meta.getPersistentDataContainer().set(villagerSpawnChanceKey, org.bukkit.persistence.PersistentDataType.DOUBLE, Math.max(0.0D, Math.min(100.0D, spawnChance)));
        meta.getPersistentDataContainer().set(villagerEmeraldCostKey, org.bukkit.persistence.PersistentDataType.INTEGER, Math.max(1, Math.min(64, emeraldCost)));
        meta.getPersistentDataContainer().set(villagerLevelKey, org.bukkit.persistence.PersistentDataType.INTEGER, Math.max(1, Math.min(5, level)));
        out.setItemMeta(meta);
        return out;
    }


    private boolean handleDebugBase(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /lootinjector debugbase <structure_id>", NamedTextColor.RED));
            return true;
        }
        String structureId = args[1].toLowerCase(Locale.ROOT);
        List<String> roots = baseLootCatalog.debugRootsForStructure(structureId);
        List<ItemStack> base = baseLootCatalog.baseItemsForStructure(structureId);
        sender.sendMessage(Component.text(
                "Base catalog: files=" + baseLootCatalog.scannedLootTableFileCount()
                        + ", tables=" + baseLootCatalog.scannedLootTableCount()
                        + ", roots=" + roots.size()
                        + ", matched_items=" + base.size(),
                NamedTextColor.AQUA
        ));
        for (int i = 0; i < roots.size(); i++) {
            sender.sendMessage(Component.text(" root: " + roots.get(i), NamedTextColor.DARK_GRAY));
        }
        for (int i = 0; i < base.size(); i++) {
            sender.sendMessage(Component.text(" - " + base.get(i).getType().name().toLowerCase(Locale.ROOT), NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean handleDebugHere(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Run this as a player.", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("DebugHere @ " + player.getWorld().getName()
                + " [" + player.getLocation().getBlockX()
                + ", " + player.getLocation().getBlockY()
                + ", " + player.getLocation().getBlockZ() + "]", NamedTextColor.AQUA));

        Set<String> chunkStructures = structuresFromCurrentChunk(player);
        if (chunkStructures.isEmpty()) {
            sender.sendMessage(Component.text("Chunk structures: none detected via chunk metadata", NamedTextColor.DARK_GRAY));
        } else {
            sender.sendMessage(Component.text("Chunk structures (" + chunkStructures.size() + "):", NamedTextColor.YELLOW));
            for (String structureId : chunkStructures) {
                sender.sendMessage(Component.text(" - official: " + structureId, NamedTextColor.GRAY));
                sender.sendMessage(Component.text("   inject key: " + TargetKey.of(TargetType.STRUCTURE, structureId), NamedTextColor.DARK_GRAY));
            }
        }

        List<String> nearbyMobs = nearbyMobTypes(player, 64.0D);
        if (nearbyMobs.isEmpty()) {
            sender.sendMessage(Component.text("Area mobs: none currently loaded nearby", NamedTextColor.DARK_GRAY));
        } else {
            sender.sendMessage(Component.text("Area mobs (observed nearby): " + nearbyMobs.size(), NamedTextColor.YELLOW));
            for (int i = 0; i < nearbyMobs.size(); i++) {
                sender.sendMessage(Component.text(" - " + nearbyMobs.get(i), NamedTextColor.GRAY));
            }
        }

        Block block = player.getTargetBlockExact(12, FluidCollisionMode.NEVER);
        if (block == null) {
            RayTraceResult hit = player.rayTraceBlocks(12.0D, FluidCollisionMode.NEVER);
            if (hit != null) {
                block = hit.getHitBlock();
            }
        }
        if (block == null) {
            sender.sendMessage(Component.text("Facing container: none (look at chest/barrel/etc within 12 blocks)", NamedTextColor.DARK_GRAY));
            return true;
        }
        BlockState state = block.getState();
        if (!(state instanceof Lootable lootable)) {
            sender.sendMessage(Component.text("Facing block is not lootable: " + block.getType().name().toLowerCase(Locale.ROOT), NamedTextColor.DARK_GRAY));
            return true;
        }

        LootTable table = lootable.getLootTable();
        if (table == null || table.getKey() == null) {
            sender.sendMessage(Component.text("Facing lootable has no loot table assigned.", NamedTextColor.DARK_GRAY));
            sender.sendMessage(Component.text("This usually means the chest already rolled its loot and consumed the table pointer.", NamedTextColor.DARK_GRAY));
            if (!chunkStructures.isEmpty()) {
                sender.sendMessage(Component.text("Likely loot tables from current chunk structures:", NamedTextColor.YELLOW));
                Set<String> seen = new LinkedHashSet<>();
                for (String structureId : chunkStructures) {
                    for (String root : baseLootCatalog.rootsForStructure(structureId)) {
                        if (seen.add(root)) {
                            sender.sendMessage(Component.text(" - " + root + " (from " + structureId + ")", NamedTextColor.GRAY));
                        }
                    }
                }
            }
            return true;
        }

        String tableId = table.getKey().toString().toLowerCase(Locale.ROOT);
        sender.sendMessage(Component.text("Facing loot table (official): " + tableId, NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Inject key (loot_table): " + TargetKey.of(TargetType.LOOT_TABLE, tableId), NamedTextColor.YELLOW));

        Set<String> aliases = structureAliasesForLootTable(tableId);
        if (aliases.isEmpty()) {
            sender.sendMessage(Component.text("Inject structure aliases: none inferred", NamedTextColor.DARK_GRAY));
        } else {
            sender.sendMessage(Component.text("Inject structure aliases:", NamedTextColor.YELLOW));
            for (String alias : aliases) {
                sender.sendMessage(Component.text(" - official: " + alias, NamedTextColor.GRAY));
                sender.sendMessage(Component.text("   inject key: " + TargetKey.of(TargetType.STRUCTURE, alias), NamedTextColor.DARK_GRAY));
            }
        }

        List<ItemStack> expected = baseLootCatalog.baseItemsForLootTable(tableId);
        sender.sendMessage(Component.text("Expected base items from table: " + expected.size(), NamedTextColor.AQUA));
        for (int i = 0; i < expected.size(); i++) {
            ItemStack stack = expected.get(i);
            sender.sendMessage(Component.text(" - " + stack.getType().name().toLowerCase(Locale.ROOT), NamedTextColor.GRAY));
        }

        return true;
    }

    private @NotNull Set<String> structuresFromCurrentChunk(@NotNull Player player) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Object chunk = player.getLocation().getChunk();
            Method getStructures = chunk.getClass().getMethod("getStructures");
            Object raw = getStructures.invoke(chunk);
            if (!(raw instanceof Iterable<?> iterable)) {
                return out;
            }
            for (Object generated : iterable) {
                if (generated == null) {
                    continue;
                }
                Object structureObj = null;
                try {
                    Method getStructure = generated.getClass().getMethod("getStructure");
                    structureObj = getStructure.invoke(generated);
                } catch (Throwable ignored) {
                }
                if (structureObj == null) {
                    continue;
                }
                Object keyObj = null;
                try {
                    Method getKey = structureObj.getClass().getMethod("getKey");
                    keyObj = getKey.invoke(structureObj);
                } catch (Throwable ignored) {
                }
                if (keyObj != null) {
                    out.add(keyObj.toString().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private @NotNull List<String> nearbyMobTypes(@NotNull Player player, double radius) {
        Set<String> out = new LinkedHashSet<>();
        for (var entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living instanceof Player) {
                continue;
            }
            EntityType type = living.getType();
            if (!type.isAlive() || type.getKey() == null) {
                continue;
            }
            out.add(type.getKey().toString().toLowerCase(Locale.ROOT));
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private @NotNull Set<String> structureAliasesForLootTable(@NotNull String lootTableId) {
        Set<String> out = new LinkedHashSet<>();
        String normalized = lootTableId.toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        if (colon <= 0 || colon + 1 >= normalized.length()) {
            return out;
        }
        String namespace = normalized.substring(0, colon);
        String path = normalized.substring(colon + 1);
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
        if (leaf == null || leaf.isBlank()) {
            return;
        }
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
        if (tokens.length >= 2) out.add(namespace + ":" + tokens[0] + "_" + tokens[1]);
        if (tokens.length >= 3) out.add(namespace + ":" + tokens[0] + "_" + tokens[1] + "_" + tokens[2]);
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

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("gui", "reload", "search", "add", "addhand", "block", "blockhand", "open", "clear", "debugbase", "debughere"), args[0]);
        }
        if (args.length == 2 && List.of("search", "add", "addhand", "block", "blockhand", "open", "clear").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(List.of("structure", "loot_table", "mob", "villager", "enchant_table"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debugbase")) {
            return filter(searchStructures(""), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("search")) {
            TargetType type = TargetType.fromString(args[1]);
            if (type == null) {
                return List.of();
            }
            return switch (type) {
                case STRUCTURE -> filter(searchStructures(""), args[2]);
                case LOOT_TABLE -> filter(searchLootTables(""), args[2]);
                case MOB -> filter(searchMobs(""), args[2]);
                case VILLAGER -> filter(searchVillagers(""), args[2]);
                case ENCHANT_TABLE -> filter(searchEnchantTargets(""), args[2]);
            };
        }
        return List.of();
    }

    private static @NotNull List<String> filter(@NotNull List<String> base, @NotNull String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String entry : base) {
            if (entry.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                out.add(entry);
            }
        }
        return out;
    }

    public @NotNull List<Structure> allStructures() {
        List<Structure> structures = new ArrayList<>();
        for (Structure structure : Registry.STRUCTURE) {
            structures.add(structure);
        }
        structures.sort(Comparator.comparing(s -> s.getKey().toString(), String.CASE_INSENSITIVE_ORDER));
        return structures;
    }

    private @NotNull List<String> searchStructures(@NotNull String query) {
        List<String> out = new ArrayList<>();
        for (Structure structure : Registry.STRUCTURE) {
            String key = structure.getKey().toString();
            if (SearchUtil.contains(key, query)) {
                out.add(key);
            }
        }
        for (String key : VIRTUAL_STRUCTURE_IDS) {
            if (SearchUtil.contains(key, query)) {
                out.add(key);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private @NotNull List<String> searchLootTables(@NotNull String query) {
        List<String> out = new ArrayList<>();
        for (String configured : profileStore.configuredTargetKeys(TargetType.LOOT_TABLE)) {
            String key = TargetKey.idOf(configured);
            if (SearchUtil.contains(key, query)) {
                out.add(key);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private @NotNull List<String> searchMobs(@NotNull String query) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (EntityType type : EntityType.values()) {
            if (!type.isAlive() || type.getKey() == null) {
                continue;
            }
            String key = type.getKey().toString();
            if (SearchUtil.contains(key, query)) {
                out.add(key);
            }
        }
        for (String id : baseLootCatalog.knownMobIdsFromLootTables()) {
            if (SearchUtil.contains(id, query)) {
                out.add(id);
            }
        }
        for (String configured : profileStore.configuredTargetKeys(TargetType.MOB)) {
            String id = TargetKey.idOf(configured);
            if (SearchUtil.contains(id, query)) {
                out.add(id);
            }
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private @NotNull List<String> searchVillagers(@NotNull String query) {
        List<String> out = new ArrayList<>();
        for (org.bukkit.entity.Villager.Profession profession : org.bukkit.entity.Villager.Profession.values()) {
            if (profession == org.bukkit.entity.Villager.Profession.NONE) {
                continue;
            }
            String id = profession.name().toLowerCase(Locale.ROOT);
            if (SearchUtil.contains(id, query)) {
                out.add(id);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private @NotNull List<String> searchEnchantTargets(@NotNull String query) {
        List<String> out = new ArrayList<>();
        if (SearchUtil.contains("book", query)) {
            out.add("book");
        }
        return out;
    }
}
