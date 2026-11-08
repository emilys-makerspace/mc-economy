"""Extract facts from the pinned source, without loading Minecraft or random factories.

All static catalogs, experimental catalogs and helper bodies precede listing classes.
Counts are constructor domains, never sampled runtime offers. Secondary ingredients
are excluded from principal goods. Component variants inherit their base-item minimum.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile

ARTIFACT = "140360abbb394e8e3f282cdd88281f7c7da8ec94bd9a3a38dec3a1b3db25190b"
SOURCE = "1e279519a2a87c59b7d50a0a40889e3e9381e281ca74f87c3fd40c6400865c41"
ENTRY = "net/minecraft/world/entity/npc/VillagerTrades.java"


def arguments(text, start):
    depth, angle, quoted, escape, current, result = 1, 0, False, False, "", []
    for char in text[start:]:
        if quoted:
            current += char
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
            current += char
        elif char == '(':
            depth += 1
            current += char
        elif char == ')':
            depth -= 1
            if depth == 0:
                return result + [current.strip()]
            current += char
        elif char == '<':
            angle += 1
            current += char
        elif char == '>':
            angle = max(0, angle - 1)
            current += char
        elif char == ',' and depth == 1 and angle == 0:
            result.append(current.strip())
            current = ""
        else:
            current += char
    raise ValueError("Unclosed source constructor")


def item(expression):
    match = re.search(r"(?:Items|Blocks)\.([A-Z_]+)", expression)
    if match:
        return "minecraft:" + match[1].lower()
    if re.fullmatch(r"potion(?:Cost)?\(Potions\.[A-Z_]+\)", expression):
        return "minecraft:potion"
    raise ValueError("Unresolved static item: " + expression)


def extract(artifact):
    raw = Path(artifact).read_bytes()
    if hashlib.sha256(raw).hexdigest() != ARTIFACT:
        raise ValueError("Pinned source artifact changed")
    with ZipFile(artifact) as archive:
        raw_source = archive.read(ENTRY)
    if hashlib.sha256(raw_source).hexdigest() != SOURCE:
        raise ValueError("Pinned trade source changed")
    text = raw_source.decode().split("    public static class DyedArmorForEmeralds", 1)[0]
    quantities, calls = {}, Counter()

    def add(key, quantity):
        quantity = int(quantity)
        if quantity <= 0 or key == "minecraft:emerald":
            raise ValueError("Invalid principal goods")
        quantities[key] = min(quantity, quantities.get(key, quantity))

    for match in re.finditer(r"new VillagerTrades\.([A-Za-z]+)\(", text):
        name = match[1]
        args = arguments(text, match.end())
        calls[name] += 1
        if name == "ItemsForEmeralds":
            add(item(args[0]), args[2])
        elif name == "EmeraldForItems":
            add(item(args[0]), 1 if args[0].startswith("potionCost(") else args[1])
        elif name == "ItemsAndEmeraldsToItems":
            add(item(args[3]), args[4])
        elif name == "TippedArrowForItemsAndEmeralds":
            add(item(args[2]), args[3])
        elif name in ("EnchantedItemForEmeralds", "DyedArmorForEmeralds"):
            add(item(args[0]), 1)
        elif name == "EmeraldsForVillagerTypeItem":
            for mapped in re.findall(r"Items\.([A-Z_]+)", args[3]):
                add("minecraft:" + mapped.lower(), args[0])
        elif name == "EnchantBookForEmeralds":
            add("minecraft:enchanted_book", 1)
            add("minecraft:book", 1)
        elif name == "TreasureMapForEmeralds":
            add("minecraft:filled_map", 1)
        elif name == "SuspiciousStewForEmerald":
            add("minecraft:suspicious_stew", 1)
        elif name not in ("TypeSpecificTrade", "FailureItemListing"):
            raise ValueError("Uncovered listing: " + name)
    expected = dict(ItemsForEmeralds=379, EmeraldForItems=98, ItemsAndEmeraldsToItems=17,
                    EnchantedItemForEmeralds=17, DyedArmorForEmeralds=7,
                    TippedArrowForItemsAndEmeralds=1, EmeraldsForVillagerTypeItem=1,
                    EnchantBookForEmeralds=18, TreasureMapForEmeralds=13,
                    SuspiciousStewForEmerald=6, TypeSpecificTrade=5, FailureItemListing=1)
    if dict(calls) != expected or len(quantities) != 308:
        raise ValueError("Static catalog coverage changed: " + repr((calls, len(quantities))))
    return dict(version=1, minecraft="1.21.1", neoforge="21.1.252", artifact_sha256=ARTIFACT,
                source_sha256=SOURCE, rules="constructor-count-domains-v1", calls=dict(sorted(calls.items())),
                quantities=dict(sorted(quantities.items())))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_archive")
    parser.add_argument("output")
    args = parser.parse_args()
    data = extract(args.source_archive)
    Path(args.output).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
    print(f"Extracted {len(data['quantities'])} static principal-goods families")
