package dev.cevapi.lootinjector.paper;

import dev.cevapi.lootinjector.common.TargetKey;
import dev.cevapi.lootinjector.common.TargetType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LootInjectorMenuService implements Listener {
    public enum StructureListMode { ALL, CONFIGURED }
    private static final List<String> VILLAGER_PROFESSIONS = List.of(
            "armorer", "butcher", "cartographer", "cleric", "farmer",
            "fisherman", "fletcher", "leatherworker", "librarian",
            "mason", "shepherd", "toolsmith", "weaponsmith"
    );

    private static final List<String> VIRTUAL_STRUCTURE_IDS = List.of(
            "minecraft:trial_chambers_vault",
            "minecraft:trial_chambers_ominous_vault"
    );
    private static final int MENU_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final int SOURCE_SPECIAL_COUNT = 3;

    private final LootInjectorPlugin plugin;
    private final LootProfileStore store;
    private final StructureBaseLootCatalog baseLootCatalog;
    private final NamespacedKey enchantXpCostKey;
    private final NamespacedKey villagerEmeraldCostKey;
    private final NamespacedKey villagerSpawnChanceKey;
    private final NamespacedKey villagerLevelKey;

    public LootInjectorMenuService(@NotNull LootInjectorPlugin plugin,
                                   @NotNull LootProfileStore store,
                                   @NotNull StructureBaseLootCatalog baseLootCatalog) {
        this.plugin = plugin;
        this.store = store;
        this.baseLootCatalog = baseLootCatalog;
        this.enchantXpCostKey = new NamespacedKey(plugin, "enchant_xp_cost");
        this.villagerEmeraldCostKey = new NamespacedKey(plugin, "villager_emerald_cost");
        this.villagerSpawnChanceKey = new NamespacedKey(plugin, "villager_spawn_chance");
        this.villagerLevelKey = new NamespacedKey(plugin, "villager_trade_level");
    }

    public void openNamespaceList(@NotNull Player player, int page) {
        List<String> namespaces = allNamespaces();
        int namespacePageSize = PAGE_SIZE - SOURCE_SPECIAL_COUNT;
        int totalPages = Math.max(1, (namespaces.size() + namespacePageSize - 1) / namespacePageSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inventory = Bukkit.createInventory(new NamespaceListHolder(safePage), MENU_SIZE,
                Component.text("LootInjector Sources [" + (safePage + 1) + "/" + totalPages + "]", NamedTextColor.AQUA));

        inventory.setItem(0, specialItem("Villagers", Material.EMERALD));
        inventory.setItem(1, specialItem("Enchant Table", Material.ENCHANTING_TABLE));
        inventory.setItem(2, specialItem("Mob Drops", Material.SPAWNER));

        int start = safePage * namespacePageSize;
        int end = Math.min(namespaces.size(), start + namespacePageSize);
        int slot = 3;
        for (int i = start; i < end; i++) {
            inventory.setItem(slot++, namespaceItem(namespaces.get(i)));
        }
        inventory.setItem(45, navItem("<< Prev", safePage > 0));
        inventory.setItem(49, infoItem("Pick Vanilla or datapack namespace"));
        inventory.setItem(53, navItem("Next >>", safePage + 1 < totalPages));
        player.openInventory(inventory);
    }

    public void openStructureList(@NotNull Player player, int page, @NotNull StructureListMode mode) {
        openStructureList(player, page, mode, null, null);
    }

    private void openStructureList(@NotNull Player player, int page, @NotNull StructureListMode mode, String namespaceFilter) {
        openStructureList(player, page, mode, namespaceFilter, null);
    }

    private void openStructureList(@NotNull Player player, int page, @NotNull StructureListMode mode, String namespaceFilter, String groupFilter) {
        List<String> structures = allStructureIds(namespaceFilter, groupFilter);
        if (mode == StructureListMode.CONFIGURED) {
            structures.removeIf(id -> !store.hasProfile(TargetKey.of(TargetType.STRUCTURE, id)));
        }
        int totalPages = Math.max(1, (structures.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        String title = namespaceFilter == null ? "LootInjector Structures" : ("LootInjector " + namespaceFilter);
        if (groupFilter != null && !"*".equals(groupFilter)) {
            title += "/" + ("__direct__".equals(groupFilter) ? "direct" : groupFilter);
        }

        Inventory inventory = Bukkit.createInventory(new StructureListHolder(safePage, mode, namespaceFilter, groupFilter), MENU_SIZE,
                Component.text(title + " [" + (safePage + 1) + "/" + totalPages + "]", NamedTextColor.AQUA));

        int start = safePage * PAGE_SIZE;
        int end = Math.min(structures.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int i = start; i < end; i++) {
            inventory.setItem(slot++, structureItem(structures.get(i)));
        }

        inventory.setItem(45, navItem("<< Prev", safePage > 0));
        inventory.setItem(46, backItem());
        inventory.setItem(47, modeSwitchItem(mode));
        inventory.setItem(49, infoItem("Click structure to edit"));
        inventory.setItem(53, navItem("Next >>", safePage + 1 < totalPages));
        player.openInventory(inventory);
    }

    private void openStructureGroupList(@NotNull Player player, int page, @NotNull StructureListMode mode, @NotNull String namespaceFilter) {
        List<String> groups = structureGroups(namespaceFilter, mode);
        int totalPages = Math.max(1, (groups.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inventory = Bukkit.createInventory(new StructureGroupListHolder(safePage, mode, namespaceFilter), MENU_SIZE,
                Component.text("LootInjector " + namespaceFilter + " Groups [" + (safePage + 1) + "/" + totalPages + "]", NamedTextColor.AQUA));

        int start = safePage * PAGE_SIZE;
        int end = Math.min(groups.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int i = start; i < end; i++) {
            inventory.setItem(slot++, structureGroupItem(groups.get(i)));
        }
        inventory.setItem(45, navItem("<< Prev", safePage > 0));
        inventory.setItem(46, backItem());
        inventory.setItem(47, modeSwitchItem(mode));
        inventory.setItem(49, infoItem("Pick a structure group"));
        inventory.setItem(53, navItem("Next >>", safePage + 1 < totalPages));
        player.openInventory(inventory);
    }

    private void openStructureEditor(@NotNull Player player,
                                     @NotNull String structureId,
                                     int page,
                                     @NotNull String back) {
        openEditor(player, TargetType.STRUCTURE, structureId, page, back);
    }

    private void openVillagerList(@NotNull Player player, int page) {
        openVillagerList(player, page, StructureListMode.ALL);
    }

    private void openVillagerList(@NotNull Player player, int page, @NotNull StructureListMode mode) {
        List<String> professions = new ArrayList<>(VILLAGER_PROFESSIONS);
        if (mode == StructureListMode.CONFIGURED) {
            professions.removeIf(id -> !store.hasProfile(TargetKey.of(TargetType.VILLAGER, id)));
        }
        int totalPages = Math.max(1, (professions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        String title = mode == StructureListMode.ALL ? "Villager Professions" : "Villager Professions (Configured)";
        Inventory inventory = Bukkit.createInventory(new VillagerListHolder(safePage, mode), MENU_SIZE,
                Component.text(title + " [" + (safePage + 1) + "/" + totalPages + "]", NamedTextColor.AQUA));

        int start = safePage * PAGE_SIZE;
        int end = Math.min(professions.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int i = start; i < end; i++) {
            String profession = professions.get(i);
            inventory.setItem(slot++, plainItem(Material.EMERALD, profession, NamedTextColor.GREEN));
        }
        inventory.setItem(45, navItem("<< Prev", safePage > 0));
        inventory.setItem(46, backItem());
        inventory.setItem(47, modeSwitchItem(mode, "All Villagers", "Configured Only"));
        inventory.setItem(49, infoItem("Select villager profession to edit trades"));
        inventory.setItem(53, navItem("Next >>", safePage + 1 < totalPages));
        player.openInventory(inventory);
    }

    private void openVillagerEditor(@NotNull Player player, @NotNull String profession, int page, @NotNull StructureListMode mode) {
        openEditor(player, TargetType.VILLAGER, profession, page, "villager_list:" + mode.name());
    }

    private void openMobNamespaceList(@NotNull Player player, int page) {
        List<String> namespaces = allMobNamespaces();
        int totalPages = Math.max(1, (namespaces.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inventory = Bukkit.createInventory(new MobNamespaceListHolder(safePage), MENU_SIZE,
                Component.text("Mob Sources [" + (safePage + 1) + "/" + totalPages + "]", NamedTextColor.AQUA));

        int start = safePage * PAGE_SIZE;
        int end = Math.min(namespaces.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int i = start; i < end; i++) {
            inventory.setItem(slot++, namespaceItem(namespaces.get(i)));
        }
        inventory.setItem(45, navItem("<< Prev", safePage > 0));
        inventory.setItem(46, backItem());
        inventory.setItem(49, infoItem("Pick mob namespace"));
        inventory.setItem(53, navItem("Next >>", safePage + 1 < totalPages));
        player.openInventory(inventory);
    }

    private void openMobList(@NotNull Player player, int page) {
        openMobList(player, page, null, StructureListMode.ALL);
    }

    private void openMobList(@NotNull Player player, int page, String namespaceFilter) {
        openMobList(player, page, namespaceFilter, StructureListMode.ALL);
    }

    private void openMobList(@NotNull Player player, int page, String namespaceFilter, @NotNull StructureListMode mode) {
        List<String> mobs = allMobIds(namespaceFilter);
        if (mode == StructureListMode.CONFIGURED) {
            mobs.removeIf(id -> !store.hasProfile(TargetKey.of(TargetType.MOB, id)));
        }
        int totalPages = Math.max(1, (mobs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        String title = namespaceFilter == null ? "Mob Drops" : ("Mob Drops " + namespaceFilter);
        if (mode == StructureListMode.CONFIGURED) {
            title += " (Configured)";
        }

        Inventory inventory = Bukkit.createInventory(new MobListHolder(safePage, namespaceFilter, mode), MENU_SIZE,
                Component.text(title + " [" + (safePage + 1) + "/" + totalPages + "]", NamedTextColor.AQUA));

        int start = safePage * PAGE_SIZE;
        int end = Math.min(mobs.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int i = start; i < end; i++) {
            inventory.setItem(slot++, mobItem(mobs.get(i)));
        }
        inventory.setItem(45, navItem("<< Prev", safePage > 0));
        inventory.setItem(46, backItem());
        inventory.setItem(47, modeSwitchItem(mode, "All Mobs", "Configured Only"));
        inventory.setItem(49, infoItem("Select mob to edit drops"));
        inventory.setItem(53, navItem("Next >>", safePage + 1 < totalPages));
        player.openInventory(inventory);
    }

    private void openMobEditor(@NotNull Player player, @NotNull String mobId, int page, String namespaceFilter, @NotNull StructureListMode mode) {
        String back = "mob_list:" + (namespaceFilter == null ? "*" : namespaceFilter) + ":" + mode.name();
        openEditor(player, TargetType.MOB, mobId, page, back);
    }

    private void openEnchantEditor(@NotNull Player player, int page) {
        openEditor(player, TargetType.ENCHANT_TABLE, "book", page, "namespace");
    }

    private void openEditor(@NotNull Player player,
                            @NotNull TargetType type,
                            @NotNull String id,
                            int page,
                            @NotNull String back) {
        String targetKey = TargetKey.of(type, id);
        StructureLootProfile profile = store.profile(targetKey);
        List<EditorEntry> entries = editorEntries(type, id, profile);
        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        String title = switch (type) {
            case STRUCTURE -> id;
            case MOB -> "mob:" + id;
            case VILLAGER -> "villager:" + id;
            case ENCHANT_TABLE -> "enchant_table:" + id;
            default -> id;
        };

        Inventory inventory = Bukkit.createInventory(new StructureEditorHolder(targetKey, safePage, back), MENU_SIZE,
                Component.text(shorten(title, 32) + " [" + (safePage + 1) + "/" + totalPages + "]", NamedTextColor.LIGHT_PURPLE));

        int start = safePage * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int i = start; i < end; i++) {
            EditorEntry e = entries.get(i);
            inventory.setItem(slot++, e.base ? e.display : ruleItem(e.rule, type));
        }

        inventory.setItem(45, navItem("<< Prev", safePage > 0));
        inventory.setItem(46, backItem());
        inventory.setItem(47, addItemPane());
        inventory.setItem(48, blockItemPane());
        if (type == TargetType.VILLAGER) {
            inventory.setItem(49, infoItem("L/R chance +/-1 (Shift +/-10), Q/Ctrl+Q emerald -/+1, F cycles level 1..5"));
        } else if (type == TargetType.ENCHANT_TABLE) {
            inventory.setItem(49, infoItem("Left/Right chance +/-10, Shift+Left/Right XP +/-10, Middle delete"));
        } else {
            inventory.setItem(49, infoItem("Left/Right +/- chance, Shift +/-10, Middle delete"));
        }
        inventory.setItem(51, clearItem());
        inventory.setItem(53, navItem("Next >>", safePage + 1 < totalPages));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof NamespaceListHolder h) handleNamespaceClick(event, player, h);
        else if (top.getHolder() instanceof StructureGroupListHolder h) handleStructureGroupListClick(event, player, h);
        else if (top.getHolder() instanceof StructureListHolder h) handleStructureListClick(event, player, h);
        else if (top.getHolder() instanceof VillagerListHolder h) handleVillagerListClick(event, player, h);
        else if (top.getHolder() instanceof MobNamespaceListHolder h) handleMobNamespaceListClick(event, player, h);
        else if (top.getHolder() instanceof MobListHolder h) handleMobListClick(event, player, h);
        else if (top.getHolder() instanceof StructureEditorHolder h) handleEditorClick(event, player, h);
    }

    @EventHandler
    public void onDrag(@NotNull InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof NamespaceListHolder
                || top.getHolder() instanceof StructureGroupListHolder
                || top.getHolder() instanceof StructureListHolder
                || top.getHolder() instanceof VillagerListHolder
                || top.getHolder() instanceof MobNamespaceListHolder
                || top.getHolder() instanceof MobListHolder
                || top.getHolder() instanceof StructureEditorHolder)) return;
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) { event.setCancelled(true); return; }
        }
    }

    private void handleNamespaceClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull NamespaceListHolder holder) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        List<String> namespaces = allNamespaces();
        int namespacePageSize = PAGE_SIZE - SOURCE_SPECIAL_COUNT;
        int totalPages = Math.max(1, (namespaces.size() + namespacePageSize - 1) / namespacePageSize);
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { openNamespaceList(player, holder.page - 1); return; }
        if (slot == 53 && holder.page + 1 < totalPages) { openNamespaceList(player, holder.page + 1); return; }
        if (slot == 0) { openVillagerList(player, 0, StructureListMode.ALL); return; }
        if (slot == 1) { openEnchantEditor(player, 0); return; }
        if (slot == 2) {
            if (hasOnlyVanillaMobNamespace()) {
                openMobList(player, 0, "minecraft", StructureListMode.ALL);
            } else {
                openMobNamespaceList(player, 0);
            }
            return;
        }
        if (slot < 3 || slot >= PAGE_SIZE) return;
        int idx = holder.page * namespacePageSize + (slot - 3);
        if (idx < 0 || idx >= namespaces.size()) return;
        String namespace = namespaces.get(idx);
        if (hasStructureSubgroups(namespace)) {
            openStructureGroupList(player, 0, StructureListMode.ALL, namespace);
        } else {
            openStructureList(player, 0, StructureListMode.ALL, namespace, null);
        }
    }

    private void handleStructureGroupListClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull StructureGroupListHolder holder) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        List<String> groups = structureGroups(holder.namespaceFilter, holder.mode);
        int totalPages = Math.max(1, (groups.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { openStructureGroupList(player, holder.page - 1, holder.mode, holder.namespaceFilter); return; }
        if (slot == 53 && holder.page + 1 < totalPages) { openStructureGroupList(player, holder.page + 1, holder.mode, holder.namespaceFilter); return; }
        if (slot == 46) { openNamespaceList(player, 0); return; }
        if (slot == 47) {
            StructureListMode next = holder.mode == StructureListMode.ALL ? StructureListMode.CONFIGURED : StructureListMode.ALL;
            openStructureGroupList(player, 0, next, holder.namespaceFilter);
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        int idx = holder.page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= groups.size()) return;
        String group = groups.get(idx);
        openStructureList(player, 0, holder.mode, holder.namespaceFilter, group);
    }

    private void handleMobNamespaceListClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull MobNamespaceListHolder holder) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        List<String> namespaces = allMobNamespaces();
        int totalPages = Math.max(1, (namespaces.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { openMobNamespaceList(player, holder.page - 1); return; }
        if (slot == 53 && holder.page + 1 < totalPages) { openMobNamespaceList(player, holder.page + 1); return; }
        if (slot == 46) { openNamespaceList(player, 0); return; }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        int idx = holder.page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= namespaces.size()) return;
        openMobList(player, 0, namespaces.get(idx), StructureListMode.ALL);
    }

    private void handleVillagerListClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull VillagerListHolder holder) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        List<String> professions = new ArrayList<>(VILLAGER_PROFESSIONS);
        if (holder.mode == StructureListMode.CONFIGURED) {
            professions.removeIf(id -> !store.hasProfile(TargetKey.of(TargetType.VILLAGER, id)));
        }
        int totalPages = Math.max(1, (professions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { openVillagerList(player, holder.page - 1, holder.mode); return; }
        if (slot == 53 && holder.page + 1 < totalPages) { openVillagerList(player, holder.page + 1, holder.mode); return; }
        if (slot == 46) { openNamespaceList(player, 0); return; }
        if (slot == 47) {
            StructureListMode next = holder.mode == StructureListMode.ALL ? StructureListMode.CONFIGURED : StructureListMode.ALL;
            openVillagerList(player, 0, next);
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        int idx = holder.page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= professions.size()) return;
        openVillagerEditor(player, professions.get(idx), 0, holder.mode);
    }

    private void handleMobListClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull MobListHolder holder) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        List<String> mobs = allMobIds(holder.namespaceFilter);
        if (holder.mode == StructureListMode.CONFIGURED) {
            mobs.removeIf(id -> !store.hasProfile(TargetKey.of(TargetType.MOB, id)));
        }
        int totalPages = Math.max(1, (mobs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { openMobList(player, holder.page - 1, holder.namespaceFilter, holder.mode); return; }
        if (slot == 53 && holder.page + 1 < totalPages) { openMobList(player, holder.page + 1, holder.namespaceFilter, holder.mode); return; }
        if (slot == 46) { openMobNamespaceList(player, 0); return; }
        if (slot == 47) {
            StructureListMode next = holder.mode == StructureListMode.ALL ? StructureListMode.CONFIGURED : StructureListMode.ALL;
            openMobList(player, 0, holder.namespaceFilter, next);
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        int idx = holder.page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= mobs.size()) return;
        openMobEditor(player, mobs.get(idx), 0, holder.namespaceFilter, holder.mode);
    }

    private void handleStructureListClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull StructureListHolder holder) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        List<String> structures = allStructureIds(holder.namespaceFilter, holder.groupFilter);
        if (holder.mode == StructureListMode.CONFIGURED) structures.removeIf(id -> !store.hasProfile(TargetKey.of(TargetType.STRUCTURE, id)));
        int totalPages = Math.max(1, (structures.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { openStructureList(player, holder.page - 1, holder.mode, holder.namespaceFilter, holder.groupFilter); return; }
        if (slot == 53 && holder.page + 1 < totalPages) { openStructureList(player, holder.page + 1, holder.mode, holder.namespaceFilter, holder.groupFilter); return; }
        if (slot == 46) {
            if (holder.namespaceFilter != null && hasStructureSubgroups(holder.namespaceFilter)) {
                openStructureGroupList(player, 0, holder.mode, holder.namespaceFilter);
            } else {
                openNamespaceList(player, 0);
            }
            return;
        }
        if (slot == 47) {
            StructureListMode next = holder.mode == StructureListMode.ALL ? StructureListMode.CONFIGURED : StructureListMode.ALL;
            openStructureList(player, 0, next, holder.namespaceFilter, holder.groupFilter);
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        int idx = holder.page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= structures.size()) return;
        String back = "structure_list:" + holder.page + ":" + holder.mode.name() + ":" + (holder.namespaceFilter == null ? "*" : holder.namespaceFilter) + ":" + (holder.groupFilter == null ? "*" : holder.groupFilter);
        openStructureEditor(player, structures.get(idx), 0, back);
    }

    private void handleEditorClick(@NotNull InventoryClickEvent event, @NotNull Player player, @NotNull StructureEditorHolder holder) {
        String targetKey = holder.targetKey;
        TargetType type = TargetKey.typeOf(targetKey);
        if (type == null) return;
        String id = TargetKey.idOf(targetKey);
        StructureLootProfile profile = store.profile(targetKey);
        List<EditorEntry> entries = editorEntries(type, id, profile);
        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);

        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;

        if (!clicked.equals(top)) {
            if (event.isShiftClick() && event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) {
                event.setCancelled(true);
                ItemStack stack = event.getCurrentItem().clone();
                stack.setAmount(Math.max(1, stack.getAmount()));
                if (type == TargetType.ENCHANT_TABLE) {
                    stack = withEnchantXpCost(stack, 10);
                    store.addRule(targetKey, LootRuleType.CUSTOM, 10.0D, stack);
                } else if (type == TargetType.VILLAGER) {
                    stack = withVillagerTradeLevel(stack, 1);
                    stack = withVillagerEmeraldCost(stack, 1);
                    stack = withVillagerSpawnChance(stack, 100.0D);
                    store.addRule(targetKey, LootRuleType.CUSTOM, 100.0D, stack);
                } else {
                    store.addRule(targetKey, LootRuleType.CUSTOM, 1.0D, stack);
                }
                openEditor(player, type, id, holder.page, holder.back);
            }
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { openEditor(player, type, id, holder.page - 1, holder.back); return; }
        if (slot == 53 && holder.page + 1 < totalPages) { openEditor(player, type, id, holder.page + 1, holder.back); return; }
        if (slot == 46) {
            if ("villager_list".equals(holder.back)) openVillagerList(player, 0, StructureListMode.ALL);
            else if (holder.back != null && holder.back.startsWith("villager_list:")) {
                String raw = holder.back.substring("villager_list:".length());
                StructureListMode mode = "CONFIGURED".equalsIgnoreCase(raw) ? StructureListMode.CONFIGURED : StructureListMode.ALL;
                openVillagerList(player, 0, mode);
            }
            else if (holder.back != null && holder.back.startsWith("structure_list:")) {
                String payload = holder.back.substring("structure_list:".length());
                String[] parts = payload.split(":", 5);
                int page = 0;
                try {
                    page = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
                } catch (NumberFormatException ignored) {
                    page = 0;
                }
                StructureListMode mode = (parts.length > 1 && "CONFIGURED".equalsIgnoreCase(parts[1])) ? StructureListMode.CONFIGURED : StructureListMode.ALL;
                String namespace = parts.length > 2 ? parts[2] : "*";
                String group = parts.length > 3 ? parts[3] : "*";
                openStructureList(player, Math.max(0, page), mode, "*".equals(namespace) ? null : namespace, "*".equals(group) ? null : group);
            }
            else if ("mob_list".equals(holder.back)) openMobList(player, 0);
            else if (holder.back != null && holder.back.startsWith("mob_list:")) {
                String payload = holder.back.substring("mob_list:".length());
                String[] parts = payload.split(":", 2);
                String namespace = parts.length > 0 ? parts[0] : "*";
                StructureListMode mode = (parts.length > 1 && "CONFIGURED".equalsIgnoreCase(parts[1])) ? StructureListMode.CONFIGURED : StructureListMode.ALL;
                openMobList(player, 0, "*".equals(namespace) ? null : namespace, mode);
            }
            else openNamespaceList(player, 0);
            return;
        }
        if (slot == 47) {
            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType() == Material.AIR) {
                player.sendMessage(Component.text("Hold an item on cursor and click Add", NamedTextColor.YELLOW));
                return;
            }
            if (type == TargetType.ENCHANT_TABLE && !isBookType(cursor.getType())) {
                player.sendMessage(Component.text("Enchant Table target only accepts book items.", NamedTextColor.RED));
                return;
            }
            ItemStack toAdd = cursor.clone();
            toAdd.setAmount(Math.max(1, toAdd.getAmount()));
            if (type == TargetType.ENCHANT_TABLE) {
                toAdd = withEnchantXpCost(toAdd, 10);
                store.addRule(targetKey, LootRuleType.CUSTOM, 10.0D, toAdd);
            } else if (type == TargetType.VILLAGER) {
                toAdd = withVillagerTradeLevel(toAdd, 1);
                toAdd = withVillagerEmeraldCost(toAdd, 1);
                toAdd = withVillagerSpawnChance(toAdd, 100.0D);
                store.addRule(targetKey, LootRuleType.CUSTOM, 100.0D, toAdd);
            } else {
                store.addRule(targetKey, LootRuleType.CUSTOM, 1.0D, toAdd);
            }
            openEditor(player, type, id, holder.page, holder.back);
            return;
        }
        if (slot == 48) {
            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType() == Material.AIR) {
                player.sendMessage(Component.text("Hold an item on cursor and click Block", NamedTextColor.YELLOW));
                return;
            }
            if (type == TargetType.ENCHANT_TABLE && !isBookType(cursor.getType())) {
                player.sendMessage(Component.text("Enchant Table target only accepts book items.", NamedTextColor.RED));
                return;
            }
            ItemStack toBlock = new ItemStack(cursor.getType());
            store.addRule(targetKey, LootRuleType.REMOVE, 100.0D, toBlock);
            openEditor(player, type, id, holder.page, holder.back);
            return;
        }
        if (slot == 51) {
            store.clearRules(targetKey);
            openEditor(player, type, id, 0, holder.back);
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        int idx = holder.page * PAGE_SIZE + slot;
        if (idx < 0 || idx >= entries.size()) return;
        EditorEntry selected = entries.get(idx);
        if (selected.base) {
            if (selected.blockRuleId != null) store.removeRule(targetKey, selected.blockRuleId);
            else store.addRule(targetKey, LootRuleType.REMOVE, 100.0D, new ItemStack(selected.source.getType()));
            openEditor(player, type, id, holder.page, holder.back);
            return;
        }

        boolean deleteClick = event.getClick() == ClickType.MIDDLE
                || event.getAction() == InventoryAction.CLONE_STACK;
        if (deleteClick) {
            store.removeRule(targetKey, selected.rule.id());
            openEditor(player, type, id, holder.page, holder.back);
            return;
        }
        if (selected.rule.type() == LootRuleType.REMOVE) return;
        if (type == TargetType.ENCHANT_TABLE) {
            int step = 10;
            if (event.isShiftClick()) {
                int xp = enchantXpCost(selected.rule.item());
                int next = event.getClick().isLeftClick() ? xp + step : xp - step;
                store.updateItem(targetKey, selected.rule.id(), withEnchantXpCost(selected.rule.item(), next));
            } else {
                if (event.getClick().isLeftClick()) store.updateChance(targetKey, selected.rule.id(), selected.rule.chance() + step);
                if (event.getClick().isRightClick()) store.updateChance(targetKey, selected.rule.id(), selected.rule.chance() - step);
            }
        } else if (type == TargetType.VILLAGER) {
            if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
                int emeraldCost = villagerEmeraldCost(selected.rule.item(), selected.rule.chance());
                int next = event.getClick() == ClickType.CONTROL_DROP ? emeraldCost + 1 : emeraldCost - 1;
                store.updateItem(targetKey, selected.rule.id(), withVillagerEmeraldCost(selected.rule.item(), next));
            } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                int level = villagerTradeLevel(selected.rule.item());
                int next = (level >= 5) ? 1 : (level + 1);
                store.updateItem(targetKey, selected.rule.id(), withVillagerTradeLevel(selected.rule.item(), next));
            } else {
                double step = event.isShiftClick() ? 10.0D : 1.0D;
                double spawn = villagerSpawnChance(selected.rule.item(), selected.rule.chance());
                if (event.getClick().isLeftClick()) spawn += step;
                if (event.getClick().isRightClick()) spawn -= step;
                store.updateItem(targetKey, selected.rule.id(), withVillagerSpawnChance(selected.rule.item(), spawn));
            }
        } else {
            double step = event.isShiftClick() ? 10.0D : 1.0D;
            if (event.getClick().isLeftClick()) store.updateChance(targetKey, selected.rule.id(), selected.rule.chance() + step);
            if (event.getClick().isRightClick()) store.updateChance(targetKey, selected.rule.id(), selected.rule.chance() - step);
        }
        openEditor(player, type, id, holder.page, holder.back);
    }

    private List<EditorEntry> editorEntries(TargetType type, String id, StructureLootProfile profile) {
        List<EditorEntry> entries = new ArrayList<>();
        List<ItemStack> baseItems = new ArrayList<>();
        if (type == TargetType.STRUCTURE) {
            baseItems.addAll(baseLootCatalog.baseItemsForStructure(id));
        } else if (type == TargetType.MOB) {
            baseItems.addAll(baseLootCatalog.baseItemsForMob(id));
        } else if (type == TargetType.VILLAGER) {
            baseItems.addAll(villagerBaseItems(id));
        } else if (type == TargetType.ENCHANT_TABLE) {
            baseItems.addAll(enchantBaseItems());
        }
        baseItems.sort(Comparator.comparing(s -> s.getType().name(), String.CASE_INSENSITIVE_ORDER));
        List<LootRule> rules = new ArrayList<>(profile.rules());

        for (ItemStack base : baseItems) {
            LootRule blocking = findBlockingRule(rules, base);
            entries.add(new EditorEntry(true, blocking == null ? baseItem(base) : blockedBaseItem(base), base, null, blocking == null ? null : blocking.id()));
        }
        rules.sort(Comparator.comparing((LootRule r) -> r.type().name()).thenComparing(LootRule::id));
        for (LootRule r : rules) entries.add(new EditorEntry(false, r.item(), r.item(), r, null));
        return entries;
    }

    private List<ItemStack> villagerBaseItems(String professionId) {
        List<ItemStack> out = new ArrayList<>();
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (!villager.getProfession().name().equalsIgnoreCase(professionId)) {
                    continue;
                }
                villager.getRecipes().forEach(recipe -> {
                    if (recipe != null && recipe.getResult() != null && !recipe.getResult().getType().isAir()) {
                        ItemStack result = recipe.getResult().clone();
                        result.setAmount(1);
                        annotateVillagerBaseItem(result, recipe, professionId);
                        out.add(result);
                    }
                });
            }
        }
        out.addAll(plugin.observedVillagerItems(professionId));
        dedupeBySimilarity(out);
        return out;
    }

    private List<ItemStack> enchantBaseItems() {
        List<ItemStack> out = new ArrayList<>();
        for (Enchantment enchantment : Enchantment.values()) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
            if (meta != null) {
                meta.addStoredEnchant(enchantment, Math.max(1, enchantment.getStartLevel()), true);
                book.setItemMeta(meta);
                out.add(book);
            }
        }
        return out;
    }

    private void dedupeBySimilarity(List<ItemStack> items) {
        List<ItemStack> deduped = new ArrayList<>();
        for (ItemStack item : items) {
            boolean exists = false;
            for (ItemStack existing : deduped) {
                ItemStack a = existing.clone(); a.setAmount(1);
                ItemStack b = item.clone(); b.setAmount(1);
                if (a.isSimilar(b)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) deduped.add(item);
        }
        items.clear();
        items.addAll(deduped);
    }

    private void annotateVillagerBaseItem(@NotNull ItemStack stack,
                                          @NotNull org.bukkit.inventory.MerchantRecipe recipe,
                                          @NotNull String professionId) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Villager: " + professionId, NamedTextColor.DARK_AQUA));
        if (!recipe.getIngredients().isEmpty()) {
            ItemStack first = recipe.getIngredients().get(0);
            if (first != null && first.getType() == Material.EMERALD) {
                lore.add(Component.text("Typical cost: " + first.getAmount() + " emerald", NamedTextColor.GREEN));
            } else if (first != null && !first.getType().isAir()) {
                lore.add(Component.text("Typical first ingredient: " + first.getType().name().toLowerCase(Locale.ROOT), NamedTextColor.GREEN));
            }
        }
        if (recipe.getIngredients().size() > 1) {
            ItemStack second = recipe.getIngredients().get(1);
            if (second != null && !second.getType().isAir()) {
                lore.add(Component.text("Second ingredient: " + second.getType().name().toLowerCase(Locale.ROOT), NamedTextColor.GRAY));
            }
        }
        lore.add(Component.text("Uses: " + recipe.getUses() + "/" + recipe.getMaxUses(), NamedTextColor.GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    private LootRule findBlockingRule(List<LootRule> rules, ItemStack base) {
        for (LootRule rule : rules) {
            if (rule.type() != LootRuleType.REMOVE) continue;
            ItemStack ruleItem = rule.item();
            if (!ruleItem.hasItemMeta()) {
                if (ruleItem.getType() == base.getType()) return rule;
            } else {
                ItemStack a = ruleItem.clone(); a.setAmount(1);
                ItemStack b = base.clone(); b.setAmount(1);
                if (a.isSimilar(b)) return rule;
            }
        }
        return null;
    }

    private List<String> allStructureIds(String namespaceFilter) {
        return allStructureIds(namespaceFilter, null);
    }

    private List<String> allStructureIds(String namespaceFilter, String groupFilter) {
        Set<String> known = new LinkedHashSet<>();
        for (Structure structure : plugin.allStructures()) {
            known.add(structure.getKey().toString().toLowerCase(Locale.ROOT));
        }
        for (String virtualId : VIRTUAL_STRUCTURE_IDS) {
            known.add(virtualId.toLowerCase(Locale.ROOT));
        }

        java.util.Map<String, Boolean> hasBaseLootCache = new java.util.HashMap<>();
        java.util.function.Function<String, Boolean> hasBaseLoot = id ->
                hasBaseLootCache.computeIfAbsent(id, key -> !baseLootCatalog.baseItemsForStructure(key).isEmpty());

        Set<String> ids = new LinkedHashSet<>();
        for (String id : known) {
            if (namespaceFilter != null && !id.startsWith(namespaceFilter.toLowerCase(Locale.ROOT) + ":")) {
                continue;
            }
            if (hasBaseLoot.apply(id) || store.hasProfile(TargetKey.of(TargetType.STRUCTURE, id))) {
                ids.add(id);
            }
        }
        for (String tableId : baseLootCatalog.knownLootTableIds()) {
            for (String alias : structureAliasesForLootTable(tableId)) {
                String normalized = alias.toLowerCase(Locale.ROOT);
                if (namespaceFilter != null && !normalized.startsWith(namespaceFilter.toLowerCase(Locale.ROOT) + ":")) {
                    continue;
                }
                if (hasBaseLoot.apply(normalized) || store.hasProfile(TargetKey.of(TargetType.STRUCTURE, normalized))) {
                    ids.add(normalized);
                }
            }
        }
        for (String configured : store.configuredTargetKeys(TargetType.STRUCTURE)) {
            String id = TargetKey.idOf(configured).toLowerCase(Locale.ROOT);
            if (namespaceFilter == null || id.startsWith(namespaceFilter + ":")) {
                ids.add(id);
            }
        }
        List<String> out = new ArrayList<>(ids);
        if (groupFilter != null && !"*".equals(groupFilter)) {
            String normalizedGroup = groupFilter.toLowerCase(Locale.ROOT);
            out.removeIf(id -> {
                int colon = id.indexOf(':');
                if (colon < 0 || colon + 1 >= id.length()) {
                    return true;
                }
                String path = id.substring(colon + 1);
                if ("__direct__".equals(normalizedGroup)) {
                    return path.contains("/");
                }
                return !(path.equals(normalizedGroup) || path.startsWith(normalizedGroup + "/"));
            });
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private boolean hasStructureSubgroups(@NotNull String namespaceFilter) {
        for (String id : allStructureIds(namespaceFilter)) {
            int colon = id.indexOf(':');
            if (colon >= 0 && colon + 1 < id.length() && id.substring(colon + 1).contains("/")) {
                return true;
            }
        }
        return false;
    }

    private List<String> structureGroups(@NotNull String namespaceFilter, @NotNull StructureListMode mode) {
        List<String> ids = allStructureIds(namespaceFilter);
        if (mode == StructureListMode.CONFIGURED) {
            ids.removeIf(id -> !store.hasProfile(TargetKey.of(TargetType.STRUCTURE, id)));
        }
        Set<String> groups = new LinkedHashSet<>();
        groups.add("*");
        boolean hasDirect = false;
        for (String id : ids) {
            int colon = id.indexOf(':');
            if (colon < 0 || colon + 1 >= id.length()) {
                continue;
            }
            String path = id.substring(colon + 1);
            int slash = path.indexOf('/');
            if (slash > 0) {
                groups.add(path.substring(0, slash));
            } else {
                hasDirect = true;
            }
        }
        if (hasDirect) {
            groups.add("__direct__");
        }
        List<String> out = new ArrayList<>(groups);
        out.sort((a, b) -> {
            if ("*".equals(a)) return -1;
            if ("*".equals(b)) return 1;
            if ("__direct__".equals(a)) return 1;
            if ("__direct__".equals(b)) return -1;
            return String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });
        return out;
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
        if (tokens.length >= 2) {
            String pair = tokens[0] + "_" + tokens[1];
            out.add(namespace + ":" + pair);
        }
        if (tokens.length >= 3) {
            String triple = tokens[0] + "_" + tokens[1] + "_" + tokens[2];
            out.add(namespace + ":" + triple);
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

    private List<String> allNamespaces() {
        Set<String> out = new LinkedHashSet<>();
        out.add("minecraft");
        for (String id : allStructureIds(null)) {
            int idx = id.indexOf(':');
            if (idx > 0) out.add(id.substring(0, idx));
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        if (sorted.remove("minecraft")) sorted.add(0, "minecraft");
        return sorted;
    }

    private List<String> allMobIds(String namespaceFilter) {
        Set<String> out = new LinkedHashSet<>();
        for (EntityType entityType : EntityType.values()) {
            if (!entityType.isAlive() || entityType.getKey() == null) {
                continue;
            }
            String id = entityType.getKey().toString().toLowerCase(Locale.ROOT);
            if (namespaceFilter == null || id.startsWith(namespaceFilter.toLowerCase(Locale.ROOT) + ":")) {
                out.add(id);
            }
        }
        for (String id : baseLootCatalog.knownMobIdsFromLootTables()) {
            if (namespaceFilter == null || id.startsWith(namespaceFilter.toLowerCase(Locale.ROOT) + ":")) {
                out.add(id);
            }
        }
        for (String configured : store.configuredTargetKeys(TargetType.MOB)) {
            String id = TargetKey.idOf(configured).toLowerCase(Locale.ROOT);
            if (namespaceFilter == null || id.startsWith(namespaceFilter.toLowerCase(Locale.ROOT) + ":")) {
                out.add(id);
            }
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private List<String> allMobNamespaces() {
        Set<String> out = new LinkedHashSet<>();
        for (String id : allMobIds(null)) {
            int idx = id.indexOf(':');
            if (idx > 0) out.add(id.substring(0, idx));
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        if (sorted.remove("minecraft")) sorted.add(0, "minecraft");
        return sorted;
    }

    private boolean hasOnlyVanillaMobNamespace() {
        List<String> namespaces = allMobNamespaces();
        return namespaces.size() == 1 && "minecraft".equalsIgnoreCase(namespaces.get(0));
    }

    private ItemStack structureItem(String key) {
        boolean configured = store.hasProfile(TargetKey.of(TargetType.STRUCTURE, key));
        ItemStack item = new ItemStack(configured ? Material.CHEST : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(key, configured ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            meta.lore(List.of(Component.text(configured ? "Configured" : "Not configured", configured ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack mobItem(String key) {
        boolean configured = store.hasProfile(TargetKey.of(TargetType.MOB, key));
        Material mat = mobDisplayMaterial(key);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(key, configured ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            meta.lore(List.of(Component.text(configured ? "Configured" : "Not configured", configured ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material mobDisplayMaterial(@NotNull String mobId) {
        int colon = mobId.indexOf(':');
        if (colon <= 0 || colon + 1 >= mobId.length()) {
            return Material.SPAWNER;
        }
        String namespace = mobId.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!"minecraft".equals(namespace)) {
            return Material.SPAWNER;
        }
        String path = mobId.substring(colon + 1)
                .replace('/', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        Material egg = Material.getMaterial(path + "_SPAWN_EGG");
        return egg == null ? Material.SPAWNER : egg;
    }

    private ItemStack namespaceItem(String namespace) {
        boolean vanilla = namespace.equals("minecraft");
        ItemStack item = new ItemStack(vanilla ? Material.GRASS_BLOCK : Material.BOOKSHELF);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(vanilla ? "Vanilla (minecraft)" : namespace, NamedTextColor.AQUA));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack structureGroupItem(String group) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String label = switch (group) {
                case "*" -> "All Entries";
                case "__direct__" -> "Direct Entries";
                default -> group;
            };
            meta.displayName(Component.text(label, NamedTextColor.AQUA));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack specialItem(String name, Material mat) {
        return plainItem(mat, name, NamedTextColor.AQUA);
    }

    private ItemStack plainItem(Material mat, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack navItem(String label, boolean enabled) {
        ItemStack item = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(label, enabled ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack modeSwitchItem(StructureListMode mode) {
        return modeSwitchItem(mode, "All Structures", "Configured Only");
    }

    private ItemStack modeSwitchItem(StructureListMode mode, String allLabel, String configuredLabel) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String next = mode == StructureListMode.ALL ? configuredLabel : allLabel;
            meta.displayName(Component.text("Switch: " + next, NamedTextColor.AQUA));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack addItemPane() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text("Add Custom Item", NamedTextColor.GREEN)); item.setItemMeta(meta); }
        return item;
    }

    private ItemStack blockItemPane() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text("Block Existing Item", NamedTextColor.RED)); item.setItemMeta(meta); }
        return item;
    }

    private ItemStack clearItem() {
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text("Clear Rules", NamedTextColor.RED)); item.setItemMeta(meta); }
        return item;
    }

    private ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text("Back to Sources", NamedTextColor.YELLOW)); item.setItemMeta(meta); }
        return item;
    }

    private boolean isBookType(@NotNull Material material) {
        return material == Material.BOOK || material == Material.ENCHANTED_BOOK || material == Material.WRITTEN_BOOK || material == Material.WRITABLE_BOOK;
    }

    private ItemStack infoItem(String text) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Info", NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text(text, NamedTextColor.GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack ruleItem(LootRule rule, TargetType editorType) {
        ItemStack item = rule.item().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Type: " + rule.type().name().toLowerCase(Locale.ROOT), NamedTextColor.AQUA));
            if (editorType == TargetType.VILLAGER && rule.type() == LootRuleType.CUSTOM) {
                lore.add(Component.text(String.format(Locale.ROOT, "Spawn Chance: %.1f%%", villagerSpawnChance(rule.item(), rule.chance())), NamedTextColor.GOLD));
            } else if (rule.type() == LootRuleType.REMOVE) {
                lore.add(Component.text("Chance: fixed 100%", NamedTextColor.GOLD));
            } else {
                lore.add(Component.text(String.format(Locale.ROOT, "Chance: %.1f%%", rule.chance()), NamedTextColor.GOLD));
            }
            if (editorType == TargetType.ENCHANT_TABLE && rule.type() == LootRuleType.CUSTOM) {
                lore.add(Component.text("XP Cost: " + enchantXpCost(rule.item()), NamedTextColor.GOLD));
                lore.add(Component.text("Left/Right: chance +/-10", NamedTextColor.GRAY));
                lore.add(Component.text("Shift+Left/Right: XP +/-10", NamedTextColor.GRAY));
            } else if (editorType == TargetType.VILLAGER && rule.type() == LootRuleType.CUSTOM) {
                lore.add(Component.text("Emerald Cost: " + villagerEmeraldCost(rule.item(), rule.chance()), NamedTextColor.GOLD));
                lore.add(Component.text("Villager Level: " + villagerTradeLevel(rule.item()), NamedTextColor.GOLD));
                lore.add(Component.text("Left/Right: chance +/-1", NamedTextColor.GRAY));
                lore.add(Component.text("Shift+Left/Right: chance +/-10", NamedTextColor.GRAY));
                lore.add(Component.text("Q/Ctrl+Q: emerald -/+1", NamedTextColor.GRAY));
                lore.add(Component.text("F: cycle level 1..5", NamedTextColor.GRAY));
            } else if (rule.type() == LootRuleType.CUSTOM) {
                lore.add(Component.text("Left/Right: chance +/-1", NamedTextColor.GRAY));
                lore.add(Component.text("Shift+Left/Right: chance +/-10", NamedTextColor.GRAY));
            }
            lore.add(Component.text("Middle: delete rule", NamedTextColor.GRAY));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private int enchantXpCost(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 10;
        }
        Integer raw = meta.getPersistentDataContainer().get(enchantXpCostKey, org.bukkit.persistence.PersistentDataType.INTEGER);
        if (raw == null) {
            return 10;
        }
        return Math.max(1, Math.min(30, raw));
    }

    private @NotNull ItemStack withEnchantXpCost(@NotNull ItemStack source, int xpCost) {
        ItemStack out = source.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        meta.getPersistentDataContainer().set(enchantXpCostKey, org.bukkit.persistence.PersistentDataType.INTEGER, Math.max(1, Math.min(30, xpCost)));
        out.setItemMeta(meta);
        return out;
    }

    private int villagerTradeLevel(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 1;
        }
        Integer raw = meta.getPersistentDataContainer().get(villagerLevelKey, org.bukkit.persistence.PersistentDataType.INTEGER);
        if (raw == null) {
            return 1;
        }
        return Math.max(1, Math.min(5, raw));
    }

    private @NotNull ItemStack withVillagerTradeLevel(@NotNull ItemStack source, int level) {
        ItemStack out = source.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        meta.getPersistentDataContainer().set(villagerLevelKey, org.bukkit.persistence.PersistentDataType.INTEGER, Math.max(1, Math.min(5, level)));
        out.setItemMeta(meta);
        return out;
    }

    private int villagerEmeraldCost(@NotNull ItemStack item, double fallbackFromRuleChance) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Integer raw = meta.getPersistentDataContainer().get(villagerEmeraldCostKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (raw != null) {
                return Math.max(1, Math.min(64, raw));
            }
        }
        return Math.max(1, Math.min(64, (int) Math.round(fallbackFromRuleChance)));
    }

    private @NotNull ItemStack withVillagerEmeraldCost(@NotNull ItemStack source, int emeraldCost) {
        ItemStack out = source.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        meta.getPersistentDataContainer().set(villagerEmeraldCostKey, org.bukkit.persistence.PersistentDataType.INTEGER, Math.max(1, Math.min(64, emeraldCost)));
        out.setItemMeta(meta);
        return out;
    }

    private double villagerSpawnChance(@NotNull ItemStack item, double fallbackFromRuleChance) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Double raw = meta.getPersistentDataContainer().get(villagerSpawnChanceKey, org.bukkit.persistence.PersistentDataType.DOUBLE);
            if (raw != null) {
                return Math.max(0.0D, Math.min(100.0D, raw));
            }
        }
        return Math.max(0.0D, Math.min(100.0D, fallbackFromRuleChance));
    }

    private @NotNull ItemStack withVillagerSpawnChance(@NotNull ItemStack source, double spawnChance) {
        ItemStack out = source.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        meta.getPersistentDataContainer().set(villagerSpawnChanceKey, org.bukkit.persistence.PersistentDataType.DOUBLE, Math.max(0.0D, Math.min(100.0D, spawnChance)));
        out.setItemMeta(meta);
        return out;
    }

    private ItemStack baseItem(ItemStack base) {
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Type: base (read-only)", NamedTextColor.BLUE));
            lore.add(Component.text("Click to toggle block", NamedTextColor.RED));
            List<Component> existing = meta.lore();
            if (existing != null && !existing.isEmpty()) {
                lore.addAll(existing);
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack blockedBaseItem(ItemStack base) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Blocked: " + base.getType().name().toLowerCase(Locale.ROOT), NamedTextColor.RED));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private record NamespaceListHolder(int page) implements InventoryHolder { public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); } }
    private record StructureGroupListHolder(int page, StructureListMode mode, String namespaceFilter) implements InventoryHolder { public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); } }
    private record VillagerListHolder(int page, StructureListMode mode) implements InventoryHolder { public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); } }
    private record MobNamespaceListHolder(int page) implements InventoryHolder { public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); } }
    private record MobListHolder(int page, String namespaceFilter, StructureListMode mode) implements InventoryHolder { public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); } }
    private record StructureListHolder(int page, StructureListMode mode, String namespaceFilter, String groupFilter) implements InventoryHolder { public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); } }
    private record StructureEditorHolder(String targetKey, int page, String back) implements InventoryHolder { public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); } }

    private static final class EditorEntry {
        final boolean base;
        final ItemStack display;
        final ItemStack source;
        final LootRule rule;
        final String blockRuleId;
        private EditorEntry(boolean base, ItemStack display, ItemStack source, LootRule rule, String blockRuleId) {
            this.base = base; this.display = display; this.source = source; this.rule = rule; this.blockRuleId = blockRuleId;
        }
    }
}
