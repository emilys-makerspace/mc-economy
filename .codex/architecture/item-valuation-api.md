# Public item-reference reads and contribution data

Delivered by [A9-04](../A9-04.md), with [scoped evidence](../verification/A9-04.md), extending [component/container valuation](item-valuation-variants.md). Public interface: `api.ItemValuationIntegration`, version1. [User integration example](../../docs/INTEGRATION_API.md#generic-provider-registration).

## Read admission and immutable results

`ServerServices.itemValuation(server)` binds the current server session. Each call validates its server thread, foundation readiness and server-issued ownership `PartyAuthorization.Grant`. Null, wrong-world, inspection and expired-session grants do not authorize reads. Malformed IDs/JSON/quantities/selectors reject at admission. Canonical component JSON is duplicate-aware, key-sorted, array-order preserving, UTF-8 bounded to64KiB/depth64; quantity1–1,000,000. Detached results carry no native stack or mutable JSON.

`evaluate` captures manifest and admission time, loads one effective FX publication on the existing bounded storage worker, then evaluates detached data on the calculation worker. Retained selectors require both valuation UUID and original FX UUID; missing history never falls back to today's rates. Current generation/session changes refuse in-flight new reads. Public completions follow existing server-tick delivery. Exact amounts are reduced rationals bounded to4096bits. Only READY contains exact, unit/total money and complete captured identity; unavailable results have no partial spendable amount.

Snapshot references include UUID/revision/content/config/recipe/provider hashes, manifest and algorithm/adapter versions, FX UUID and FX effective time. Capture time is admission time; valuation effective-from is durable publication time. Selected source provider/revision appears in bounded structured proof steps. Returned proof is capped at256nodes with explicit truncation, independent of numeric completeness.

`currentCached` accepts Current only. An immutable root result is reusable only for the same active manifest, unchanged storage epoch and before the next captured effective-FX boundary; backwards wall-clock movement also refuses it. Every storage task admission/start conservatively advances that epoch, including internal economic writes. This prevents stale FX from appearing current without database I/O on the server thread. A miss starts deduplicated bounded async work and returns NOT_READY/PENDING. It does not invent an amount while work runs. The public sampled-result cache is capped at64 entries; internal root/compiled caches retain their existing bounds.

Reads/history share a32-request session cap plus existing queue/actor fairness. Queue overload returns BACKPRESSURE (history fails its stage). Never join on the server thread. `history` uses an exclusive UUID cursor resolved to durable revision, limit1–16; eviction from compiled memory does not delete history. Reads never advance economic time.

## Provider authentication and publication

`ServerServices.itemValuationProvider(server)` returns an opaque session-bound handle. The bridge itself captures the actual external caller using StackWalker; it accepts no caller-supplied class or namespace proof. Caller module must equal the loaded NeoForge game-layer module owning its mod namespace. One handle can claim only its loaded module's mod namespaces; reserved Minecraft/Economy namespaces remain forbidden. Native code in a trusted server mod may explicitly delegate its handle, like other capabilities.

Contribution data includes provider ID/revision, owned item IDs, positive currency/count bases, exact component selectors, explicit ordinary derivation opt-ins, material-sum rules and supported adapter versions. All collections are immutable; aggregate entries/claims are capped at4096, selector identity payload at1MiB in the public shape, and durable manifest at32MiB. Every item/material must exist in the captured registry. Provider data/revisions enter the canonical provider hash. Same revision with changed data is rejected while registered. Two providers with any overlapping item claim reject the whole candidate even for equal prices and even behind an operator override.

Registration replaces one provider's complete data; removal proposes a provider-free candidate. Both invalidate fresh admission and use the existing generation-gated capture/prepare/stage/publication pipeline. Accepted provider data changes only after durable publication. Failure retains prior provider data/pointer, restoring only the captured prior readiness when its resource epoch still matches. Overlapping reload cancels the pending provider change on the server executor. Futures are cleared before success/failure callbacks can request another mutation. At most one provider mutation is admitted while capture/publication/reload is busy.

Providers are live session registration data, not silently resurrected code. Loaded mods re-register after foundation readiness on each server session. Reload retains currently accepted providers; restart re-captures current registrations before making mod values available. Retained manifests remain readable independently of present provider registration.

## Supported contribution semantics

V5 `economy:contributed-values/v1` adds a provider list; old v1–v4 canonical encodings omit it and keep their original semantics. No database/schema migration is needed (V50 storage/chunks/history reused). Explicit server overrides remain highest precedence; plain override still does not flatten unknown components. If it suppresses an opaque exact selector, that unsupported state remains unavailable.

Unregistered mod recipes never grant a value. A claimed plain mod item can use an explicit base, exact `{}` selector, opted-in captured ordinary recipe or declared SUM material rule. Ordinary adapter `economy:ordinary-recipes/v1` declares the provider's plain claimed inputs have consumed/no-remainder semantics; only exact reviewed vanilla serializer classes are captured. The declaration enables those IDs as material alternatives without invoking remainder callbacks. Providers must not opt in custom-consumption/returned-container semantics. Component adapter `economy:component-values/v1` accepts bounded detached SUM terms and optional positive typed CAD content, with shared depth/work/exact bounds. No public callback/SQL/recipe assembly hook exists.

Conservative mod metadata is plain/non-damageable/non-container. Custom component-bearing states need an exact selector for the whole canonical state. A selector supplies that identity's complete value, without inferring arbitrary equipment or container behavior. Vanilla typed adapters retain their prior captured semantics and bounds. Native custody still preserves actual original stacks independently of valuation identity.

## Diagnostics and consumer boundary

Connected permission-level2 operators have `/economy valuation status`, `coverage`, `value <item> [currency]` and `history [limit]`. Max16 command requests per server, bounded output, server/player/operator/session revalidation before display and stop cleanup. API/operator results retain reason codes and proof roots; no player-facing unsupported-listing explanations were added.

Coverage separates1,333 vanilla classifications (349BASE/760DERIVED/109VARIANT/115operator-unpriced), supported component states and intentionally unregistered mod identities. Classification is not a numerical guarantee for arbitrary datapacks/components/budget exhaustion. Public reference values confer no financial qualification, custody, money or trade authority. [A10 handoff](a9-read-only-handoff.md) records read-only use; A9-05/06 own source transition and trader activation.
