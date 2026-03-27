package dev.cevapi.lootinjector.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class StructureBaseLootCatalog {
    private static final String LOOT_TABLE_DIR_NEW = "/loot_table/";
    private static final String LOOT_TABLE_DIR_OLD = "/loot_tables/";

    private final JavaPlugin plugin;
    private final Map<String, Map<String, BaseItemDescriptor>> lootTableItems = new HashMap<>();
    private final Map<String, Set<String>> lootTableRefs = new HashMap<>();
    private final Map<String, Material> materialCache = new HashMap<>();
    private int scannedLootTableFileCount;

    StructureBaseLootCatalog(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        lootTableItems.clear();
        lootTableRefs.clear();
        materialCache.clear();
        scannedLootTableFileCount = 0;
        scanServerJar();
        scanDatapacks();
    }

    @NotNull List<ItemStack> baseItemsForStructure(@NotNull String structureId) {
        Set<String> roots = filterStructureRoots(structureId.toLowerCase(Locale.ROOT), structureRootCandidates(structureId));
        return baseItemsForRoots(roots);
    }

    @NotNull List<ItemStack> baseItemsForLootTable(@NotNull String lootTableId) {
        return baseItemsForRoots(Set.of(lootTableId.toLowerCase(Locale.ROOT)));
    }

    @NotNull List<ItemStack> baseItemsForMob(@NotNull String mobId) {
        Set<String> roots = mobRootCandidates(mobId.toLowerCase(Locale.ROOT));
        return baseItemsForRoots(roots);
    }

    @NotNull List<String> knownMobIdsFromLootTables() {
        Set<String> out = new LinkedHashSet<>();
        collectMobIdsFromTableIds(out, lootTableItems.keySet());
        collectMobIdsFromTableIds(out, lootTableRefs.keySet());
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private @NotNull List<ItemStack> baseItemsForRoots(@NotNull Set<String> roots) {
        Set<String> visited = new LinkedHashSet<>();
        Map<String, BaseItemDescriptor> matched = new LinkedHashMap<>();
        for (String root : roots) {
            collectItemsRecursive(root, visited, matched);
        }

        List<ItemStack> out = new ArrayList<>();
        for (BaseItemDescriptor descriptor : matched.values()) {
            Material baseMaterial = materialFromItemId(descriptor.itemId());
            if (baseMaterial == null || baseMaterial.isAir()) {
                continue;
            }

            Material displayMaterial = baseMaterial;
            if (descriptor.enchantedHint() && baseMaterial == Material.BOOK) {
                displayMaterial = Material.ENCHANTED_BOOK;
            }

            ItemStack stack = new ItemStack(displayMaterial);
            if (descriptor.enchantedHint() && displayMaterial != Material.ENCHANTED_BOOK) {
                try {
                    stack.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null) {
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                        stack.setItemMeta(meta);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (descriptor.displayNameHint() != null && !descriptor.displayNameHint().isBlank()) {
                try {
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null) {
                        meta.displayName(Component.text(descriptor.displayNameHint(), NamedTextColor.AQUA));
                        applyItemModelHint(meta, descriptor.itemModelHint());
                        stack.setItemMeta(meta);
                    }
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null) {
                        applyItemModelHint(meta, descriptor.itemModelHint());
                        stack.setItemMeta(meta);
                    }
                } catch (Throwable ignored) {
                }
            }
            out.add(stack);
        }
        return out;
    }

    private Material materialFromItemId(@NotNull String itemId) {
        Material cached = materialCache.get(itemId);
        if (cached != null || materialCache.containsKey(itemId)) {
            return cached;
        }

        String normalized = itemId.toLowerCase(Locale.ROOT).trim();
        String namespace = "minecraft";
        String path = normalized;
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            namespace = normalized.substring(0, colon);
            path = normalized.substring(colon + 1);
        }

        Material resolved = null;
        if ("minecraft".equals(namespace) && !path.isBlank()) {
            String enumName = path
                    .replace('/', '_')
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            resolved = Material.getMaterial(enumName);
        }

        materialCache.put(itemId, resolved);
        return resolved;
    }

    int scannedLootTableCount() {
        return lootTableItems.size();
    }

    int scannedLootTableFileCount() {
        return scannedLootTableFileCount;
    }

    @NotNull List<String> debugRootsForStructure(@NotNull String structureId) {
        return new ArrayList<>(filterStructureRoots(structureId.toLowerCase(Locale.ROOT), structureRootCandidates(structureId)));
    }

    @NotNull List<String> rootsForStructure(@NotNull String structureId) {
        List<String> out = new ArrayList<>(filterStructureRoots(structureId.toLowerCase(Locale.ROOT), structureRootCandidates(structureId)));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private @NotNull Set<String> filterStructureRoots(@NotNull String structureId, @NotNull Set<String> roots) {
        Set<String> filtered = new LinkedHashSet<>();
        boolean normalTrial = structureId.endsWith(":trial_chambers");
        boolean vaultOnly = structureId.endsWith(":trial_chambers_vault");
        boolean ominousOnly = structureId.endsWith(":trial_chambers_ominous_vault");

        for (String root : roots) {
            String rel = root;
            int colon = root.indexOf(':');
            if (colon >= 0 && colon + 1 < root.length()) {
                rel = root.substring(colon + 1);
            }
            String lowered = rel.toLowerCase(Locale.ROOT);
            boolean trialPath = lowered.contains("trial_chambers");
            boolean vaultPath = lowered.contains("vault") || lowered.contains("reward");
            boolean ominousPath = lowered.contains("ominous");

            if (normalTrial && trialPath && (vaultPath || ominousPath)) {
                continue;
            }
            if (vaultOnly) {
                if (!trialPath || !vaultPath || ominousPath) {
                    continue;
                }
            }
            if (ominousOnly) {
                if (!trialPath || !ominousPath) {
                    continue;
                }
            }
            filtered.add(root);
        }
        return filtered;
    }

    private @NotNull Set<String> structureRootCandidates(@NotNull String structureId) {
        String normalized = structureId.toLowerCase(Locale.ROOT);
        String namespace = "minecraft";
        String path = normalized;
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            namespace = normalized.substring(0, colon);
            path = normalized.substring(colon + 1);
        }

        Set<String> out = new LinkedHashSet<>();
        String last = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            last = path.substring(slash + 1);
        }

        Set<String> directCandidates = new LinkedHashSet<>();
        directCandidates.add(namespace + ":" + path);
        directCandidates.add(namespace + ":" + last);
        directCandidates.add(namespace + ":chests/" + path);
        directCandidates.add(namespace + ":chests/" + last);
        directCandidates.add("minecraft:" + path);
        directCandidates.add("minecraft:" + last);
        directCandidates.add("minecraft:chests/" + path);
        directCandidates.add("minecraft:chests/" + last);

        Set<String> existingDirect = new LinkedHashSet<>();
        for (String candidate : directCandidates) {
            if (hasTable(candidate)) {
                existingDirect.add(candidate);
            }
        }

        if (!existingDirect.isEmpty()) {
            out.addAll(existingDirect);
            for (String direct : existingDirect) {
                includeAnchoredVariantsFromRoot(out, direct);
            }
            return out;
        }

        // Fallback only when no direct match exists.
        Set<String> anchors = fallbackAnchors(path, last);
        for (String anchor : anchors) {
            includeAnchoredVariants(out, namespace, anchor);
            includeAnchoredVariants(out, "minecraft", anchor);
        }
        return out;
    }

    private @NotNull Set<String> mobRootCandidates(@NotNull String mobId) {
        String normalized = mobId.toLowerCase(Locale.ROOT);
        String namespace = "minecraft";
        String path = normalized;
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            namespace = normalized.substring(0, colon);
            path = normalized.substring(colon + 1);
        }

        Set<String> out = new LinkedHashSet<>();
        Set<String> directCandidates = new LinkedHashSet<>();
        directCandidates.add(namespace + ":entities/" + path);
        directCandidates.add(namespace + ":entity/" + path);
        directCandidates.add(namespace + ":" + path);
        directCandidates.add("minecraft:entities/" + path);
        directCandidates.add("minecraft:entity/" + path);
        directCandidates.add("minecraft:" + path);

        Set<String> existingDirect = new LinkedHashSet<>();
        for (String candidate : directCandidates) {
            if (hasTable(candidate)) {
                existingDirect.add(candidate);
            }
        }

        if (!existingDirect.isEmpty()) {
            out.addAll(existingDirect);
            for (String direct : existingDirect) {
                includeAnchoredVariantsFromRoot(out, direct);
            }
            return out;
        }

        includeAnchoredVariants(out, namespace, "entities/" + path);
        includeAnchoredVariants(out, namespace, "entity/" + path);
        includeAnchoredVariants(out, namespace, path);
        if (!"minecraft".equals(namespace)) {
            includeAnchoredVariants(out, "minecraft", "entities/" + path);
            includeAnchoredVariants(out, "minecraft", "entity/" + path);
            includeAnchoredVariants(out, "minecraft", path);
        }
        return out;
    }

    private void collectMobIdsFromTableIds(@NotNull Set<String> out, @NotNull Collection<String> tableIds) {
        for (String tableId : tableIds) {
            int colon = tableId.indexOf(':');
            if (colon <= 0 || colon + 1 >= tableId.length()) {
                continue;
            }
            String namespace = tableId.substring(0, colon);
            String rel = tableId.substring(colon + 1);
            String mobPath = extractMobPath(rel);
            if (mobPath == null || mobPath.isBlank()) {
                continue;
            }
            int slash = mobPath.lastIndexOf('/');
            String finalPath = slash >= 0 ? mobPath.substring(slash + 1) : mobPath;
            if (!finalPath.isBlank()) {
                out.add(namespace + ":" + finalPath);
            }
        }
    }

    private String extractMobPath(@NotNull String relPath) {
        String lowered = relPath.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("entities/")) {
            return relPath.substring("entities/".length());
        }
        if (lowered.startsWith("entity/")) {
            return relPath.substring("entity/".length());
        }
        int entitiesIdx = lowered.indexOf("/entities/");
        if (entitiesIdx >= 0) {
            return relPath.substring(entitiesIdx + "/entities/".length());
        }
        int entityIdx = lowered.indexOf("/entity/");
        if (entityIdx >= 0) {
            return relPath.substring(entityIdx + "/entity/".length());
        }
        return null;
    }

    private @NotNull Set<String> fallbackAnchors(@NotNull String path, @NotNull String last) {
        Set<String> anchors = new LinkedHashSet<>();
        anchors.add(path);
        anchors.add(last);
        anchors.add("chests/" + path);
        anchors.add("chests/" + last);

        addTokenAnchors(anchors, path);
        addTokenAnchors(anchors, last);
        return anchors;
    }

    private void addTokenAnchors(@NotNull Set<String> anchors, @NotNull String input) {
        String[] parts = input.split("[/_]+");
        if (parts.length == 0) {
            return;
        }
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            anchors.add(part);
            anchors.add("chests/" + part);
        }
        for (int i = 0; i + 1 < parts.length; i++) {
            String pair = parts[i] + "_" + parts[i + 1];
            anchors.add(pair);
            anchors.add("chests/" + pair);
        }
        for (int i = 0; i + 2 < parts.length; i++) {
            String triple = parts[i] + "_" + parts[i + 1] + "_" + parts[i + 2];
            anchors.add(triple);
            anchors.add("chests/" + triple);
        }
        if (parts.length >= 1) {
            String tail1 = parts[parts.length - 1];
            if (!tail1.isBlank()) {
                anchors.add(tail1);
                anchors.add("chests/" + tail1);
            }
        }
        if (parts.length >= 2) {
            String tail2 = parts[parts.length - 2] + "_" + parts[parts.length - 1];
            anchors.add(tail2);
            anchors.add("chests/" + tail2);
        }
    }

    private boolean hasTable(@NotNull String tableId) {
        return lootTableItems.containsKey(tableId) || lootTableRefs.containsKey(tableId);
    }

    private void includeAnchoredVariantsFromRoot(@NotNull Set<String> out, @NotNull String rootTableId) {
        int colon = rootTableId.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String namespace = rootTableId.substring(0, colon);
        String rel = rootTableId.substring(colon + 1);
        includeAnchoredVariants(out, namespace, rel);
    }

    private void includeAnchoredVariants(@NotNull Set<String> out, @NotNull String namespace, @NotNull String baseName) {
        String prefix = namespace + ":";
        includeAnchoredFromMap(out, prefix, baseName, lootTableItems.keySet());
        includeAnchoredFromMap(out, prefix, baseName, lootTableRefs.keySet());
    }

    private void includeAnchoredFromMap(@NotNull Set<String> out,
                                        @NotNull String prefix,
                                        @NotNull String baseName,
                                        @NotNull Collection<String> ids) {
        for (String tableId : ids) {
            if (!tableId.startsWith(prefix)) {
                continue;
            }
            String rel = tableId.substring(prefix.length());
            if (belongsToSameBranch(rel, baseName)) {
                out.add(tableId);
            }
        }
    }

    private boolean belongsToSameBranch(@NotNull String relPath, @NotNull String basePath) {
        if (relPath.equals(basePath)) {
            return true;
        }
        if (relPath.startsWith(basePath + "_")) {
            return true;
        }
        return relPath.startsWith(basePath + "/");
    }

    private void collectItemsRecursive(@NotNull String tableId,
                                       @NotNull Set<String> visited,
                                       @NotNull Map<String, BaseItemDescriptor> out) {
        if (!visited.add(tableId)) {
            return;
        }

        Map<String, BaseItemDescriptor> direct = lootTableItems.get(tableId);
        if (direct != null) {
            for (BaseItemDescriptor descriptor : direct.values()) {
                out.merge(descriptor.itemId(), descriptor, BaseItemDescriptor::merge);
            }
        }

        Set<String> refs = lootTableRefs.get(tableId);
        if (refs == null) {
            return;
        }
        for (String ref : refs) {
            collectItemsRecursive(ref, visited, out);
        }
    }

    private void scanServerJar() {
        Set<Path> candidates = new LinkedHashSet<>();
        try {
            Path pluginJar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            candidates.add(pluginJar);
        } catch (Throwable ignored) {
        }
        try {
            Path bukkitJar = Path.of(Bukkit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            candidates.add(bukkitJar);
        } catch (Throwable ignored) {
        }
        addClassCodeSource(candidates, "net.minecraft.server.MinecraftServer");
        addClassCodeSource(candidates, "net.minecraft.world.level.storage.loot.LootTable");
        addClassCodeSource(candidates, "org.bukkit.craftbukkit.CraftServer");
        for (Path candidate : candidates) {
            scanJar(candidate);
        }
    }

    private void addClassCodeSource(@NotNull Set<Path> candidates, @NotNull String className) {
        try {
            Class<?> type = Class.forName(className);
            if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) {
                return;
            }
            Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            candidates.add(path);
        } catch (Throwable ignored) {
        }
    }

    private void scanJar(@NotNull Path jarPath) {
        if (!Files.exists(jarPath) || !jarPath.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!isLootTableJsonPath(name)) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    ingestLootTableJson(name, in);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void scanDatapacks() {
        Set<Path> scannedDatapackRoots = new HashSet<>();
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            Path datapacksDir = world.getWorldFolder().toPath().resolve("datapacks");
            if (!Files.isDirectory(datapacksDir) || !scannedDatapackRoots.add(datapacksDir)) {
                continue;
            }

            try (Stream<Path> stream = Files.list(datapacksDir)) {
                stream.forEach(this::scanDatapackEntry);
            } catch (IOException ignored) {
            }
        }
    }

    private void scanDatapackEntry(@NotNull Path entry) {
        String lowered = entry.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Files.isDirectory(entry)) {
            scanDatapackFolder(entry);
            return;
        }
        if (lowered.endsWith(".zip")) {
            scanDatapackZip(entry);
        }
    }

    private void scanDatapackFolder(@NotNull Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(path -> Files.isRegularFile(path) && isLootTableJsonPath(path.toString().replace('\\', '/')))
                    .forEach(path -> {
                        try (InputStream in = Files.newInputStream(path)) {
                            String normalized = path.toString().replace('\\', '/');
                            int dataIdx = normalized.lastIndexOf("/data/");
                            if (dataIdx >= 0) {
                                ingestLootTableJson(normalized.substring(dataIdx + 1), in);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private void scanDatapackZip(@NotNull Path zipPath) {
        try (JarFile jar = new JarFile(zipPath.toFile())) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!isLootTableJsonPath(name)) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    ingestLootTableJson(name, in);
                } catch (Throwable ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private boolean isLootTableJsonPath(@NotNull String path) {
        String lowered = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (!lowered.endsWith(".json")) {
            return false;
        }
        boolean hasDataSegment = lowered.contains("/data/") || lowered.startsWith("data/");
        return hasDataSegment && (lowered.contains(LOOT_TABLE_DIR_NEW) || lowered.contains(LOOT_TABLE_DIR_OLD));
    }

    private void ingestLootTableJson(@NotNull String path, @NotNull InputStream in) {
        String tableId = tableIdFromPath(path);
        if (tableId == null) {
            return;
        }
        JsonElement root;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        } catch (Throwable ignored) {
            return;
        }
        if (!root.isJsonObject()) {
            return;
        }

        ParsedLoot parsed = parseLoot(root.getAsJsonObject());
        if (!parsed.items().isEmpty()) {
            scannedLootTableFileCount++;
            Map<String, BaseItemDescriptor> tableMap = lootTableItems.computeIfAbsent(tableId, unused -> new LinkedHashMap<>());
            for (BaseItemDescriptor descriptor : parsed.items()) {
                tableMap.merge(descriptor.itemId(), descriptor, BaseItemDescriptor::merge);
            }
        }
        if (!parsed.refs().isEmpty()) {
            lootTableRefs.computeIfAbsent(tableId, unused -> new LinkedHashSet<>()).addAll(parsed.refs());
        }
    }

    private String tableIdFromPath(@NotNull String rawPath) {
        String normalized = rawPath.replace('\\', '/');
        int dataIndex = normalized.indexOf("data/");
        if (dataIndex < 0) {
            return null;
        }
        String dataRelative = normalized.substring(dataIndex + "data/".length());
        int slash = dataRelative.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String namespace = dataRelative.substring(0, slash);
        String remainder = dataRelative.substring(slash + 1);

        int lootNewIdx = remainder.indexOf("loot_table/");
        int lootOldIdx = remainder.indexOf("loot_tables/");
        int idx = lootNewIdx >= 0 ? lootNewIdx + "loot_table/".length()
                : lootOldIdx >= 0 ? lootOldIdx + "loot_tables/".length() : -1;
        if (idx < 0 || idx >= remainder.length()) {
            return null;
        }
        String path = remainder.substring(idx);
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - 5);
        }
        return namespace + ":" + path;
    }

    private ParsedLoot parseLoot(@NotNull JsonObject root) {
        List<BaseItemDescriptor> items = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        JsonArray pools = root.getAsJsonArray("pools");
        if (pools == null) {
            return new ParsedLoot(items, refs);
        }
        for (JsonElement poolElement : pools) {
            if (!poolElement.isJsonObject()) {
                continue;
            }
            JsonArray entries = poolElement.getAsJsonObject().getAsJsonArray("entries");
            if (entries != null) {
                parseEntries(entries, items, refs, false);
            }
        }
        return new ParsedLoot(items, refs);
    }

    private void parseEntries(@NotNull JsonArray entries,
                              @NotNull Collection<BaseItemDescriptor> items,
                              @NotNull Collection<String> refs,
                              boolean inheritedEnchantHint) {
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            boolean enchantedHint = inheritedEnchantHint || hasEnchantFunction(entry);
            String type = entry.has("type") ? entry.get("type").getAsString() : "";
            String loweredType = type.toLowerCase(Locale.ROOT);

            if (loweredType.endsWith(":item") || loweredType.equals("item")) {
                if (entry.has("name")) {
                    String id = normalizeItemId(entry.get("name").getAsString());
                    DisplayHints hints = extractDisplayHints(entry);
                    items.add(new BaseItemDescriptor(id, enchantedHint, hints.displayNameHint(), hints.itemModelHint()));
                }
            }

            if (loweredType.endsWith(":loot_table") || loweredType.equals("loot_table")) {
                if (entry.has("value")) {
                    refs.add(entry.get("value").getAsString().toLowerCase(Locale.ROOT));
                } else if (entry.has("name")) {
                    refs.add(entry.get("name").getAsString().toLowerCase(Locale.ROOT));
                }
            }

            JsonArray children = entry.getAsJsonArray("children");
            if (children != null) {
                parseEntries(children, items, refs, enchantedHint);
            }
        }
    }

    private boolean hasEnchantFunction(@NotNull JsonObject entry) {
        JsonArray functions = entry.getAsJsonArray("functions");
        if (functions == null) {
            return false;
        }
        for (JsonElement functionElement : functions) {
            if (!functionElement.isJsonObject()) {
                continue;
            }
            JsonObject function = functionElement.getAsJsonObject();
            if (!function.has("function")) {
                continue;
            }
            String id = function.get("function").getAsString().toLowerCase(Locale.ROOT);
            if (id.endsWith(":enchant_with_levels") || id.equals("enchant_with_levels")
                    || id.endsWith(":set_enchantments") || id.equals("set_enchantments")
                    || id.endsWith(":enchant_randomly") || id.equals("enchant_randomly")) {
                return true;
            }
        }
        return false;
    }

    private @NotNull String normalizeItemId(@NotNull String raw) {
        String lowered = raw.toLowerCase(Locale.ROOT).trim();
        if (lowered.contains(":")) {
            return lowered;
        }
        return "minecraft:" + lowered;
    }

    private DisplayHints extractDisplayHints(@NotNull JsonObject entry) {
        String displayName = null;
        String itemModel = null;
        JsonArray functions = entry.getAsJsonArray("functions");
        if (functions == null) {
            return new DisplayHints(null, null);
        }
        for (JsonElement functionElement : functions) {
            if (!functionElement.isJsonObject()) {
                continue;
            }
            JsonObject function = functionElement.getAsJsonObject();
            String functionId = function.has("function") ? function.get("function").getAsString().toLowerCase(Locale.ROOT) : "";

            if (functionId.endsWith(":set_components") || functionId.equals("set_components")) {
                JsonObject components = function.getAsJsonObject("components");
                if (components == null) {
                    continue;
                }
                JsonElement itemName = components.has("minecraft:item_name")
                        ? components.get("minecraft:item_name")
                        : components.get("item_name");
                String parsed = parseTextComponent(itemName);
                if ((displayName == null || displayName.isBlank()) && parsed != null && !parsed.isBlank()) {
                    displayName = parsed;
                }
                JsonElement model = components.has("minecraft:item_model")
                        ? components.get("minecraft:item_model")
                        : components.get("item_model");
                if ((itemModel == null || itemModel.isBlank()) && model != null && model.isJsonPrimitive()) {
                    itemModel = model.getAsString().toLowerCase(Locale.ROOT);
                }
            }

            if (functionId.endsWith(":set_name") || functionId.equals("set_name")) {
                String parsed = parseTextComponent(function.get("name"));
                if ((displayName == null || displayName.isBlank()) && parsed != null && !parsed.isBlank()) {
                    displayName = parsed;
                }
            }
        }
        return new DisplayHints(displayName, itemModel);
    }

    private void applyItemModelHint(@NotNull ItemMeta meta, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        try {
            java.lang.reflect.Method method = meta.getClass().getMethod("setItemModel", NamespacedKey.class);
            NamespacedKey key = NamespacedKey.fromString(modelId);
            if (key != null) {
                method.invoke(meta, key);
            }
        } catch (Throwable ignored) {
        }
    }

    private String parseTextComponent(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                String raw = primitive.getAsString();
                if (raw.isBlank()) {
                    return null;
                }
                return prettifyTranslate(raw);
            }
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("text")) {
            String text = obj.get("text").getAsString();
            if (!text.isBlank()) {
                return text;
            }
        }
        if (obj.has("translate")) {
            return prettifyTranslate(obj.get("translate").getAsString());
        }
        return null;
    }

    private @NotNull String prettifyTranslate(@NotNull String key) {
        String normalized = key.trim();
        if (normalized.contains(".")) {
            normalized = normalized.substring(normalized.lastIndexOf('.') + 1);
        }
        normalized = normalized.replace('_', ' ').replace('-', ' ');
        if (normalized.isBlank()) {
            return key;
        }
        String[] words = normalized.split("\\s+");
        List<String> capped = new ArrayList<>(words.length);
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            capped.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return String.join(" ", capped);
    }

    private record ParsedLoot(@NotNull List<BaseItemDescriptor> items, @NotNull Set<String> refs) {
    }

    private record DisplayHints(String displayNameHint, String itemModelHint) {
    }

    private record BaseItemDescriptor(@NotNull String itemId, boolean enchantedHint, String displayNameHint, String itemModelHint) {
        private static @NotNull BaseItemDescriptor merge(@NotNull BaseItemDescriptor left, @NotNull BaseItemDescriptor right) {
            String name = left.displayNameHint;
            if (name == null || name.isBlank()) {
                name = right.displayNameHint;
            }
            String itemModel = left.itemModelHint;
            if (itemModel == null || itemModel.isBlank()) {
                itemModel = right.itemModelHint;
            }
            return new BaseItemDescriptor(left.itemId, left.enchantedHint || right.enchantedHint, name, itemModel);
        }
    }
}
