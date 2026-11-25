# Institution and provider contracts — A6-03

## Identity and schema V20

`InstitutionCatalog` contains 23 immutable fictional customer providers, with stable `economy:bank/...` keys, native currencies, approved networks and product capabilities. World-bound deterministic SystemActor IDs distinguish institution parties from player-owned BANK identities. `economy:lender/ironbridge` and `economy:collector/waystone` use the captured server currency. `economy:treasury/institution-capital` is only a funding counterparty, outside the25 funded recipients.

V20 adds `institution_parties`, `institution_financial_accounts`, `institution_capital_manifest`, `institution_capital_allocations`, `bank_account_providers`, `bank_provider_leases`, `bank_provider_switches`, `bank_provider_migration`. Schema structure is validated at open/recovery/offline backup. The old V6 trigger and V19 index remain frozen migration definitions; V20 replaces only the insert cap guard (four) and history index (posting versions1,2,3). Existing identities and ledger rows are preserved.

Financial account IDs bind world, institution, role and currency. Roles are CASH, RESERVATION, RECEIVABLE, LIABILITY, PAYABLE, INCOME, EXPENSE, EQUITY, ROUNDING. Only CASH contributes to `spendableCash`; reservations and claims cannot fund spending. Capital initially registers CASH/EQUITY/ROUNDING per party. Other typed identities can be registered as their owning future products require them. Customer accounts stay in `account_catalog`; institution accounts do not count toward customer caps or acquire player authorization.

## Finite capital and money

`InstitutionCapital` owns stable world/step operation IDs. It waits for denomination and a fresh eleven-currency publication (UTC day start through 48h), captures both once, then journals one issuance plus 25 allocation steps. Each step registers at most one party and three accounts. Production scheduling treats READY/ALLOCATING capital as bounded debt and withholds economic readiness until funded; missing denomination/FX remains dormant. Restart resumes the persisted allocation cursor. Once ACTIVE, replay resolves the final operation without reseeding.

New funding starts with 2.8T server-major: 18×40B Tier1/hybrid, 5×400B specialists, 40B Ironbridge and 40B Waystone. Existing manifests retain their captured total, including the legacy 28B seed, for restart, inspection and journal recovery; existing worlds receive no automatic top-up. `CapitalPosting` issues treasury CASH+seed/EQUITY−seed with explicit CAPITAL_ISSUE authority. Allocations transfer treasury cash through A4 clearing to native institution cash; matching equity recognition cancels clearing per currency. Treasury ends at zero. This is an approved new-money/equity seed, not a customer credit or receivable-backed spendable balance.

Native base minor units use exact rational FLOOR. Captured rates, exact residual numerator/denominator,18-decimal display values and CEILING10% native-minor operating floors persist in the allocation. Residual never becomes spendable cash. Later lending/yield packages must enforce floors and commitments; this package does not originate credit.

`economy:posting` v3 retains five fields (UUID, role, currency, signed minor amount, authority), adding INSTITUTION_CASH/INSTITUTION_CLAIM and CAPITAL_ISSUE. V1/V2 decoders reject these additions; ordinary Settlement/PostingBatch admissions reject them. Ledger projection rebuild/history include v3. Typed capital preparation resolves registered or same-transaction planned accounts and checks normal balances: asset/expense balances nonnegative, liability/payable/income/equity nonpositive, rounding signed.

## Providers and admission

Legacy BANK rows migrate in pages≤16, with durable unassigned/legacy bindings and cursor facts. Existing provider bindings are skipped. Account IDs, currencies, primary flags, balances and prior history remain intact. Primary currency rebases retain their provider; a preexisting non-native primary is an explicit retained exception rather than silent provider reassignment.

At most four OPEN BANK accounts total per owner, including primary, and one per currency. Personal primaries use the opening bank’s enabled native currency. World denomination does not constrain the choice of currency. New secondary admission uses the selected bank's native currency. Specialist admission checks a trusted eligibility revision; the production default is unavailable until A6-04. Hybrids retain Tier1 admission.

`BranchAdmission.Issuer` construction is package restricted; no live issuer is installed. Proof contains a server live-check callback and immutable world/session/actor/action/bank/target/account/expected revisions, TTL≤30s. Adapter must recheck current session, loaded block/generation, distance, permission and bank on every attempt. Persistent lease rows prevent successful reuse with another operation; A1 replay of the original intent bypasses re-execution. A durable rejection does not consume a lease, but its canonical action/targets/revisions remain fixed. No network DTO can create a proof.

Switching requires a source visit naming one same-currency target, followed by target confirmation within7×1,200,000ms financial days. Source authorization advances provider revision without revoking debit access; target must obtain a new proof after source authorization. Target confirmation rechecks account/provider/denomination revisions and tier eligibility, consumes authorization, retains account identity/funds and increments debit epoch. Assignment and closure also increment epoch. Actual card revocation consumption belongs to A6-06.

`AccountLifecycle.closingAtBranch` wraps provider admission around original A3/A4 closure protections. `FundedClosure.registrationAtBranch` binds destination/revisions/quote into the canonical intent and composes all postings, closure, provider epoch and lease in one transaction. Primary/current-world-currency protection, claims, hold and destination checks remain.

## Facts and API

V1 decoders: `economy:a6/catalog-publication`, `economy:a6/capital-manifest`, `economy:a6/capital-allocation`, `economy:a6/institution-account` (see source constants for exact keys); `economy:bank-provider`, `economy:provider-account`, `economy:provider-switch`, `economy:provider-migration-cursor`. Provider identity facts include world/account/owner/currency/status/revision/primary; binding facts include legacy marker/debit epoch; switch facts include named source/target, expected revisions and expiry. Journal envelope/canonical intent supplies actor, world, operation and lease identity. Projections, facts and terminal outcome commit atomically; uncertain commits use A1 original lookup.

`ServerServices.institutionCatalog`, `institutionCapitalStatus`, `accountProvider` return immutable worker-derived snapshots (provider reads enforce owner authorization). `bankLifecycle` is a trusted server adapter SPI with default Tier2 denial. Protected closure uses the sealed proof overload. There are no new client packets, physical effects, loans, assets or kiosks in this package. Old catalog creation, raw opening/closing, teller/card UI actions and unresolved lifecycle resubmission are gated; lookup of existing outcomes remains available.


## A6-04 current contract (2026-11-05)

[Wealth, pricing and funded yield](wealth-pricing.md) defines schema V21, persisted required-source contracts, fresh qualification/fee witnesses, assigned-bank FX pricing and time-weighted bank-funded deposit yield. Production installs bounded accrual/payout providers at stage 1 and daily Tier reevaluation at stage 7; empty domains remain dormant. Source snapshots and unpaid receivables remain distinct from spendable funds. Live branch adapters, issued cards, lending and trading are still separate packages. Earlier no-product/default-Tier2-denial descriptions apply to their historical delivery checkpoints.

## A6-05-01 live branch adapter

[Branded kiosks](branch-hardware.md) now install live server-owned admission for opening, assignment, provider switching and protected closure. `BankProviders.CLOSE` is the composed closure kind. Generic lifecycle APIs remain gated; replacements and applications await their services. Schema is V22; capital remains finite and bound to its original manifest (see the current seed policy above).

## A6-06 token revocation integration

[Issued debit tokens](issued-cards.md) bind the provider debit epoch. Assignment/target switch/closure compose one active-token revocation fact/projection atomically; source switch authorization retains debit access. New issuer first issuance is free. Ownership of branch hardware never grants token/customer authority.
