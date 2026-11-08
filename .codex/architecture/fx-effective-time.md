# Prospective fixed retirement and exact-time FX — V47

A8-06-01 adds retirement/read foundations. The production daily FX definition, its UTC origin/cadence/dependencies/signatures and v1/v2 implementations remain installed and unchanged. Legacy publication facts and the unique publication-day index retain their meaning. No production macro provider or takeover is activated.

## Fixed-system retirement

EconomicClock.retire(system,F,dispatcher,successorId) is an internal worker command. The successor must exist in the live fixed/domain configuration and persisted DeadlineDispatcher registry, with a retained implementation fingerprint and a next event exactly at F, stage0. The shared future macro provider identity is `economy:a8/macro-rates`.

The immutable FixedRetirements receipt captures world/system, original origin/cadence, selected implementation/version/signature and complete version schedule, real scheduling time/high-water frontier, takeover F, first excluded aligned end H, and successor provider/fingerprint/event/version. H is the smallest old aligned end >=F. Both F/H must be strictly beyond real wall, economic/financial observations, processed frontiers and retained events. No old end lies in [F,H). Existing completion at/after H or a pending legacy FX policy at/after H rejects; no future edit is discarded. Retired fixed version schedules freeze, and a reserved successor version cannot change before takeover.

nextIntent, nextDueMillis, debt/readiness and prepare/commit revalidation share the end>=H exclusion. The retired eligible target is clamped to H-cadence; its frontier remains its last actual completion. Per-system observation stops growing beyond its eligible range without reversing retained metadata. Global economic/financial observation remains available to the successor. No excluded completion, economic sequence or fake publication is created. Original receipt lookup precedes new time/provider admission; unchanged originals replay.

Clock startup validates retained implementation/dependency signatures and live successor fingerprints. DeadlineDispatcher prepare/next and offline provider validation also require the exact captured successor event: if no persisted event exists, the live provider must still report that same next id/time/stage/version. A vanished or altered successor blocks service instead of making retirement appear ready. Stored in-flight/completed events must match the captured identity. The full future provider must report F from immutable activation before the plan exists, so prospective retirement can be reserved before F.

JournalCompatibility's existing requirement that every actual economy:fx completion has its matching legacy daily publication is untouched. Retirement is a distinct source/projection and never substitutes a no-publication clock version.

## Exact-time FX sources and compatibility

EffectiveFxSnapshot carries UUID/world, exact effective milliseconds, provider/event, policy/activation, config/window, membership/model versions, mode/reference and the complete rate vector. New IDs derive from world/provider/event/policy/window. Rate representation is exactly12 decimals without numerical rounding; this normalizes the model's reference1 while preserving its values and frozen output hash.

EffectiveFxStore.prepare composes one trusted stage0 `20-fx` child from retained ActivationModelPlans, the preceding effective vector and approved model output. It supports AUTO and unchanged PAUSED output; MANUAL requires a future explicit frozen override adapter. It takes the actual dispatcher-child operation ID. Strict source validation binds the same transaction's real DeadlineDispatcher progress record, event/provider/cursor, window/boundary/config/activation, source chronology, exact model rates and contiguous events. If a fixed retirement is reserved, the first snapshot must match its F/provider/event. Daily publications may not overlap intraday takeover. No production registration or scheduling occurs here.

FxService.snapshot(reads), snapshot(reads,id), at(reads,time), source(...,exclusiveSequence) and snapshotBefore provide exact shared-family reads. Legacy daily sources adapt their original UUID and actual UTC-midnight effective time; intraday sources retain their exact instant. effectiveMillis(reads,UUID) resolves authoritative original identity and rejects unknown sources. Source-frontier variants support historical validation without admitting later facts.

FxService.latest now returns the newest shared snapshot's mathematical/display Publication view for compatible existing callers. Its day is display metadata, not an age clock; never publish that view as a legacy FxFacts.PUBLICATION. Existing facts that capture rates/UUID/day as math/provenance keep their bytes and source semantics. New code should prefer EffectiveFxSnapshot directly. latestDaily, publication(LocalDate), opening/deferred origin and frozen daily simulation remain explicitly legacy daily. The day index is not repurposed.

FxPairHistory preserves its daily API and adds intraday keyset pages <=16 using effectiveMillis/UUID with a captured upper bound. Newest/source/as-of lookups are indexed. Diagnostics validate old clock frontier/backlog against latestDaily and retirement-eligible target, while reporting the exact current snapshot id/effective time separately.

## Consumer migration and age policies

| Consumer boundary | Exact-time behavior |
| --- | --- |
| FxRequests/FxQuotes | Shared snapshots, original UUID revalidation and captured expiry; exact source freshness, no display-midnight reconstruction. Legacy mathematical overloads remain for frozen callers/fixtures. |
| CreditTreasury and loan/credit/collection conversions | Shared original/latest source; existing48-hour age duration and inclusive treasury boundary remain. Adapters already delegating these reads inherit the change. |
| LedgerService credit inputs | Underwriting publishedAt is original exact effective milliseconds. |
| LedgerService deposit/withdrawal cash | Shared exact source and UUID revision token, preserving the prior24-hour availability duration. Day prefix remains display only. |
| WealthValuation, InstitutionCapital, DepositYield | Original snapshot source and exact existing48-hour freshness/expiry rules. Capital's stored publication_day and valuation's encoded view remain frozen provenance/display fields. |
| NPC account/cash FX and goods pricing | Exact source48-hour expiry; original quote/supplier/source checks preserved. |
| CreditScoreRuntime historical utilization | Current-at-time snapshot, intervals split at intraday FX boundaries as well as existing UTC boundaries; prior24-hour coverage rule preserved. |
| Prime activation and cash quote recovery | Original source UUID at the original journal frontier plus exact effective time; no scan restricted to legacy daily publications. |
| Rebase, yield posting and captured loan/repayment facts | Captured UUID/rate math via the shared facade; no unrelated new age rule or contract rewrite. |

A scoped audit found no remaining live atStartOfDay or LocalDate publication lookup in foundation/credit/loan/debt/pricing/journal/institution/interest/trade/simulation consumers. Remaining daily-date uses are explicit legacy adaptation/history, frozen display or preserved UTC interval splits.

## Persistence and next owner

V47 appends fixed_system_retirements and effective_fx_snapshots with immutable triggers, source/time/original-ID indexes and registered strict codecs. Startup/recovery/offline inspection validate both; clean complete-world restore recognizes47 and retains the stopped upgrade-backup gate. Historical daily completion validation is unchanged. No player world/reset/EULA action is required for this foundation delivery.

A8-06-02 must install the actual stage0 provider, reserve one coherent prime/FX activation and retirement, compose26 targets over bounded pages, and retain full readiness through FX/30-complete/dispatcher completion. Use actual child IDs for scoped plans, prime and FX projections, and make successor next(F) available before plan preparation. A8-07 owns controls/production activation. Completion here selects neither package.


A8-06-02 now delivers the dormant [financial-day macro provider](macro-rates.md) under V48: atomic A/F/H handover, actual child-linked v2 window/publication facts, immutable selected baskets, chronological paused/manual/resume policies, and readiness through the separate dispatcher completion. Historical facts and fixed loan terms remain unchanged; A8-07 lifecycle activation is still unselected.
