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
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class LootInjectorFabricMod implements ModInitializer {
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
                                            }))));
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
        String marker = "chests/";
        int idx = path.indexOf(marker);
        if (idx < 0 || idx + marker.length() >= path.length()) {
            return out;
        }

        String chestPath = path.substring(idx + marker.length());
        String[] parts = chestPath.split("/");
        if (parts.length == 0) {
            return out;
        }
        String first = parts[0];
        if (!first.isBlank()) {
            out.add(namespace + ":" + first);
        }
        String last = parts[parts.length - 1];
        if (!last.isBlank()) {
            out.add(namespace + ":" + last);
        }

        if (chestPath.startsWith("trial_chambers/")) {
            if (chestPath.contains("ominous")) {
                out.add(namespace + ":trial_chambers_ominous_vault");
            } else if (chestPath.contains("vault") || chestPath.contains("reward")) {
                out.add(namespace + ":trial_chambers_vault");
            } else {
                out.add(namespace + ":trial_chambers");
            }
        }
        return out;
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
