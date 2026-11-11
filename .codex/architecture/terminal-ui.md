# ATM and kiosk navigation — 2026-11-09

Banking protocol11 introduced these finite actions; current protocol12 adds the separately reviewed settings confirmation. Current schema54 and public API versions remain unchanged by this navigation layer. [A10-09 acceptance](../A10/architecture/a10-acceptance.md) records subsequent localization and native layout evidence; historical task checks retain their original versions.

`TerminalMenus` replaces dense terminal Home summaries with direct services, bank-scoped bank/credit/loan accounts, five-row activity pages, bill selection and grouped kiosk services. ATM login requires an owned usable held card; credit and other-bank cards restrict ATM navigation to withdrawal. Remote credit-card My Accounts is scoped to its facility. History preserves original posting currencies. Credit draws commit atomically; there is no pending-charge model, so Pending is zero.

`BankingScreen` retains316×236 container/deposit geometry, using existing issuer logos/palettes, cream surfaces, accent outlines and a Home shortcut. Permanent footer status and duplicate status tooltips are removed. Cash selection prefers horizontal banknote art; loan amount and term have separate fields. Compact dates and contextual feedback replace diagnostic presentation.

Bound kiosks derive native cash-service settings without mutating legacy saved disabled settings. Registers stay excluded. Credit withdrawal confirms an advance into an owned same-currency bank account, then opens separately reviewed physical withdrawal. Financed fees consume advance headroom. Assisted admission permits an owner kiosk or same-bank usable debit login while preserving active owned credit-token, issuer, design, owner, request, expiry, terminal generation/revision and fee checks.

Account Settings preserves existing primary-account currency and source/target authorization: Change Primary Bank at the source bank, then Assign This Bank as Primary at the target bank. Legacy unassigned primary accounts can be assigned directly. Provider changes cancel old debit tokens. There is no new cross-currency primary identity.

`CashClosure` atomically debits the account, issues native cash, creates protected custody and closes the account/provider; existing inventory delivery then runs. Zero balances need no cash. Primary/world-currency, hold and debt protections remain. Exact payout must fit one supported batch; no balance remainder is silently rounded away. Capacity is checked before confirmation and during delivery. Undelivered payouts can become claims; uncertain attempted cash retains existing quarantine/recovery rules. Legacy funded closure API semantics remain unchanged.

[Scoped verification](../verification/terminal-ui.md). No player worlds or backups changed.
