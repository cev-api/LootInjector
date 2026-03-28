# LootInjector

LootInjector is a Paper plugin + Fabric mod that gives you one place to control loot across your server. You can edit structure chests, direct loot tables, mob drops, villager trades, and enchanting outcomes, including datapack content. On Paper, it also has an in-game GUI so you can browse sources and tweak chances, add items, or block entries without digging through JSON by hand.

![1](https://i.imgur.com/vxOoiqd.png)
![3](https://i.imgur.com/cxsXa2t.png)
![4](https://i.imgur.com/YPKAwHP.png)

- `paper/`: Paper plugin with GUI + command workflow.
- `fabric/`: Fabric server mod with command workflow + loot runtime injection.
- `common/`: shared `TargetType`, `TargetKey`, search helpers.

## Supported Targets

- `Structure` targets
- `Direct loot_table` targets
- `Mob` drops
- `Villager` trades
- `Enchant table` books
- Trial chamber vault aliases

## Feature Parity Matrix

| Feature | Paper | Fabric |
|---|---|---|
| Runtime injection: loot tables / structures / mobs | Yes | Yes |
| GUI editor | Yes | No |
| Namespace source browser | Yes | No |
| Structure grouping (`namespace -> group -> entries`) | Yes | No GUI (runtime supports grouped path aliases) |
| Structure base loot discovery (vanilla + datapacks) | Yes | No GUI base view |
| Mob source browser and mob editor | Yes | No GUI |
| Villager trade GUI/editor | Yes | No GUI |
| Enchant table GUI/editor | Yes | No GUI |
| Command editing (`add`, `addhand`, `block`, `blockhand`, `clear`, `search`) | Yes | Yes |
| Type-aware searching (structure/loot_table/mob/villager/enchant_table) | Yes | Yes |
| `debughere` command | Yes | Yes |

## Command Reference

Base command: `/lootinjector`

### Shared (Paper + Fabric)

- `/lootinjector reload`
- `/lootinjector search <structure|loot_table|mob|villager|enchant_table> <query>`
- `/lootinjector add <structure|loot_table|mob|villager|enchant_table> <id> <chance> <item_id>`
- `/lootinjector addhand <structure|loot_table|mob|villager|enchant_table> <id> <chance>`
- `/lootinjector block <structure|loot_table|mob|villager|enchant_table> <id> <item_id>`
- `/lootinjector blockhand <structure|loot_table|mob|villager|enchant_table> <id>`
- `/lootinjector clear <structure|loot_table|mob|villager|enchant_table> <id>`
- `/lootinjector debughere`

### Paper-only

- `/lootinjector gui`
- `/lootinjector add <...> [nbt]` (NBT argument supported)
- `/lootinjector block <...> [nbt]` (NBT argument supported)
- `/lootinjector open <structure|loot_table|mob|villager|enchant_table> <id>`
- `/lootinjector debugbase <structure_id>`

## Paper

### GUI

- Main Sources page:
  - Vanilla/datapack structure namespaces
  - Villagers
  - Enchant Table
  - Mob Drops
- Structure and mob lists are filtered to actionable targets (real loot/container-backed or configured).
- Structure browsing is now hierarchical for large datapacks:
  - `Namespace -> Group -> Entries`
  - Example: `incendium -> castle -> castle/blacksmith/chestplate`
  - Includes `All Entries` and `Direct Entries` group shortcuts.
- Mob Drops has namespace drilldown, then per-mob editor.
- Mob icons use vanilla spawn eggs where available, else spawner fallback.
- Structure editor Back returns to the previous structure list page/mode/namespace.
- Editors support:
  - Viewing base entries
  - Add custom rules (drag/drop + add button)
  - Block/unblock entries
  - Chance edits
  - Paging + clear rules

## Fabric

### Runtime

- Injects configured rules during loot-drop modification.
- Applies to:
  - Direct loot table targets
  - Structure aliases inferred from container-style loot tables (chest/barrel/crate/cache/supply/treasure/vault/reward), including nested datapack paths like `namespace:lab/junk`
  - Mob targets inferred from `entities/*` loot tables
- `debughere` on Fabric includes area/chunk/container alias info; base loot item expansion remains Paper-only.

## Permissions

Paper uses `lootinjector.admin` for command access.

## Build

- Paper only: `./gradlew :paper:build`
- Fabric only: `./gradlew :fabric:build`
- Both: `./gradlew :paper:build :fabric:build`

## License

GPLv3. See `LICENSE`.
