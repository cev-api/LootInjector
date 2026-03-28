package dev.cevapi.lootinjector.fabric;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.cevapi.lootinjector.common.TargetKey;
import dev.cevapi.lootinjector.common.TargetType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LootInjectorFabricMod implements ModInitializer {
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private FabricRuleStore store;

    @Override
    public void onInitialize() {
        this.store = new FabricRuleStore(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        this.store.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("lootinjector")
                    .then(Commands.literal("reload")
                            .executes(context -> {
                                store.load();
                                context.getSource().sendSuccess(() -> Component.literal("LootInjector Fabric config reloaded."), false);
                                return 1;
                            }))
                    .then(Commands.literal("search")
                            .then(Commands.argument("type", StringArgumentType.word())
                                    .then(Commands.argument("query", StringArgumentType.greedyString())
                                            .executes(context -> {
                                                TargetType type = TargetType.fromString(StringArgumentType.getString(context, "type"));
                                                if (type == null) {
                                                    context.getSource().sendFailure(Component.literal("Unknown type. Use structure|loot_table|mob|villager|enchant_table"));
                                                    return 0;
                                                }
                                                String query = StringArgumentType.getString(context, "query");
                                                java.util.List<String> matches = switch (type) {
                                                    case STRUCTURE -> store.searchStructures(query);
                                                    case LOOT_TABLE -> store.searchLootTables(query);
                                                    case MOB -> store.searchMobs(query);
                                                    case VILLAGER -> store.searchVillagers(query);
                                                    case ENCHANT_TABLE -> store.searchEnchantTargets(query);
                                                };
                                                context.getSource().sendSuccess(() -> Component.literal("Matches: " + matches.size()), false);
                                                for (int i = 0; i < Math.min(20, matches.size()); i++) {
                                                    int index = i;
                                                    context.getSource().sendSuccess(() -> Component.literal(" - " + matches.get(index)), false);
                                                }
                                                return 1;
                                            }))))
                    .then(Commands.literal("add")
                            .then(Commands.argument("type", StringArgumentType.word())
                                    .then(Commands.argument("id", StringArgumentType.word())
                                            .then(Commands.argument("chance", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                    .then(Commands.argument("item_id", StringArgumentType.word())
                                                            .executes(context -> {
                                                                TargetType type = TargetType.fromString(StringArgumentType.getString(context, "type"));
                                                                if (type == null) {
                                                                    context.getSource().sendFailure(Component.literal("Unknown type. Use structure|loot_table|mob|villager|enchant_table"));
                                                                    return 0;
                                                                }
                                                                String id = StringArgumentType.getString(context, "id");
                                                                double chance = DoubleArgumentType.getDouble(context, "chance");
                                                                String itemId = StringArgumentType.getString(context, "item_id");
                                                                Identifier parsed = Identifier.tryParse(itemId);
                                                                if (parsed == null || BuiltInRegistries.ITEM.getOptional(parsed).isEmpty()) {
                                                                    context.getSource().sendFailure(Component.literal("Unknown item id: " + itemId));
                                                                    return 0;
                                                                }
                                                                String targetKey = TargetKey.of(type, id);
                                                                store.addRule(targetKey, "CUSTOM", chance, itemId);
                                                                context.getSource().sendSuccess(() -> Component.literal("Added rule to " + targetKey), false);
                                                                return 1;
                                                            }))))))
                    .then(Commands.literal("addhand")
                            .then(Commands.argument("type", StringArgumentType.word())
                                    .then(Commands.argument("id", StringArgumentType.word())
                                            .then(Commands.argument("chance", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                    .executes(context -> {
                                                        TargetType type = TargetType.fromString(StringArgumentType.getString(context, "type"));
                                                        if (type == null) {
                                                            context.getSource().sendFailure(Component.literal("Unknown type. Use structure|loot_table|mob|villager|enchant_table"));
                                                            return 0;
                                                        }
                                                        ServerPlayer player = context.getSource().getPlayer();
                                                        if (player == null) {
                                                            context.getSource().sendFailure(Component.literal("Run this as a player."));
                                                            return 0;
                                                        }
                                                        ItemStack hand = player.getMainHandItem();
                                                        if (hand.isEmpty()) {
                                                            context.getSource().sendFailure(Component.literal("Hold an item in your main hand."));
                                                            return 0;
                                                        }
                                                        String itemId = BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
                                                        String targetKey = TargetKey.of(type, StringArgumentType.getString(context, "id"));
                                                        double chance = DoubleArgumentType.getDouble(context, "chance");
                                                        store.addRule(targetKey, "CUSTOM", chance, itemId);
                                                        context.getSource().sendSuccess(() -> Component.literal("Added main-hand item to " + targetKey), false);
                                                        return 1;
                                                    })))))
                    .then(Commands.literal("block")
                            .then(Commands.argument("type", StringArgumentType.word())
                                    .then(Commands.argument("id", StringArgumentType.word())
                                            .then(Commands.argument("item_id", StringArgumentType.word())
                                                    .executes(context -> {
                                                        TargetType type = TargetType.fromString(StringArgumentType.getString(context, "type"));
                                                        if (type == null) {
                                                            context.getSource().sendFailure(Component.literal("Unknown type. Use structure|loot_table|mob|villager|enchant_table"));
                                                            return 0;
                                                        }
                                                        String itemId = StringArgumentType.getString(context, "item_id");
                                                        Identifier parsed = Identifier.tryParse(itemId);
                                                        if (parsed == null || BuiltInRegistries.ITEM.getOptional(parsed).isEmpty()) {
                                                            context.getSource().sendFailure(Component.literal("Unknown item id: " + itemId));
                                                            return 0;
                                                        }
                                                        String targetKey = TargetKey.of(type, StringArgumentType.getString(context, "id"));
                                                        store.addRule(targetKey, "REMOVE", 100.0D, itemId);
                                                        context.getSource().sendSuccess(() -> Component.literal("Added remove/block rule to " + targetKey), false);
                                                        return 1;
                                                    })))))
                    .then(Commands.literal("blockhand")
                            .then(Commands.argument("type", StringArgumentType.word())
                                    .then(Commands.argument("id", StringArgumentType.word())
                                            .executes(context -> {
                                                TargetType type = TargetType.fromString(StringArgumentType.getString(context, "type"));
                                                if (type == null) {
                                                    context.getSource().sendFailure(Component.literal("Unknown type. Use structure|loot_table|mob|villager|enchant_table"));
                                                    return 0;
                                                }
                                                ServerPlayer player = context.getSource().getPlayer();
                                                if (player == null) {
                                                    context.getSource().sendFailure(Component.literal("Run this as a player."));
                                                    return 0;
                                                }
                                                ItemStack hand = player.getMainHandItem();
                                                if (hand.isEmpty()) {
                                                    context.getSource().sendFailure(Component.literal("Hold an item in your main hand."));
                                                    return 0;
                                                }
                                                String itemId = BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
                                                String targetKey = TargetKey.of(type, StringArgumentType.getString(context, "id"));
                                                store.addRule(targetKey, "REMOVE", 100.0D, itemId);
                                                context.getSource().sendSuccess(() -> Component.literal("Added remove/block rule to " + targetKey), false);
                                                return 1;
                                            }))))
                    .then(Commands.literal("clear")
                            .then(Commands.argument("type", StringArgumentType.word())
                                    .then(Commands.argument("id", StringArgumentType.word())
                                            .executes(context -> {
                                                TargetType type = TargetType.fromString(StringArgumentType.getString(context, "type"));
                                                if (type == null) {
                                                    context.getSource().sendFailure(Component.literal("Unknown type. Use structure|loot_table|mob|villager|enchant_table"));
                                                    return 0;
                                                }
                                                String targetKey = TargetKey.of(type, StringArgumentType.getString(context, "id"));
                                                store.clearTarget(targetKey);
                                                context.getSource().sendSuccess(() -> Component.literal("Cleared " + targetKey), false);
                                                return 1;
                                            }))))
                    .then(Commands.literal("debughere")
                            .executes(context -> handleDebugHere(context.getSource())));
            dispatcher.register(root);
        });

        LootTableEvents.MODIFY_DROPS.register((tableHolder, lootContext, drops) -> {
            String rawKey = tableHolder.unwrapKey().map(Object::toString).orElse("");
            if (rawKey.isBlank()) {
                return;
            }
            String id = extractResourceId(rawKey);
            if (id.isBlank()) {
                return;
            }
            applyRulesForTarget(TargetKey.of(TargetType.LOOT_TABLE, id), drops);

            for (String structureAlias : structureAliasesForLootTable(id)) {
                applyRulesForTarget(TargetKey.of(TargetType.STRUCTURE, structureAlias), drops);
            }

            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null) {
                return;
            }
            String path = parsed.getPath();
            if (path.startsWith("entities/")) {
                String entityId = parsed.getNamespace() + ":" + path.substring("entities/".length());
                applyRulesForTarget(TargetKey.of(TargetType.MOB, entityId), drops);
            }
            if (path.contains("chests/")) {
                String structureGuess = parsed.getNamespace() + ":" + path.substring(path.lastIndexOf('/') + 1);
                applyRulesForTarget(TargetKey.of(TargetType.STRUCTURE, structureGuess), drops);
            }
        });
    }

    private void applyRulesForTarget(String targetKey, List<ItemStack> drops) {
        if (!store.hasRules(targetKey)) {
            return;
        }
        List<FabricStoredRule> remove = new ArrayList<>();
        List<FabricStoredRule> add = new ArrayList<>();
        for (FabricStoredRule rule : store.rulesFor(targetKey)) {
            if ("REMOVE".equalsIgnoreCase(rule.type())) {
                remove.add(rule);
            } else {
                add.add(rule);
            }
        }

        if (!remove.isEmpty()) {
            drops.removeIf(drop -> drop != null && !drop.isEmpty() && matchesAny(remove, drop));
        }

        for (FabricStoredRule rule : add) {
            if (roll(rule.chance())) {
                ItemStack stack = FabricRuleStore.toStack(rule);
                if (!stack.isEmpty()) {
                    drops.add(stack.copy());
                }
            }
        }
    }

    private boolean matchesAny(List<FabricStoredRule> remove, ItemStack stack) {
        for (FabricStoredRule rule : remove) {
            ItemStack blocked = FabricRuleStore.toStack(rule);
            if (!blocked.isEmpty() && blocked.getItem() == stack.getItem()) {
                return true;
            }
        }
        return false;
    }

    private boolean roll(double chance) {
        double safe = Math.max(0.0D, Math.min(100.0D, chance));
        return ThreadLocalRandom.current().nextDouble(100.0D) <= safe;
    }

    private int handleDebugHere(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player."));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        source.sendSuccess(() -> Component.literal("DebugHere @ " + level.dimension()
                + " [" + player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ() + "]"), false);

        Set<String> structures = structuresFromCurrentChunk(player);
        if (structures.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Chunk structures: none detected"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Chunk structures (" + structures.size() + "):"), false);
            for (String structureId : structures) {
                source.sendSuccess(() -> Component.literal(" - official: " + structureId), false);
                source.sendSuccess(() -> Component.literal("   inject key: " + TargetKey.of(TargetType.STRUCTURE, structureId)), false);
            }
        }

        List<String> nearbyMobs = nearbyMobTypes(player, 64.0D);
        if (nearbyMobs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Area mobs: none currently loaded nearby"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Area mobs (observed nearby): " + nearbyMobs.size()), false);
            for (int i = 0; i < nearbyMobs.size(); i++) {
                String mobId = nearbyMobs.get(i);
                source.sendSuccess(() -> Component.literal(" - " + mobId), false);
            }
        }

        HitResult hit = player.pick(12.0D, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            source.sendSuccess(() -> Component.literal("Facing container: none (look at chest/barrel/etc within 12 blocks)"), false);
            return 1;
        }

        var blockEntity = level.getBlockEntity(blockHit.getBlockPos());
        if (blockEntity == null) {
            source.sendSuccess(() -> Component.literal("Facing block has no block entity/container."), false);
            return 1;
        }

        String lootTableId = extractLootTableId(blockEntity);
        if (lootTableId == null || lootTableId.isBlank()) {
            source.sendSuccess(() -> Component.literal("Facing container has no loot table assigned."), false);
            source.sendSuccess(() -> Component.literal("This usually means the container already rolled loot and consumed its table pointer."), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("Facing loot table (official): " + lootTableId), false);
        source.sendSuccess(() -> Component.literal("Inject key (loot_table): " + TargetKey.of(TargetType.LOOT_TABLE, lootTableId)), false);

        Set<String> aliases = structureAliasesForLootTable(lootTableId);
        if (aliases.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Inject structure aliases: none inferred"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Inject structure aliases:"), false);
            for (String alias : aliases) {
                source.sendSuccess(() -> Component.literal(" - official: " + alias), false);
                source.sendSuccess(() -> Component.literal("   inject key: " + TargetKey.of(TargetType.STRUCTURE, alias)), false);
            }
        }

        source.sendSuccess(() -> Component.literal("Expected base items: unavailable on Fabric debug mode (runtime-only view)."), false);
        return 1;
    }

    private Set<String> structuresFromCurrentChunk(ServerPlayer player) {
        Set<String> out = new LinkedHashSet<>();
        try {
            ServerLevel level = (ServerLevel) player.level();
            Object chunk = level.getChunk(player.chunkPosition().x, player.chunkPosition().z);
            if (chunk == null) {
                return out;
            }
            java.lang.reflect.Method method = chunk.getClass().getMethod("getAllStarts");
            Object raw = method.invoke(chunk);
            if (!(raw instanceof Map<?, ?> map)) {
                return out;
            }
            for (Object keyObj : map.keySet()) {
                if (keyObj == null) {
                    continue;
                }
                String id = tryExtractStructureId(keyObj);
                if (id != null && !id.isBlank()) {
                    out.add(id.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private String tryExtractStructureId(Object keyObj) {
        String raw = keyObj.toString();
        Matcher matcher = RESOURCE_ID_PATTERN.matcher(raw.toLowerCase(Locale.ROOT));
        return matcher.find() ? matcher.group() : null;
    }

    private List<String> nearbyMobTypes(ServerPlayer player, double radius) {
        Set<String> out = new LinkedHashSet<>();
        ServerLevel level = (ServerLevel) player.level();
        for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(radius),
                candidate -> candidate instanceof LivingEntity && !(candidate instanceof ServerPlayer))) {
            if (!(entity instanceof LivingEntity)) {
                continue;
            }
            if (entity instanceof ServerPlayer) {
                continue;
            }
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (id != null && !id.isBlank()) {
                out.add(id.toLowerCase(Locale.ROOT));
            }
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private String extractLootTableId(Object blockEntity) {
        for (String methodName : List.of("getLootTable", "lootTable", "getLootTableId", "lootTableId")) {
            try {
                java.lang.reflect.Method method = blockEntity.getClass().getMethod(methodName);
                Object value = method.invoke(blockEntity);
                if (value == null) {
                    continue;
                }
                String asString = value.toString();
                if (!asString.isBlank()) {
                    return sanitizeLootTableString(asString);
                }
            } catch (Throwable ignored) {
            }
        }
        for (String fieldName : List.of("lootTable", "lootTableId")) {
            try {
                java.lang.reflect.Field field = blockEntity.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(blockEntity);
                if (value == null) {
                    continue;
                }
                String asString = value.toString();
                if (!asString.isBlank()) {
                    return sanitizeLootTableString(asString);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String sanitizeLootTableString(String raw) {
        String out = raw.trim().toLowerCase(Locale.ROOT);
        out = out.replace("resourcekey[minecraft:loot_table / ", "");
        out = out.replace("resourcekey[", "").replace("]", "");
        return out.trim();
    }

    private Set<String> structureAliasesForLootTable(String lootTableId) {
        Set<String> out = new LinkedHashSet<>();
        if (lootTableId == null || lootTableId.isBlank()) {
            return out;
        }
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

    private void addStructureAliasCandidates(Set<String> out, String namespace, String tablePath) {
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

    private void addCanonicalStructureAliases(Set<String> out, String namespace, String tablePath) {
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

    private boolean isLikelyStructureContainerTable(String path) {
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

    /*
     * Some mappings stringify ResourceKey as: ResourceKey[minecraft:loot_table / minecraft:chests/xxx]
     * Extract the right-side identifier robustly.
     */
    private static String extractResourceId(String raw) {
        String work = raw == null ? "" : raw.trim();
        int slash = work.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < work.length()) {
            work = work.substring(slash + 1);
        }
        work = work.replace("]", "").trim();
        return work;
    }
}
