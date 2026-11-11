# A6 downstream integration contracts

A6-13 records completed acceptance with approved D15 automatic payments. Current storage is V35 and banking protocol is8. `BankingIntegration` remains version1. [Automatic-payment contracts](../../architecture/autopay.md) publish the new entities, decoders, owner configuration and stage3 scheduler.

## Planned sequence

[A7–A10 planning boundaries](../../architecture/planned-a7-a10.md) insert A7 village banking and A9 basic item valuation. Former A7 simulation is A8; former A8 integration/playtesting is A10. These are future scopes, not execution approval. Branches reuse authenticated banking services; reference costs do not establish commodity backing or financial eligibility.

## Shared integration rules

Use server-session services and authenticated owner grants. Server-thread entry points capture live authority before detached work reaches the existing storage worker. Preserve canonical world/actor/operation/request identity, immutable policy/source versions and terminal outcome lookup. Financial postings, product state and outcomes settle in one journal transaction. Physical cards, cash, goods and agent presentation retain their separately witnessed effects and recovery rules.

Prime, valuation and fee snapshots carry their existing version, effective time, denomination/FX references and availability. Quote expiry and current ownership/revisions must be checked again at settlement. A source that lacks history, current marks or reconciliation publishes unavailability. The existing four-bank-account cap, currency uniqueness, three-active-card cap and same-issuer kiosk restrictions apply to adapters.

## A8 economic simulation and prime (former A7)

Consume `BankingIntegration.prime` and the captured `PricingPolicy.Policy`. Initial configured prime is 6.00% APR. `PricingPolicy.Change` supplies versioned future financial-day publication, with 0–50 percentage-point input and approved precision. Keep prior effective versions available to backlog and historical contracts. A new economic simulation must supply an explicitly registered scheduling/provider contract through the existing A1 dispatcher. The last committed configured rate remains the source until that adapter exists. Existing fixed loans retain their captured prime and score premium.

## B2 securities valuation

Implement a trusted `WealthValuation.Source` with stable ID, asset/liability kind, revision, mark identity and mark instant. Securities sources require fresh marks, with one-financial-day freshness. `SourceSnapshot` is bounded at 16 components and the valuation supports at most eight installed sources, including its three current production sources. Declare the immutable source manifest before finance readiness and preserve historical source versions for replay. Prevent duplicate claim identities and double-counted holdings or margin liabilities. Missing marks or unsupported liability coverage cannot qualify Tier 2. Raw securities holdings, execution and mark adapters are still future integrations.

## B4 trading execution and fees

Trading execution remains unavailable through the public banking facade. `ServicePricing.Service.TRADING` and `CumulativeTradingFee.Accumulator` supply the approved fee primitives for later execution. Tier 1 earns 0.25% on executed cash notional and Tier 2 earns zero. Persist the order accumulator with its execution and charge only the incremental cumulatively rounded fee. Cancelled or unexecuted quantity earns no fee. Revalidate the supplying bank, owner, tier, policy, quote and source revisions. Installation must atomically compose execution, cash, holdings, fee and outcome before advertising trading availability. Do not bypass the current facade's explicit trading rejection.

## B6 collateral and variable-rate products

`Loans.capabilities()` currently returns fixed available, variable unavailable and secured unavailable with `UNSUPPORTED_B6_ADAPTER_REQUIRED`. A collateral adapter needs authoritative ownership, current valuation, reservation/encumbrance, revision checks, release/default handling and history. Variable-rate contracts need approved reset boundaries and retained rate versions. Neither capability can be enabled by texture, client fields or absent-source defaults. Existing unsecured fixed contracts and debt remain payable under their recorded terms.

## A10 core integration and broader playtesting (former A8)

Preserve the new real-client and dedicated-server witnesses, original asset licensing, finite action bounds and exact offered selections. Extend visual review across other window/GUI scales, network latencies and external multiplayer clients. Native FX collection payment is now exercised. Native FX/subprime loan confirmation and a continuous real-time origination-to-default-to-agent playthrough retain composite backend/entity evidence, with their combined gameplay coverage still explicit. Full-inventory and uncertain delivery retain A5 witnesses and quarantine behavior. Polish can improve explanations and navigation while retaining server authority and quote consent.

## Delivered A6 automatic payments

Approved D15 is implemented and accepted through default-OFF per-facility named-source consent, card minimum/statement/full targets and loan arrears, atomic payments and bounded two-day retries. Statement reconstruction includes historical repayments. Original-window state survives restart. Payment precedes same-instant reporting. Changing consent cancels old retries and applies to future due instants, as disclosed in the review. Integrations preserve these semantics and the existing owner grant and original-outcome lookup paths.

[Acceptance evidence](../verification/A6-13.md) and [A6-13 handoff](../A6-13.md) retain broader gameplay limits. Later phases require explicit selection.
