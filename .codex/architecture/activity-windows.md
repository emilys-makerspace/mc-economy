# Immutable activity windows and model — A8-02-02

V41 delivers internal worker interfaces only. No production provider, rate publication, new clock, retirement or trader gameplay is installed.

`WindowStore.open(store,boundaryMillis,observedMillis,configId,anchorId)` freezes world/source/boundary/journal frontier and globally seals all currencies before their separate closes. It requires ready capacity seeding and completed, sequential calendar days; every previous currency close must finish first. Coverage starts at the ceiling of activity activation and completed seed time. Partial pre-coverage days are excluded. Later source commits use the first open accounting day while retaining original effective time.

`close(store,openId,currency)` reads at most eleven kind counters, one indexed capacity value strictly before the boundary and at/below the input frontier, and thirteen earlier summaries. Ready seeding makes absent capacity a proven zero. Missing covered summaries reject as unavailable. Snapshot facts preserve at most fourteen contiguous gross daily summaries and their SHA-256 hash. Detached BigInteger aggregates use the latest up to seven days W and preceding up to seven days B. Historical closes remain stored after aging out. Expected pending/out-of-order work reports ECONOMIC_CATCH_UP without poisoning storage.

`MacroModel.compute(Input)` is pure, using reduced rational arithmetic and activation-anchor CAD normalization. It implements shared v1 spending/credit/money/FX formulas, Q=1000 CAD/day, capacity factor .01 and equal weights. Prime is bounded to [0,.25], step .001, HALF_EVEN six decimals. FX intersects every ordered-pair/rate constraint with one largest exact feasible alpha, rounds to twelve decimals and rechecks all bounds; deterministic 64-step bisection retains valid witnessed vectors with the valid prior fallback. Invalid priors reject. Empty covered W holds rates; missing coverage is unavailable. Empty individual currencies have zero own pressure.

`ModelPlanStore.prepare` requires eleven closed snapshots. It records canonical model input/output once, including snapshot hash, config UUID/revision, anchors, prior rates, exact signals, common alpha, explanations and output hash. Original operation replay reads the saved plan. Indexed identity bindings reject changed anchors under one UUID or changed config contents under one UUID/revision. Opens preserve the anchor identity; future manifest/anchor extensions require explicit implementation. Future provider children must consume saved plans, not recompute against partial publications.

V41 adds macro_window_opens, macro_window_closes and activity_model_plans plus indexed/immutable projections. Explicit v1 codecs are registered. Startup/recovery and offline clean-backup validation check declarations, fact/projection linkage, coverage, chronology/global seals, source counters and historical capacity; plan validation reconstructs frozen input and v1 output. Clean restore admits V41, retaining the stopped complete-world upgrade-backup requirement and frozen old migrations. Each call commits one open/close/plan; existing 16KiB facts, 64-record and 128KiB transaction limits remain authoritative and oversized compositions fail atomically.

[Handoff](../A8/A8-02-02.md) and [verification](../A8/verification/A8-02-02.md) record actual checks. Next selectable package is A8-03-01. Production scheduling/publication remains later A8 scope.


## A8-05 activation horizon

[ActivationModelPlans and currency prime](currency-prime.md) add a distinct V46 input/output source that slices these immutable day summaries at future macro activation A, binds actual day indices and hashes, and preserves v1 opens/closes/plans. The full A8-06 provider must use that scoped source for prospective rate publication rather than counting pre-A days as warm-up. This addition registers no production provider.
