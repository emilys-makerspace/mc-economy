# A6 — Banks, credit and debt

**Status: all 23 prompts approved for execution on 2026-11-04; A6-01 through A6-02-03 and A6-03-01/A6-03/A6-04 complete in their recorded scopes; later runtime products not started.** [Execution preparation and approval record](EXECUTION.md) gives the queue, missing-input checklist and first-package launch instructions. Select one package, finish its acceptance/handoff, then stop; no repeated prompt approval is needed.

The user selected **elapsed UTC, 20 real minutes per financial day**, including downtime, and **APR/13 with thirteen monthly loan payments per year**. Other user rules and remaining unspecified inputs are recorded separately in [the specification](SPECIFICATION.md). Use the approved [original bank/network names and art scope](BRANDING.md) during asset implementation.

The expansion adds **CHF cash and FX**, **23 banks** (18 Tier 1 including five hybrids; five Tier 2 specialists), **four open BANK accounts total**, bank-branded ATMs/tellers and a new **branch kiosk** for institution-specific opening/closing/replacement and credit-card/loan applications. Existing prompt IDs remain stable; the two new bounded packages bring the sequence to **23 prompts**.

Shared implementation contract: [CONTRACT.md](CONTRACT.md). It defines stable identities, service boundaries, states, journal families, accounting/clock extensions and migration/package ownership.

## Ordered deployment prompts

Do not execute this list as a batch. Scheduling/configuration → diagnostics → foundation acceptance is split where temporal state and replay risk warrant it. Smaller catalog, art and interaction tasks retain their own scoped diagnostics/acceptance rather than requiring artificial three-stage work.

| ID | Task | Prerequisites |
| --- | --- | --- |
| [A6-01](A6-01.md) | Shared contracts and policy checkpoint | Verified A1–A5 |
| [A6-02-01](A6-02-01.md) | Financial calendar scheduling and configuration | A6-01 |
| [A6-02-02](A6-02-02.md) | Financial calendar diagnostics | A6-02-01 |
| [A6-02-03](A6-02-03.md) | Financial calendar foundation acceptance | A6-02-02 |
| [A6-03-01](A6-03-01.md) | CHF currency, banknotes and coins | A6-01 |
| [A6-03](A6-03.md) | Institution catalog and bank accounts | A6-01, A6-02-03, A6-03-01 |
| [A6-04](A6-04.md) | Wealth qualification and service pricing | A6-03 |
| [A6-05](A6-05.md) | Original bank, network, card and agent assets | A6-01, A6-03 |
| [A6-05-01](A6-05-01.md) | Branded ATMs, tellers and branch kiosks | A6-03, A6-05 |
| [A6-06](A6-06.md) | Issued bank cards and lifecycle | A6-03, A6-05, A6-05-01 |
| [A6-07](A6-07.md) | Credit profiles and score policy | A6-01, A6-02-03, A6-03 |
| [A6-08-01](A6-08-01.md) | Credit-card facilities, interest and statement configuration | A6-04, A6-06, A6-07 |
| [A6-08-02](A6-08-02.md) | Credit-card diagnostics | A6-08-01 |
| [A6-08-03](A6-08-03.md) | Credit-card foundation acceptance | A6-08-02 |
| [A6-09-01](A6-09-01.md) | Loan underwriting, amortization and schedule configuration | A6-04, A6-07 |
| [A6-09-02](A6-09-02.md) | Loan diagnostics | A6-09-01 |
| [A6-09-03](A6-09-03.md) | Loan foundation acceptance | A6-09-02 |
| [A6-10-01](A6-10-01.md) | Delinquency, reporting and collection-clock configuration | A6-08-03, A6-09-03 |
| [A6-10-02](A6-10-02.md) | Delinquency and credit-report diagnostics | A6-10-01 |
| [A6-10-03](A6-10-03.md) | Delinquency and reporting foundation acceptance | A6-10-02 |
| [A6-11](A6-11.md) | Collection Agent gameplay and payment dialogue | A6-05, A6-10-03 |
| [A6-12](A6-12.md) | Bank and credit menus, public API and integration | A6-05-01, A6-06, A6-08-03, A6-09-03, A6-10-03, A6-11 |
| [A6-13](A6-13.md) | Integrated banking and credit foundation acceptance | A6-12 |

## Execution order and handoffs

Start with A6-01, then calendar A6-02-01 →02-02 →02-03. CHF registry/cash A6-03-01 can follow A6-01 independently of the calendar; it must complete before institution catalog A6-03. Banks A6-03 precede tier/pricing A6-04 and card/art foundations. A6-05 artwork may be prepared independently of credit mechanics after the catalog/brief is approved. A6-05-01 then implements branded ATMs/tellers and branch-kiosk admission before card replacement and full application-menu integration. A6-07 establishes the scoring contract; A6-08 and09 implement cards and loans; A6-10 supplies reports/default/collection clocks; A6-11 provides agents; A6-12 integrates usable workflows; A6-13 accepts the release. This is a dependency map, not authorization for unattended phase execution.

Current feature coverage and deliberate changes: [specification coverage ledger](SPECIFICATION.md#feature-coverage-and-later-handoffs). A8 owns later prime/economic simulation; B2/B4 owns future securities valuation/trading and fee application; B6 receives margin/collateral capabilities only. Shared API boundaries can be delivered now; downstream integrations require their own invocation.

All D01–D17 policies, branding and CHF denominations are approved, including the user-adjusted **28-billion** finite institution seed. [The approved policy set](POLICY-REVIEW.md) and its three owner contracts are normative; no policy-input blockers remain. A6-01 is complete as a document/source-review checkpoint; A6-02-01/02 deliver calendar scheduling and diagnostics, with [A6-02-03 acceptance](../../.codex/A6/A6-02-03.md). [A6-03-01 CHF](../../.codex/A6/A6-03-01.md) is verified. [A6-03 institutions/accounts](../../.codex/A6/A6-03.md) is verified with live branch adapters deferred. [A6-04 qualification/pricing/yield](../../.codex/A6/A6-04.md) is verified in scope. Next select A6-05 (original assets). Later bank/debt products remain unimplemented.
