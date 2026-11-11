# A4 ledger and transaction design

A4 owns financial postings, balances, holds, settlement, and financial history. A5 owns cash items. Custody owns shares and return obligations. Markets and separate integration mods use the financial service.

## Authority and persistence

The server authenticates the actor and submits a canonical request to the A1 journal worker. Resolve an existing operation before checking live quote validity or preparing a new posting. New requests validate authority, revisions, and available funds against the settlement snapshot. Commit financial facts, projections, and the outcome atomically. Replay uses versioned facts to rebuild projections. Terminal business rejections retain their outcome for the world lifetime. An unknown result is resolved using the original operation ID.

## Posting and availability

Each financial transaction has one immutable identity. Signed entries sum to zero independently in each currency. Use checked whole-coin arithmetic and explicit counteraccounts for issuance, redemption, and FX clearing. A hold reduces available funds while leaving the posted balance intact. Full capture posts the payment and consumes the hold atomically. Explicit release restores availability. Active holds survive restart. The owning workflow recovers them. Automatic expiry is outside the foundation scope. Partial capture is deferred for future planning.

## Settlement operations

- Same-currency payments resolve the recipient primary and recheck account eligibility at settlement. Owned-account transfers and authorized credit/debit share the same posting boundary.
- FX settles both currency legs under one identity. Consume the quote in the posting transaction so rollback leaves an otherwise valid quote usable. Credit fees to the game and round payouts down to whole coins. The rounding rule determines the whole amount to post.
- Funded closure uses the A3 destination contract. Recheck eligibility and claims, then settle and close the source atomically. A confirmed conversion that rounds down to zero has a zero payout.
- Rebase uses the A2 confirmed denomination change and reruns the full A3 plan inside settlement. Recheck authority, roster, revisions, and claims. Retire a collision before changing its surviving primary. Round converted balances down, including zero, and preserve raw history. Size the atomic operation against A1 journal limits before enabling it.

## History and reconciliation

History exposes original amounts and denomination revisions, paired transaction identities, and bounded cursor queries. Reconciliation compares replayed projections with committed state and reports discrepancies through authorized diagnostics. Commands and integrations share the financial service and its authorization rules.

## Upstream contracts and implementation detail

Use the [A1 journal contract](../../.codex/architecture/journal.md), [A2 quote handoff](../../.codex/A2/A2-04.md), [A2 denomination handoff](../../.codex/A2/A2-05.md), and [A3 account contract](../../.codex/architecture/accounts.md). The owning packages specify the posting account catalog, projection schemas, and query limits. Measure the supported rebase write size in A4-05. Package order and acceptance gates are in the [phase index](README.md) and [execution contract](EXECUTION.md).
