# A7-01 generation research checkpoint

Status: original pinned-source research, supplemented by the A7-01 resolution below. No production generation implementation. See [approved gameplay policies](../../architecture/planned-a7-a10.md), [feasibility plan](a7-feasibility-plan.md) and [template inventory](a7-template-inventory.md).

## Evidence and result

Inspected local `build/moddev/artifacts/neoforge-21.1.252-sources.jar` and `neoforge-21.1.252-client-extra-aka-minecraft-resources.jar`; these match the project's pinned Minecraft 1.21.1 / NeoForge 21.1.252 development artifacts. The resource inventory records its artifact hash. No Gradle, tests, game launch or world writes were performed. One read-only `economy_researcher` inspected generation hooks while the main agent inspected resources and prepared this checkpoint.

The feasible integration window to investigate is **after vanilla jigsaw layout selection, before construction of the immutable structure start**. Counting arbitrary pieces in a houses pool is wrong: those pools also contain farms, animal pens and decorative/meeting pieces. Additional weighted pool entries cannot satisfy the approved guaranteed bank counts.

### Pinned source locations

Paths below are entries inside the source archive, not repository files.

| Source | Finding |
| --- | --- |
| `net/minecraft/world/level/levelgen/structure/Structure.java`, `generate()` | Materializes `GenerationStub.getPiecesBuilder()` and builds `StructureStart`; this is the proposed pre-freeze planning window |
| `net/minecraft/world/level/levelgen/structure/structures/JigsawStructure.java`, `findGenerationPoint()` | Delegates layout creation to `JigsawPlacement.addPieces()`; the class is final |
| `net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement.java`, `addPieces()` | Returns a stub whose deferred generation collects and expands the original layout before adding pieces to the builder |
| `net/minecraft/world/level/levelgen/structure/StructureStart.java` | Final start/container and cached bounds make late append unsuitable; `placeInChunk()` performs bounded per-chunk piece placement and then `afterPlace()` |
| `net/minecraft/world/level/levelgen/structure/pieces/PiecesContainer.java` | Copies the piece list; exposing pieces is not a supported append mechanism |
| `net/minecraft/world/level/levelgen/structure/StructureStart.java`, `createTag()` | Saves registered structure identity and serialized children; custom bank pieces require registered loaders and persistent metadata |
| `net/minecraft/world/level/chunk/status/ChunkStatusTasks.java` | Structure starts are generated in the chunk-status pipeline; FULL conversion/chunk load is after FEATURES |
| `net/neoforged/neoforge/event/level/ChunkEvent.java` | New-chunk load detection is too late for the proposed layout/bounds planning and carries chunk-load deadlock cautions |

Targeted NeoForge source review found no public completed-piece-list generation event. Structure modifiers change structure settings rather than append completed layout pieces. This is a bounded search result, not a claim that every possible extension mechanism was exhaustively ruled out.

## Candidate strategy

Preferred research candidate: a registered custom structure type that composes vanilla jigsaw generation for the five vanilla village structure definitions, materializes the original layout, counts classified house/workplace instances and appends registered bank/path pieces before the start is finalized. Preserve the original layout and capture all bank choices once. Include bank/path extents before structure references are established.

This requires deliberate replacement of the five structure definitions and explicit handling of datapack overrides. It is a proposal, not an approved compatibility expansion or an installed hook. Review locate/structure-tag behavior, codecs, save/load, terrain sampling and vanilla start parameters before selecting it.

Alternative: a narrowly filtered injection at the pre-freeze point in `Structure.generate()`. This preserves the vanilla structure definitions but adds version-sensitive transformation wiring. The project has not selected or installed this alternative.

Do not use chunk-load callbacks to discover arbitrary villages and expand them later. Chunk load may support deferred activation of already planned hardware, with ordinary server-thread observations. Generation must not wait for SQLite or touch live banking services: persist generation-local metadata in registered pieces/markers, then reconcile it through the bounded existing storage/journal services when loaded. The reconciliation design must distinguish incomplete initial placement from later player dismantling.

## Template inventory findings

Ordinary houses pools contain 26/23/25/26/23 candidate house/workplace resource identities for plains/desert/savanna/snowy/taiga respectively. These are **not expected village building counts**. Each pool also has an empty entry and excluded candidates. Animal pens and stables, including both plains stable templates, are approved exclusions from the building count.

The five town-center pools contain both ordinary and abandoned starts. Approved scope: abandoned villages receive no A7 content, including banks, paths, hardware, desks or bankers. Detect and exclude abandoned starts before bank planning; no branch guarantee applies to them. Approved zero-count policy: a non-abandoned village with zero qualifying houses/workplaces receives no bank and is excluded from bank planning and branch requirements. Unknown/replaced template definitions still need explicit handling before implementation.

## Remaining feasibility gate

Neither candidate proves a safe site always exists. The approved policy now handles exhausted search explicitly: try interior sites, nearby edge sites with connecting paths and foundations/modest leveling, then somewhat farther connected sites; if none works, retain a persistent pending branch plan and report the reason. Preserve existing buildings, farms, roads and player construction. Every targeted village receives a plan, but immediate placement is not guaranteed. A pending plan is not a completed bank. Before A7-04, establish bank/path envelopes, search distances and terrain limits, evaluate the pinned approach in disposable scenarios, and specify safe pending-plan recovery without overwriting later construction.

Next design outputs: select the hook strategy; finish content-based classification and abandoned-start detection; define plan serialization, denomination capture timing and bounds; then specify site search and recoverable server activation. A7-02 and later packages remain unselected.

Approved initial terrain limits are up to 3 blocks of leveling and 6 blocks of foundation depth, pending terrain validation. These constrain the placement design; they are not validated feasibility evidence. Define how both limits are measured before implementation.

Approved search distances are up to 32 blocks for nearby sites and up to 96 blocks for farther fallback sites, measured outward from the original village boundary. Require a safe connecting path to the bank entrance. Precise boundary/site-distance definitions and terrain feasibility remain design work.

Approved recovery for plans pending because no safe site exists: operator-triggered retries only, with fresh site checks and preservation of later construction. Keep captured branch choices. Retry authority does not bypass placement protections or rebuild dismantled banks. Interrupted generation/activation recovery remains a separate technical design.

Approved provider-availability exception: when an optional branch is selected but no distinct eligible institution remains after the approved foreign-provider fallback, omit only that optional branch and report the reason. Persist the selection/omission without rerolling. Required branches retain the configuration not-ready policy; terrain failures still retain pending placement plans.

Verification for this checkpoint: document diff and local-link review only, following [VERIFICATION.md](../../VERIFICATION.md). No automatic test, full-suite or game-launch gate is introduced.

## A7-01 resolution — 2026-11-06

The selected composition hook is source-feasible through public APIs: `JigsawStructure.findGenerationPoint(context)` is public, so no access transformer or injection is needed. Nested `JigsawStructure.CODEC` retains all vanilla parameters. Materialize the original deferred builder exactly once; preserve its original pieces/order and return a stub with the same position and materialized augmented builder (`Either.right`). The five definitions retain size6, distance80, absolute start height0, expansion=true, WORLD_SURFACE_WG and beard_thin adaptation. Keep original structure registry keys for locate/tags/start serialization; outer wrapper settings remain authoritative for NeoForge modifiers.

Two important constraints were confirmed in pinned source. `ChunkGenerator.createReferences` scans only an eight-chunk radius: full A7 physical bounds must remain within reference reach even where the approved 96-block outward band extends farther. `PiecesContainer.load` skips unknown/corrupt child pieces, so manifest/anchor completeness must be checked before activation. These are explicit pending/conflict outcomes rather than silent missing branches.

Read element identities through `StructurePoolElement.CODEC` rather than private fields or toString. Detect abandonment from the exact audited selected root template, not zombie processors: snowy abandoned starts can use empty processor lists. The [content audit](a7-template-inventory.md) validates all 123 included and 29 excluded ordinary house-pool resources, plus all 32 root variants.

[Revision-1 shared contracts](a7-shared-contracts.md) supersede the open design items above. Initial physical effects use the ordinary chunk-generation window; loaded-world operator retries are additive only and cannot clear existing blocks. Missing terrain yields persistent pending plans; uncertain partial placement is conflict and never automatic repair. Provider readiness, denomination capture and storage reconciliation are detached from generation. No global safe-site guarantee is made.

Evidence is pinned source inspection and actual resource-content audit. Registered wrapper serialization, five-style world generation and physical terrain placement have not run: A7-04 must verify the implemented adapter with disposable worlds. This checkpoint establishes implementable API/serialization contracts, not runtime results for code that does not yet exist.
