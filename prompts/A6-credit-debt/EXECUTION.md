# A6 execution preparation

## Approval and current state

On 2026-11-04 the user stated: “all of the draft prompts have been reviewed and are approved for execution. Prep everything for execution.” This approves the 23 A6 prompts, their stated scope, acceptance gates and explicitly written recommendations. It supersedes their earlier draft-only and pending-approval labels. It does not select an implementation package in this preparation turn, fabricate missing policy values, authorize world resets or start downstream A7–A10/Stage B.

**A6-01 is complete as a document/source-review contract checkpoint.** [CONTRACT.md](CONTRACT.md), [handoff](../../.codex/A6/A6-01.md) and [document review](../../.codex/A6/verification/A6-01.md) record its outcome. [A6-02-01](../../.codex/A6/A6-02-01.md) calendar scheduling/configuration is implemented and verified, including isolated runtime loading. [A6-02-02](../../.codex/A6/A6-02-02.md) calendar diagnostics are implemented and verified. [A6-02-03](../../.codex/A6/A6-02-03.md) accepts the shared calendar foundation with restart/process/FIFO evidence. [A6-03-01](../../.codex/A6/A6-03-01.md) CHF currency/cash is verified, including V19 minor-posting history and preserved legacy facts/assets. [A6-03](../../.codex/A6/A6-03.md) institution catalog/accounts are verified, including V20 funding/provider migration and branch-only admission contracts. [A6-04](../../.codex/A6/A6-04.md) wealth qualification, pricing and funded yield are verified in their package scope. Live kiosks are delivered by A6-05-01; fixed loan APIs are delivered by A6-09-01 with production origination gated; trading remains unimplemented. [A6-05](../../.codex/A6/A6-05.md) original assets are verified. [A6-05-01](../../.codex/A6/A6-05-01.md) branded hardware and live kiosk admission are verified. [A6-06](../../.codex/A6/A6-06.md) issued debit cards are verified. [A6-07](../../.codex/A6/A6-07.md) profile/score contracts are verified with recurring scoring dormant. [A6-08-01](../../.codex/A6/A6-08-01.md) funded credit facilities and servicing APIs are verified in their recorded scope. [A6-08-02](../../.codex/A6/A6-08-02.md) owner and audited operator credit diagnostics are verified. [A6-08-03](../../.codex/A6/A6-08-03.md) credit foundation acceptance is recorded, with new production exposure gated pending downstream lifecycle safety. [A6-09-01](../../.codex/A6/A6-09-01.md) fixed loan foundation is verified in its API scope with production origination gated. [A6-09-02](../../.codex/A6/A6-09-02.md) bounded owner/operator loan diagnostics is verified. [A6-09-03](../../.codex/A6/A6-09-03.md) accepts the gated loan foundation with immutable obligation and recovery evidence. [A6-10-01](../../.codex/A6/A6-10-01.md) shared debt lifecycle is verified in its recorded foundation scope with historical reconciliation constraints. [A6-10-02](../../.codex/A6/A6-10-02.md) debt diagnostics is verified. [A6-10-03](../../.codex/A6/A6-10-03.md) accepts the installed reporting/scoring dependency for current/proven histories, with precise rejection for unsupported historical accounting. [A6-11](../../.codex/A6/A6-11.md) Collection Agent gameplay and payment dialogue are verified in their recorded scope. Next select A6-12. Other packages await actual prerequisite evidence. A completed A1–A5 gate is upstream evidence, not A6 evidence. The original approval-preparation turn changed no code/assets/schema; subsequent A6-02-01 execution adds schema18 and the calendar foundation, with evidence in its handoff.

Approval precedence: this record and the user's later instructions → approved specification/branding → package scope and recorded A6-01 contracts. Earlier dated statements that these drafts are unapproved are historical. Preserve shared contracts and bring material conflicts forward; do not ask again for the same prompt approval.

## Approved recommendations

- Original names, palettes, motifs, network names and roster home currencies, including Alpine Vault CHF. A6-01 assigns stable IDs; issuer/network compatibility still needs a concrete matrix.
- CHF two-decimal ledger precision; notes 10/20/50/100/200/1,000; coins 0.05/0.10/0.20/0.50/1/2/5. Electronic balances are not restricted to 0.05 multiples. Original artwork, not official-note reproductions.
- Card minimum uses pre-interest principal: min(total due, ceil(2%*principal + period interest)); no interest-free purchase grace; oldest arrears, then interest, then highest-APR principal payment allocation. Positive representable payments only, capped at full payoff. Arrears carry-forward detail remains to specify.
- Default at the oldest unmet due date +84 days. Reporting snapshots at due+2 days, allowing cure before reporting. Two-year collection clock begins at default/transfer; positive partial payment before expiry resets collectability, never the original seven-year reporting expiry. Merely opening dialogue acknowledges nothing.
- Fixed-at-origination loans with captured prime/rate; unsecured initial products; explicit unsupported secured/variable/margin capabilities. No implicit repricing or hidden minting.
- Net worth is approved supported assets minus all liabilities, counted once at authoritative FX/marks; borrowed proceeds are offset by debt; unavailable/stale valuations cannot qualify.
- One visible agent per debtor aggregating eligible cases; per-debtor seven-day kill cooldown; online-only spawning, no forced chunks, dimension/relogin reconciliation, no loot/XP farming.
- Nontransferable borrowing authority, replacement revokes old token, no recipes creating valid issued cards. Origination-anchored statements, world-aligned scoring; due/payment settlement before reporting before scoring.
- Four BANK accounts total including primary; retain unique currencies and protected primary. Bank-bound kiosk admission for opening/closing/replacement; kiosk base currency follows its bank. Preserve existing IDs/worlds through explicit migration rather than automatic color-to-bank guessing.

All original user requirements remain approved: UTC 20-minute days including downtime; 7/28/364-day week/month/year; APR/13 and 13 loan payments/year; 23 institutions, Tier 2 threshold100M, first credit score650/limit2,500, specified card APRs, terms, reporting/collections and branch rules. See the specification for exact units and complete coverage.

## Approved inputs for A6-01

The user approved [all remaining policies](POLICY-REVIEW.md) on 2026-11-04, with institution funding quadrupled from the reviewed7B to **28B**. All other policies are accepted unchanged. The detailed [credit/funding](POLICIES-CREDIT.md), [banking](POLICIES-BANKING.md) and [servicing](POLICIES-SERVICING.md) contracts are normative specification inputs. There are no remaining policy-approval blockers; do not ask again for these choices.

A6-01 delivered the shared interface/state/fact/migration contract and handoff; downstream packages implement and verify those boundaries. Implementing a policy is separate from approving it. If a new incompatibility is demonstrated during that work, report its exact impact and continue unaffected work.

## Package queue

Execute in the table order as a conservative serial path; independent-ready packages may be selected separately. Each package owns its scoped acceptance and handoff. Do not claim completion based on approval. Update this table and the project index only from actual results; failed gates retain blockers.

| Package | State | Prerequisites |
| --- | --- | --- |
| [A6-01](A6-01.md) | Complete: contract/document review only | Verified A1–A5 |
| [A6-02-01](A6-02-01.md) | Verified: calendar scheduling/configuration | A6-01 |
| [A6-02-02](A6-02-02.md) | Verified: read-only calendar diagnostics | A6-02-01 |
| [A6-02-03](A6-02-03.md) | Accepted: shared calendar foundation | A6-02-02 |
| [A6-03-01](A6-03-01.md) | Verified: CHF currency/cash | A6-01 |
| [A6-03](A6-03.md) | Verified: catalog/accounts; live kiosk deferred | A6-01, A6-02-03, A6-03-01 |
| [A6-04](A6-04.md) | Verified: qualification/pricing/funded yield | A6-03 |
| [A6-05](A6-05.md) | Verified: original resources only | A6-01, A6-03 |
| [A6-05-01](A6-05-01.md) | Verified: branded hardware and live branch admission | A6-03, A6-05 |
| [A6-06](A6-06.md) | Verified: issued debit lifecycle; no credit facility | A6-03, A6-05, A6-05-01 |
| [A6-07](A6-07.md) | Verified; recurring scoring dormant | A6-01, A6-02-03, A6-03 |
| [A6-08-01](A6-08-01.md) | Verified in recorded API scope; lifecycle activation pending | A6-04, A6-06, A6-07 |
| [A6-08-02](A6-08-02.md) | Verified; bounded owner/operator diagnostics | A6-08-01 |
| [A6-08-03](A6-08-03.md) | Accepted foundation; new exposure gated pending lifecycle | A6-08-02 |
| [A6-09-01](A6-09-01.md) | Verified API foundation; production origination gated | A6-04, A6-07 |
| [A6-09-02](A6-09-02.md) | Verified; bounded owner reads and audited operator diagnostics | A6-09-01 |
| [A6-09-03](A6-09-03.md) | Accepted gated foundation with accounting/source/recovery evidence | A6-09-02 |
| [A6-10-01](A6-10-01.md) | Verified foundation with recorded historical timeline boundary | A6-08-03, A6-09-03 |
| [A6-10-02](A6-10-02.md) | Verified bounded owner history and audited operator explanations | A6-10-01 |
| [A6-10-03](A6-10-03.md) | Accepted current/proven histories with reporting/scoring activation | A6-10-02 |
| [A6-11](A6-11.md) | Verified entity, cooldowns and private payment dialogue | A6-05, A6-10-03 |
| [A6-12](A6-12.md) | Verified in recorded menu/API scope | A6-05-01, A6-06, A6-08-03, A6-09-03, A6-10-03, A6-11 |
| [A6-13](A6-13.md) | Accepted with implemented and verified D15 automatic payments | A6-12 |

## Next-package launch

Copy into the next execution chat:

> A6-13 and approved D15 automatic payments are accepted in their recorded scope. Downstream phases retain their explicit-selection and prerequisite gates. No downstream package has been started.

A6-01 through A6-02-03 and A6-03-01 are complete in their recorded scopes. A6-03, A6-04, A6-05, A6-05-01, A6-06 and A6-07 are complete in their recorded scopes; later packages retain their prerequisite and explicit-invocation gates.

For later packages use “Execute <exact A6 ID> under the recorded A6 approval,” confirm prerequisite evidence and load only that package/references. Existing project one-package boundaries remain in force; phase approval eliminates repeated approval requests, not verification.

## Checks and handoffs

Main agent owns shared interfaces, integration and all sequential Gradle/game tasks. Substantial independent work may use the existing economy profiles with explicit disjoint file ownership, checks and acceptance; never silently substitute unavailable models/settings. Routine preparation stays with the main agent, with bounded read-only review where useful.

Contracts/docs-only packages: inspect diff and local links. Runnable implementation deliveries: meaningful targeted checks plus `./gradlew build`, `./gradlew help --task runClient`, `./gradlew runClient --dry-run`, following [the verification guide](../../.codex/VERIFICATION.md). Keep runServer, registered main mod source set and development runtime dependencies; runtime smoke checks when affected. One check owner, disposable fixtures only, no duplicate unaffected gates.

Each completed package records `.codex/<id>.md`, affected architecture contracts, actual evidence in `.codex/verification/<id>.md` when checks run, and project/phase queue status. Keep measured outcomes separate from designed guarantees. Do not precreate success evidence or migration versions before implementation. A6-13 owns final integration acceptance and usable release documentation; A8/B2/B4/B6/A10 remain later handoffs.

## A6-12 recorded handoff

[A6-12](../../.codex/A6/A6-12.md) is implemented and verified in its [recorded scope](../../.codex/A6/verification/A6-12.md). Bank/credit/loan/collection menus, atomic credit use and version-one integration are delivered. A6-13 has now executed its qualified acceptance. See its recorded outcome below for remaining capabilities and playtest scope.

## A6-13 acceptance outcome

[A6-13](../../.codex/A6/A6-13.md) accepts the delivered banking and automatic-payment foundation in its [recorded scope](../../.codex/A6/verification/A6-13.md). Native branch lifecycle, remote CHF collections and actual integrated-client controls/screens are verified. Debit/closure canonical-owner and presentation defects were fixed. Approved D15 automatic-payment state, reviewed configuration and bounded due/retry execution are now implemented and accepted. See the D15 verification addendum. [Published handoffs](../../.codex/A6/architecture/a6-downstream.md) retain A8/B2/B4/B6/A10 contracts. Later phases have not been started.
