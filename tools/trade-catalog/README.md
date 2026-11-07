# Pinned trade quantity manifest

`extract_quantities.py` reads the generated Minecraft 1.21.1 / NeoForge 21.1.252 source archive and writes original factual quantity data. It verifies both archive and VillagerTrades source SHA-256, statically parses every listing constructor in ordinary/experimental/wandering catalogs and helper bodies, and rejects changed constructor coverage. It never runs a listing factory or Minecraft.

```sh
python3 tools/trade-catalog/extract_quantities.py build/moddev/artifacts/neoforge-21.1.252-sources.jar src/main/resources/data/economy/trade/standard_quantities_v1.json
```

The manifest contains308 base-item families. Fixed output/input constructor counts supply minima; component variants inherit the same base quantity. Secondary ingredients are excluded. The potion ItemCost overload supplies count1 rather than treating its maxUses argument as quantity. Biome wrappers expand all mapped boats; special books include the plain-book fallback. Unknown constructors/items fail instead of using a guessed default.

The resource is pinned by SHA-256 in StandardQuantities. A deliberate source/catalog revision requires a new version and historical compatibility, not overwriting v1 to reinterpret saved offers. Native entity extraction belongs to A8-03-02; this tool establishes static domains only.
