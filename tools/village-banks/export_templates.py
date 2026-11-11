#!/usr/bin/env python3
"""Create the village bank variants from the Crownstone structure-block export."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools/village-banks/genmc/plains.json.gz"
DATA = ROOT / "src/main/resources/data/economy"
TARGET = DATA / "structure/banks"
BANKS = json.loads((ROOT / "tools/banking-assets/brands.json").read_text())["banks"]
DIMENSIONS = [17, 15, 17]
TERMINALS = {"economy:white_atm": "economy:terminal", "economy:branch_kiosk": "economy:terminal"}
DESK = "economy:banker_desk"
ENTRANCE = (0, 1, 8)
NUMBERS = dict(enumerate("bhiqfd", start=1))
ARRAYS = {7: 1, 11: 4, 12: 8}


def unpack(stream, fmt):
    return struct.unpack(">" + fmt, stream.read(struct.calcsize(">" + fmt)))[0]


def read_string(stream):
    # Round-trip Java's modified UTF-8 bytes, including any non-ASCII sign text.
    return stream.read(unpack(stream, "H")).decode("utf-8", "surrogateescape")


def read_tag(stream, kind):
    if kind in NUMBERS:
        return unpack(stream, NUMBERS[kind])
    if kind == 8:
        return read_string(stream)
    if kind == 9:
        child = unpack(stream, "B")
        return child, [read_tag(stream, child) for _ in range(unpack(stream, "i"))]
    if kind == 10:
        result = {}
        while child := unpack(stream, "B"):
            key = read_string(stream)
            result[key] = child, read_tag(stream, child)
        return result
    if kind in ARRAYS:
        length = unpack(stream, "i")
        return length, stream.read(length * ARRAYS[kind])
    raise ValueError(f"Unsupported NBT tag {kind}")


def write_string(value):
    encoded = value.encode("utf-8", "surrogateescape")
    return struct.pack(">H", len(encoded)) + encoded


def write_tag(kind, value):
    if kind in NUMBERS:
        return struct.pack(">" + NUMBERS[kind], value)
    if kind == 8:
        return write_string(value)
    if kind == 9:
        child, entries = value
        return struct.pack(">Bi", child, len(entries)) + b"".join(write_tag(child, entry) for entry in entries)
    if kind == 10:
        return b"".join(bytes([tag]) + write_string(key) + write_tag(tag, data)
                        for key, (tag, data) in value.items()) + b"\0"
    if kind in ARRAYS:
        length, encoded = value
        return struct.pack(">i", length) + encoded
    raise ValueError(f"Unsupported NBT tag {kind}")


def read_structure(source):
    stream = io.BytesIO(gzip.decompress(source.read_bytes()))
    if unpack(stream, "B") != 10:
        raise ValueError("Expected an NBT compound")
    name = read_string(stream)
    structure = read_tag(stream, 10)
    if stream.read(1):
        raise ValueError("Unexpected data after the NBT compound")
    return name, structure


def brand_dyes():
    # The archived GenMC exports use the same concrete trim in all five styles.
    # Sample the original primary and accent trim on each compact branch's facade.
    rows = json.loads(gzip.decompress(SOURCE.read_bytes()))["build"]["blocks"]
    blocks = {tuple(row[:3]): row[3] for row in rows}
    colors = {}
    for index, bank in enumerate(BANKS):
        x, z = 2 + (index % 5) * 36, 2 + (index // 5) * 16
        concrete = [blocks[x, 5, z], blocks[x + 3, 5, z]]
        if not all(block.endswith("_concrete") for block in concrete):
            raise ValueError(f"Missing archived trim for {bank['key']}")
        colors[bank["key"]] = tuple(block.removesuffix("_concrete") for block in concrete)
    colors["crownstone"] = ("blue", "yellow")
    return colors


def prepare_crownstone(structure):
    if structure["size"][1][1] not in (DIMENSIONS, [17, 17, 17]) or structure["entities"][1][1]:
        raise ValueError("Expected a 17×15×17 Crownstone bank or its 17-block-high export without entities")
    palette = structure["palette"][1][1]
    blocks = []
    anchors = {name: [] for name in (*TERMINALS, DESK)}
    for block in structure["blocks"][1][1]:
        position = tuple(block["pos"][1][1])
        state = palette[block["state"][1]]
        name = state["Name"][1]
        if position[1] >= DIMENSIONS[1]:
            if name != "minecraft:air":
                raise ValueError(f"Cannot crop non-air block at {position}")
            continue
        if name in anchors and state["Properties"][1]["cell"] == (8, "0"):
            anchors[name].append(position)
            # The existing staffing and activation paths assign fresh world-local identities.
            block["nbt"] = (10, {"id": (8, TERMINALS.get(name, DESK))})
        blocks.append(block)

    by_position = {tuple(block["pos"][1][1]): block for block in blocks}
    grass = {"Name": (8, "minecraft:grass_block"), "Properties": (10, {"snowy": (8, "false")})}
    if grass not in palette:
        palette.append(grass)
    grass_index = palette.index(grass)
    width, _, depth = DIMENSIONS
    for (x, y, z), block in by_position.items():
        if y == 0 and (x in (0, width - 1) or z in (0, depth - 1)):
            block["state"] = (3, grass_index)
    # Carry the west entrance floor across the outer margin to the generated path.
    by_position[0, 0, 8]["state"] = by_position[1, 0, 8]["state"]
    structure["blocks"] = (9, (10, blocks))
    structure["size"] = (9, (3, DIMENSIONS))
    expected = {
        "economy:white_atm": [(8, 1, 11), (8, 1, 14)],
        "economy:branch_kiosk": [(10, 1, 6)],
        DESK: [(12, 1, 10)],
    }
    if {name: sorted(positions) for name, positions in anchors.items()} != expected:
        raise ValueError("Crownstone hardware anchors differ from the generation layout")
    return anchors


def variant_palette(palette, index, primary, accent):
    dyes = {f"minecraft:{old}_{material}": f"minecraft:{new}_{material}"
            for old, new in (("blue", primary), ("yellow", accent))
            for material in ("wool", "carpet")}
    result = []
    for state in palette:
        name = state["Name"][1]
        variant = dict(state)
        if name in dyes:
            variant["Name"] = (8, dyes[name])
        if name in TERMINALS:
            properties = dict(state["Properties"][1])
            properties["bank"] = (8, str(index))
            variant["Properties"] = (10, properties)
        result.append(variant)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, nargs="?", default=TARGET / "crownstone.nbt",
                        help="structure-block NBT export (defaults to the packaged Crownstone template)")
    args = parser.parse_args()
    root_name, structure = read_structure(args.source)
    anchors = prepare_crownstone(structure)
    colors = brand_dyes()
    palette = structure["palette"][1][1]
    templates = []
    for index, bank in enumerate(BANKS, start=1):
        key = bank["key"]
        primary, accent = colors[key]
        variant = dict(structure, palette=(9, (10, variant_palette(palette, index, primary, accent))))
        encoded = b"\x0a" + write_string(root_name) + write_tag(10, variant)
        packed = gzip.compress(encoded, mtime=0)
        (TARGET / f"{key}.nbt").write_bytes(packed)
        templates.append({
            "bank": key, "currency": bank["currency"], "resource": f"economy:banks/{key}",
            "dimensions": DIMENSIONS, "entrance": ENTRANCE, "entrance_facing": "west",
            "services": anchors["economy:branch_kiosk"] + sorted(anchors["economy:white_atm"]),
            "desk_slots": anchors[DESK], "primary_dye": primary, "accent_dye": accent,
            "sha256": hashlib.sha256(packed).hexdigest(),
        })
    manifest = {"version": 2, "source": "Crownstone structure block export", "templates": templates}
    (DATA / "worldgen/bank_templates.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Exported {len(templates)} branded 17×15×17 bank templates")


if __name__ == "__main__":
    main()
