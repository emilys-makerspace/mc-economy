# B2 — Brokerage, custody and portfolios — package contract

Draft revision1, 2026-11-13. Planning only; no package is selected, implemented or verified. Read the [original scope](README.md), [design brief](DESIGN.md), [shared B contract](../planning/B-SHARED.md) and [decision register](../planning/B-DECISIONS.md). The queue below refines the brief's proposed package boundaries; approvals explicitly recorded in the brief remain intact.

## Ownership and domain contract

B2 owns brokerage identities, balanced securities custody, reservations, lots and portfolio/settlement contracts. Stage A remains the only cash/FX authority. B1 supplies effective instrument identity; B3 supplies marks and participant liquidity; B4 executes. B0 supplies corporate-owner grants before any corporate brokerage use. One owner plus appointed managers and accounts/payments-first are approved corporate scope; corporate borrowing/credit stays unavailable.

The dependency cycle is deliberately broken: B2-01–04 deliver funding/custody/composition without accepted live marks; B3 consumes that seam; B2-05 activates valuation only after B3-04. B2-06 is final valuation-qualified acceptance. B3 does not depend on B2-06. Raw custody and unmarked portfolio reads can be delivered earlier without claiming wealth eligibility.

Approved brokerage lifecycle OPEN→CLOSING→CLOSED requires zero cash, no custody/claims/debt and no unresolved operations to close; R09 approves closure/reopening semantics; exact permanent Stage A TRADING identity compatibility remains B2-01 source/representation work, preserving historical identities/outcomes. No automatic BANK-cap change, personal borrowing or anonymous infinite counterparty. Security movements name source/destination/security/quantity/operation and reconcile against explicit issuer supply; outstanding-share metadata is not issuance. Free/order-reserved/pledged/delivered-on-loan and beneficial/return-obligation views stay distinct. Pledge/lending vocabulary does not enable B6/B7 products.

## Coverage ledger

All rows are draft contracts, implementation not started, verification not run. Historical behavior comes from the [feature inventory](../planning/01-current-feature-inventory.md); current accepted Stage A contracts supersede old implementation assumptions.

| Feature | Historical behavior | Package owner / replacement | Acceptance and downstream handoff |
| --- | --- | --- | --- |
| F07 trading identity | Separate TRADING account | B2-01 linked brokerage lifecycle | No duplicate cash ledger or BANK-slot change; B4 supplies menus. |
| F08 funding | Owned funding/unfunding | B2-01 Stage A transfer composition | Replay/foreign/stale/closed/held-funds rules; B4 consumes same service. |
| F16 holdings | Persistent whole-share position reads | B2-02 custody; B2-03 lots/portfolio | Quantity and basis conserved; B3 finite participants, B4 execution. |
| F16 implicit counterparty | Immediate unbounded holdings writer | B2-04 shared atomic participants | Failed cash leg moves no shares; changed model remains policy-reviewed. |
| F16 valuation | Portfolio holdings reporting | B2-05 accepted marks/wealth seam | No duplicate asset/liability coverage; B6 adds collateral policy separately. |

## Decision gates

BD04/[R01–R09](POLICY-REVIEW.md) are approved, settling brokerage currencies/lifecycle, FIFO, fee/basis allocation and lot residual rules. Exact source/representation/FX/proof feasibility remains B2-01/03 work; trade-notional rounding remains BD05. BD03 resolves issuer inventory/capitalization before live participant funding. B2-05 requires accepted B3 marks and complete liability coverage, not merely a raw price. Corporate mode additionally requires B0 acceptance and BD10 permissions; it may not infer authority from a player UUID or integration ID.

## Package boundaries and delivery

[EXECUTION.md](EXECUTION.md) is the authoritative selectable draft queue. Earlier proposed brief headings are refined by this queue: existing IDs retain their domain; additional acceptance/query/activation packages split later boundaries. No approval, implementation or verification follows from this refinement.

All packages inherit shared authentication, worker/journal, exact arithmetic, original-outcome, effective-time, bounded-read/configuration and recovery semantics. Reject stale, overflow, absent authority and unsupported sources explicitly; do not fabricate fallback quantities/prices. Each selected package must finalize its affected schema/state/DTO/configuration bounds before code and preserve historical decoders. No public SQL or alternate money ledger.

Planning uses diff/link review only; future packages follow [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md), including at most one useful focused test invocation and the three ordinary runnable delivery commands once. No automatic full-suite/test-per-package gate. Keep runClient/runServer, registered main source set and required development runtime dependencies; one owner runs Gradle/game checks sequentially. Each package records its handoff/evidence and stops before dependents.

## Catalog and cross-listing handoff — 2026-11-13

Cross-listing handoff: [catalog K07](../B1-market-registries/POLICY-REVIEW.md) requires one conserved underlying position/supply, separate venue availability/in-transit restrictions and exact relationship identity for B5 arbitrage. Portfolio aggregation cannot bypass transfer restrictions, mint a second holding or net unrelated classes. B4 cross-venue delivery must consult the applicable accepted B5 route or remain unavailable.

## Approved brokerage policies — 2026-11-14

[R01–R09](POLICY-REVIEW.md) are normative. One open brokerage per player/corporation, outside BANK-account caps, holds separate supported published-currency balances through Stage A authority. Choose an eligible sponsoring bank at its kiosk; personal Tier 2 eligibility stays current and corporate mode stays Tier 1/hybrid. Provider changes require explicit review and no open orders, holds, debt or uncertain operations. Sponsor loss cannot erase custody/cash; new affected work is gated pending an explicitly selected eligible provider.

Funding/unfunding is explicit same-owner cash transfer without automatic credit/overdraft or additional brokerage transfer fee. Existing applicable Stage A route/FX fees stay disclosed. No automatic FX at execution: fund listing currency first, using an explicit reviewed conversion where needed. Corporate personal contributions remain separate B0 owner-to-owner transactions.

Ordinary trades use whole units and immutable FIFO disposal. Acquisition commission enters basis, disposal commission reduces proceeds; retain existing Tier 1 0.25% / qualified Tier 2 zero and cumulative order-fee rules. No new custody/inactivity/annual/minimum brokerage fee. Attributable acquisition FX/fees retain their source/currency proof; unrelated cash fees remain expenses. Reporting currency is chosen at opening (world-denomination default) and fixed; acquisition and disposal retain original currency/FX, with trading and FX P&L distinguished. World rebase does not rewrite history. Cumulative lot basis allocation floors original basis×consumed quantity/original quantity and assigns the final remainder exactly; this does not settle BD05 cash-notional rounding.

Corporate trading/view/cancel scopes must be explicit, separately owner-granted and commit-time revocable; payment authority alone grants no trading rights. Initial corporate borrowing/wealth/yield/card exclusions persist. Closure requires zero cash, no securities/orders/claims/debt or uncertain work; preserve closed identity/history, reopening with a new brokerage identity and at most one open. B2-01 freezes compatible linkage to the permanent Stage A TRADING identity without destroying old facts. These approvals select no implementation.

## Order policy handoff — 2026-11-14

B4 [O01–O10](../B4-orders-settlement/POLICY-REVIEW.md) are approved, settling the formerly separate BD05 order/precision policy. B2 reservations/FIFO/fees compose with exact compatible-lot gross notional, full-request initial holds and precise partial releases; corporate revocation prevents later delegated fills without reversing earlier trades. Existing R01–R09 remain authoritative.

## Approved cross-market handoff — 2026-11-16

B5 [X01–X10](../B5-international-arbitrage/POLICY-REVIEW.md) are approved. B2 must preserve conserved supply, exact lineage/basis and in-transit/unavailable units across free 30s venue transfer or 0.10%/five-minute distinct conversion. Existing actor/scoped consent, due/cancel/completion, corporate revocation and original-operation recovery remain explicit; B5 custody fulfillment is distinct from new discretionary sell authority.

## Approved margin handoff — 2026-11-16

B6 [G01–G11](../B6-margin-risk/POLICY-REVIEW.md) are approved personal opt-in margin, a separately gated exception to initial cash-only brokerage. B2 must enforce unique first-priority pledges and compose actual lender-funded purchase/debt/claim and post-action risk checks. Existing corporate/institution borrowing exclusions remain. No free collateral release, double counting or automatic cash-brokerage borrowing is enabled by approval; accepted B6 adapter/capability readiness is required.

## Approved securities-lending handoff — 2026-11-16

B7 [L01–L11](../B7-shorts-lending/POLICY-REVIEW.md) are approved. B2 must distinguish lender beneficial return/fee claims, current actual custody, locate/loan/return units and restricted short proceeds. Preserve basis and no-chain lot lineage through onward sale; short-origin units cannot re-enter the lending pool while their originating obligation remains open. Freeze compatible cover-return provenance and disjoint long/short collateral without double-counted assets or liabilities. Actual capability remains gated until B7 acceptance.

## Player equity/action expansion handoff — 2026-11-16

[B8 scope/answers](../B8-advanced-markets/CORPORATIONS-EXPANSION.md) add player company/class/cap-table, private issuance/subscriptions, founder-to-voting governance, optional listing and same/cross-player merger rights. Preserve original catalog/account/security histories, actual cash/custody/supply, unreturned/pledged/action entitlements and successor claim authority. Registration or arbitrary issue price grants no mark/wealth/collateral; optional player listings do not silently displace the 800 curated reference rows. Audit this newly requested scope explicitly; exact class/action/governance/listing policy and source feasibility remain unapproved/preparatory, no implementation selected.
