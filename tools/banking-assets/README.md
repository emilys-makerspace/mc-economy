# Original banking art (A6-05)

Run `python3 tools/banking-assets/generate.py` from the repository root, then `python3 tools/banking-assets/verify.py`. Generation uses only Python's standard library. `brands.json` records the approved catalog, palettes and short display labels; the verifier compares these to `InstitutionCatalog` and its approved network matrix.

All new art and geometry in this directory and the generated `banking/` resource directories are original project-authored work, dedicated under **CC0-1.0** (https://creativecommons.org/publicdomain/zero/1.0/). No downloaded images, fonts, real financial marks or copied trade dress were used. This dedication does not relicense existing project assets or Minecraft/NeoForge code. Existing terminal geometry is reused under the project's existing license.

`art.py`, `generate.py`, `hardware.py` and `agent.py` are editable construction sources. `sources/` contains pixel-aligned SVG artwork and geometry source snapshots. `resource_inventory.json` mirrors the shipped manifest at `assets/economy/banking/art_manifest.json`; it lists every issuer, network, card, icon, hardware family and agent resource. `previews/` contains all banks at inventory/UI scale, monochrome identities, hardware fronts and civilian agent views. Card lettering uses the original 3×5 pixel alphabet in `art.py`; DB/rectangle and CR/arrow identify product, network motifs identify networks, and both products use untiered artwork. Debit cards have one appearance per bank and credit cards one per bank/network. Old approved tier-suffixed item IDs remain registry aliases for save compatibility; obsolete tier-specific artwork is removed.

## Physical binding handoff

These are resource-only models, loaded through `BankingArtModels` for model baking. No new items, blocks, recipes, loot, entity registrations or world migrations are introduced. A6-05-01 owns physical registration, bank binding, orientation, safe migration and support/drop semantics. Generic legacy machines retain their current presentation until then. Card art grants no authority: A6-06 must validate server-issued credentials independently of appearance. The eight generic legacy collectible cards have been retired. The Bank & Credit Cards creative tab contains the current issuer designs.

For each bank, `<key>_atm` and `<key>_teller` item models preserve existing inventory geometry. Four block cells are ordered **right, base, upper_right, upper** (0–3), using the shared detailed elements/UVs; rotate all cells together from north-front. ATM screens, bezels and attached controls are recessed four model pixels behind their original position to expose the keypad; plain, dyed, branded and inventory appearances share that recess. Original kiosk geometry has two vertically stacked cells, a slender pedestal, header and distinct base; see manifest placement notes. Every front labels the bank and its native currency, independent of the world's cash currency. Hardware sources include model snapshots and textures.

## Collection Agent handoff

`CollectionAgentGeometry.create()` supplies a 64×64 humanoid layer with nine parts (standard humanoid bones plus body-attached folio and satchel). `collection_agent_model.json` records cube positions and UVs; `sources/agent/collection_agent_uv.svg` is the editable skin. The original Waystone civilian wears a teal coat, brass ledger badge and carries paperwork; no weapons or hostile imagery. A6-11 owns model-layer registration, renderer, entity, animation and all behavior. No agent is spawned by this package.

No persisted entities, schema migrations, journal decoders, money effects, service packets or authorization changes occur. Resource loading is client-only. Resource existence is never evidence of an account, facility or permission.


## A7-03 wide kiosk console

Run `python3 tools/generate_wide_kiosk_assets.py` **after** the base banking-art generator. It authors the new wide console models, horizontal institution/currency headers and explicit `wide=false`/`wide=true` kiosk blockstate variants. The original narrow block models and all existing texture pixels remain unchanged; new item appearances use the broad console while retaining registered item/model IDs and all23 custom-model-data brand mappings.

The new geometry and headers are original project-authored CC0-1.0 work. Construction source is `../generate_wide_kiosk_assets.py`; `sources/hardware/wide-kiosk/geometry.json` records the assembled cuboids and cell origins, and that folder contains the original editable header SVGs. Existing project-authored CC0 banking material maps and original procedural glyphs/marks are reused. No downloaded images, fonts, financial marks or external models were used. `previews/wide-kiosk-fronts.png` is a flat design/contact sheet, not a Minecraft render or runtime-loading witness.

The console spans two blocks wide and two high, facing north before the normal horizontal rotation. Cell0 is the bottom anchor/right-hand world-model half; cell1 is the bottom left-hand half; cells2/3 are their upper partners. Its recessed foot, side cheeks, solid rear assembly, waist-height projecting counter, broad recessed screen and horizontal header remain within each cell's0..16 model bounds and overall north-facing Z1..15. Inventory geometry is uniformly half-sized to fit the standard0..16 item model envelope. Legacy narrow cells0/1 keep their exact old models; invalid narrow cells2/3 use a safe narrow appearance while Java integrity rejects them. Rendering never grants a bank binding or physical upgrade authority.


### Detailed mid-century console surfaces

`python3 tools/generate_wide_kiosk_assets.py` also authors the console-specific
textures in `assets/economy/textures/block/banking/console/`. The shared surfaces
include walnut grain, brushed metal, speckled ivory laminate, an inset green
monitor, separate numbered keycaps, card-reader and cash-throat details, louvers,
a screw-fastened rear service door and its lock. Each of the 23 banks and the
generic console receives its own enamel cabinet and accent-strip textures.
These are original CC0-1.0 pixel drawings made with the project's `Canvas`
primitives and three-by-five alphabet in `art.py`.

The assembled 57 cuboids include raised keys and reader controls, a recessed
screen with independent bezel rails, a cash tray, walnut side cheeks, metal
edges and rear door hardware. The version 2 geometry source in
`sources/hardware/wide-kiosk/geometry.json` records their positions and materials.
The original north-facing front, four cell origins and Z1..15 envelope are
preserved. Cell models are clipped to X/Y0..16 and the inventory version is
half scale. The generator validates those bounds, all required material names,
texture files, model parents and explicit blockstate references.
