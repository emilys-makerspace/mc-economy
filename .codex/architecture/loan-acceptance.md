# Loan foundation acceptance and lifecycle handoff

## Accepted contract

The fixed unsecured bank and shared Ironbridge foundation is accepted in its gated API scope. Captured score, Tier, prime, policy, currency and FX inputs determine original offers. The loan captures APR and the original schedule hash. Servicing uses APR/13 and 13 payments per financial year, checked minor units, segmented earned interest, explicit residual disposition and bounded original due requirements. Current borrowing admission continues through CreditSafety pending lifecycle acceptance.

Acceptance exercises score-dependent term edges, representative 13/65/104-period amortization, zero rate, final rounding, earned interest across partial payment, early repayment and full settlement. Finite lender cash funds principal atomically. A two-loan payoff preserves the other loan's shared receivable. Borrowed cash above 100M gross leaves authoritative net worth below the threshold when its equal liability is included.

The existing owner LedgerService routes carry live grant, world and canonical request authority. Native and explicit FX repayment retain quote, account and ledger revisions. Original committed outcomes resolve before handler preparation, including after expiry or disconnect. The core registrations remain trusted worker primitives for validated adapters and detached fixtures.

## Accounting guard

Every Loans.repay path now calls LoanAccounting.current before preparing payment effects, including the FX composition path. It compares the owner's saved nonsettled loan debt in the contract currency with the separate loan-borrower LIABILITY. It compares the lender's saved positive card and loan debt with its shared RECEIVABLE. Checked arithmetic and explicit bounded lookahead make mismatch, overflow or incomplete input return transient LOAN_ACCOUNTING_UNAVAILABLE.

Owner and lender folds each process at most 128 claims. The lender budget is shared across cards and loans. Larger portfolios require a later scalable reconciliation design before production activation. A rejected check retains the contract, funded principal, cash and claims for diagnosis. Diagnostic commands can inspect the mismatch and completeness flags. The check runs in the same serialized transaction as quote revalidation, recognition, monetary postings, contract update, immutable facts and original outcome.

This closes the acceptance defect where a full payoff could mark a contract SETTLED while an extra borrower liability or lender receivable survived. Four projection fault fixtures cover extra and missing amounts on each side. Normal full settlement leaves zero loan debt and preserves all other funded claims.

## Immutable obligation source

LoanEvents is a trusted worker read API for A6-10 consumers. It exposes these existing v1 facts.

| Payload | Existing fact | Meaning |
| --- | --- | --- |
| ContractChanged | economy:a6/loan | Captured fixed terms, status, monetary state and remaining original requirements after an operation |
| InstallmentClosed | economy:a6/loan-installment | Original obligation UUID, ordinal, due instant, original amount, earned/posted interest and frozen debt at close |

Each source envelope retains world, journal sequence and position, original transaction and operation UUIDs, audit UTC, economic update and correction link. Decoders validate the historical version and world binding. Contract validation uses the captured rate and schedule hash. The reader draws its data from immutable journal records and envelopes.

`open(reads)` captures a fixed frontier. `read(reads,cursor,limit)` returns up to 16 entries plus one lookahead using an exclusive tuple of sequence and position. A checkpoint retains its world and frontier. `open(reads,completedCheckpoint)` renews the frontier while retaining progress. A wrong world or a frontier beyond the restored journal fails closed. Consumers persist checkpoints atomically with derived effects and retain deterministic original obligation/event identities.

The v1 obligation UUID derives from the domain string `economy:loan:v1|installment|<world>|<loan>|<dueMillis>` through UUID.nameUUIDFromBytes. The first due instant is the committed anchor plus 33,600,000 milliseconds. Later instants add that same month length. Original amount and due identity remain immutable. Contract after-snapshots retain each requirement's original amount and remaining unmet amount after repayment. A fully satisfied requirement disappears from the remaining set while its frozen installment remains available.

A6-10 should join installment originals with ordered contract after-snapshots. The requirement describes payment satisfaction against one underlying debt. The same source supplies original due plus two financial days for reporting and oldest unmet due plus 84 financial days for default. Same-instant payment settlement precedes reporting under the shared calendar contract. Consumers must preserve original due, reporting and collection clocks through later state changes.

V30 appends loan_event_feed over sequence and position with the exact supported fact predicate. The source uses a tuple cursor to avoid the temporary sort found with the initial OR query. Main and offline validation and complete-world backup support V30. Existing journal families and decoder versions continue to supply the source.

## Recovery and bounded work

Actual owned fixture JVM termination covers origination, month close, partial repayment and full payoff on both sides of commit. Reopen, recovery and canonical replay preserve the original outcome and financial state. The separate uncertainty fixture throws only after a real origination commit and verifies one recovery lookup and one preparation.

The origination process probe uses a documented test-only fresh branch proof adapter around a frozen outer canonical intent because a killed JVM loses its ephemeral live proof. The uncertainty fixture exercises the actual original application canonical bytes directly. These witnesses establish accounting atomicity and original-operation recovery within their recorded fixture scopes.

Two loans catch up three offline months with page size one. Restart during APPLYING preserves the event cursor and completed effect count. Resumption creates one original installment per loan per boundary, commits at most one child/completion per turn and finishes within eight resumed turns. Repeating prepare/advance at completion leaves the journal frontier stable.

The foundation retains its 16 outstanding requirement bound. A6-10 must install and accept chronological default/reporting transitions before production admission. Longer unpaid histories and broad player-backed gameplay acceptance belong to the later lifecycle and integration packages.

## A7 and B6 boundary

A7 can use stable world/loan identity, owner-authorized contract/schedule/installment reads, captured offers and protected native/FX repayment APIs. The UUID maps directly to the shared LoanId value without changing persisted identity. Owner and audited operator grants remain separate.

Loans.capabilities advertises fixed unsecured lending and identifies the required B6 adapter for secured or variable products. A separately accepted adapter must supply authoritative world/owner asset identity, current valuation/revision witness, approved priority, atomic cross-facility pledge uniqueness and journaled release or realization. Original fixed contract authority and funded claim accounting remain the integration boundary.

[Loan contracts](loans.md) and [diagnostic commands](loan-diagnostics.md) describe the current entry points. [Verification](../A6/verification/A6-09-03.md) records measured results and the runtime scope.

## A6-10-01 current integration

DebtClaims now maintains constant-size borrower and current-creditor witnesses through transactional triggers. This replaces the128 shared-lender repayment scan described in the original acceptance. Default freezes accrual and moves protected repayment to collection cases. [Shared debt lifecycle](debt-lifecycle.md) records current capacity, ownership and historical upgrade requirements.
