package dev.cevapi.lootinjector.paper;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public record LootRule(@NotNull String id, @NotNull LootRuleType type, double chance, @NotNull ItemStack item) {
}
