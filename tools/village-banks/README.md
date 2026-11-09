# Village bank templates

New village banks use the supplied Crownstone structure for all biomes. The
packaged Crownstone NBT is the canonical 17×15×17 template. Its structure block
export included two empty layers above the roof, which the converter trims. A
cobblestone threshold at the west edge connects the building floor to generated
paths. Grass blocks line all four sides of the base up to the entrance threshold.
The source world's hardware ownership and placement records are cleared
so each generated branch can register its own terminals and banker.

Run `python3 tools/village-banks/export_templates.py` from the repository to
regenerate all 23 variants and the SHA-256 manifest from the packaged template.
Pass the path to a new Crownstone NBT export to refresh the source template.
Each variant uses the institution's established dye colors for the blue and
yellow wool and carpet positions. The two ATMs and wide kiosk use the same
institution's registered hardware states. The shared banker desk supplies one
staffing slot per branch.

The historical GenMC source exports are preserved in `genmc/`. Their facade
trim supplies the established dye names for the other institutions. Crownstone
uses the supplied blue and yellow colors. The original GenMC designs and source
exports are dedicated under CC0-1.0.
