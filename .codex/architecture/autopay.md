# Automatic payments

D15 is implemented in schema V35 and banking protocol8. The integration API remains version1. Public owner-authorized `LedgerService.autopay` and `configureAutopay` expose configuration. The normal Bank and credit hub opens Automatic payments from ATM, teller, kiosk or card access. Facility, account, owner, world, current revisions and exact canonical consent are checked on the storage worker. Reviews expire after30 seconds. Uncertain outcomes use the existing saved banking intent and original-outcome lookup.

## Consent and targets

Each card or loan starts OFF. Explicit consent selects one owned OPEN BANK account with matching currency. Card modes are MINIMUM, STATEMENT and FULL_PAYOFF. Loan mode is LOAN_DUE. Minimum is the first offered card mode. There is no account substitution, automatic FX, overdraft or refinancing. Linked enabled consent blocks ordinary source-account closure until disabled. An unavailable or imported closed source cancels execution without charging another account.

Enabling or changing consent applies to due instants strictly after its effective time and cancels any previous retry window. This implementation choice is disclosed in the review. Existing arrears remain manually payable. Disabling remains available for inactive facilities. Facility closure/default automatically disables consent. A settled loan disables consent after its final payment. A fully paid active card remains opted in for future statements.

MINIMUM pays total unmet requirements due by the original due date, capped by debt. STATEMENT pays the immutable statement balance less every later repayment, capped by current debt. FULL_PAYOFF calculates current full card debt freshly at every attempt. LOAN_DUE pays total unmet installments due by the original date, capped by loan debt. Payments use the existing card and loan repayment composition and allocation rules. Manual payments reduce an active window. Minimum satisfaction alone leaves a larger statement/full target active. A satisfied window stays complete when later spending occurs.

## Durable scheduling and accounting

`AutopayRuntime` is provider `economy:a6/autopay`, implementation1 with fingerprint `autopay-original-window-v1`, at stage3. Daily accrual and loan installments run at stage1 and card statements at stage2. Automatic payments precede stage4 delinquency/default and stage5 reporting, including the final due+2 report boundary.

A due window attempts an all-or-nothing debit from available funds. Insufficient funds leave money untouched and schedule one retry per financial day for two days after the original due date, at most three attempts. Original consent, mode, obligation, due instant, target, remaining amount, attempts and next deadline persist. Satisfied, exhausted and cancelled windows remain auditable. The existing dispatcher supplies stable event/target operations and atomic child progress. Payment postings, facility changes, window/head changes and the journal outcome commit together. Restart and offline catch-up retain that identity and window. No physical item or entity effect is introduced.

## Storage and reconstruction

V35 adds `autopay_settings`, `autopay_windows`, `autopay_statements` and `autopay_bootstrap`. Settings, windows and bootstrap emit version1 `economy:a6/autopay-setting`, `economy:a6/autopay-window` and `economy:a6/autopay-bootstrap` records, registered in the recovery catalog. Statement trackers are source-derived projections. Existing posting/card/loan record versions retain their meanings.

Startup folds statement and legacy REPAYMENT events through an indexed immutable journal frontier in16-source pages, persisting each page's trackers and cursor atomically. It counts the existing repayment event once, including history predating modern payment audit facts. It never sums the modern sibling audit twice. This preserves literal statement-balance-minus-payments semantics even where allocation paid newer interest first. Production readiness waits for this fold before scheduled execution. The shared SQLite commit decorator maintains trackers thereafter, including while consent is OFF, and updates active windows after manual repayments. Unknown or malformed source history fails closed.

Schema validation checks exact table/index declarations, canonical settings/window bodies and projected identities, consent/deadline coupling, retry bounds and statement identity/time/amount bounds. Pre-upgrade complete-world backups remain required for schemas1–34. Restores support clean schemas4–35 through the existing format.

[Acceptance evidence](../A6/verification/A6-13.md) records execution and broader gameplay limits. Downstream phases require explicit selection.
