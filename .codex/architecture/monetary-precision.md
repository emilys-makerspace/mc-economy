# Monetary precision

2026-11-09 — Direct user-authorized change, independent of phase/package execution.

## Current arithmetic

New published FX rates have eight fractional decimal places, truncated toward zero. Stored reference vectors retain the legacy twelve-place canonical representation with four trailing zeros; historic rate payloads are immutable. Derive a cross-currency pair rate at eight places with DOWN, multiply the source major amount, and truncate the resulting target major amount to eight places. Received amounts settle with DOWN to two places, except JPY settles to the nearest whole yen with HALF_UP. Exact requested payouts settle the calculated funding debit to the nearest account minor unit with HALF_UP. Bank GUI rates show the pair quotient at exactly eight places, including trailing zeros. Original numerator/denominator history evidence remains intact.

New interest accruals and residuals truncate to eight fractional major-unit places; posted interest rounds to the nearest minor unit with HALF_UP. For compatibility with existing record formats, canonical eighteen-place containers remain, with zeros after the eighth fractional place for new calculations. Old values are read without rewriting and their original arithmetic remains available for historical validation. APRs, financial-day length, fee percentages and settlement balance units are unchanged.

CAD-based banking fee conversion uses the same eight-place pair/amount stages before its existing nearest0.25/25JPY fee-increment rounding. Historical CAD_FEE_V2 withdrawal receipts retain their original validator; new receipts carry CAD_FEE_V3.

## Compatibility

Legacy daily FX algorithms and macro model v1 remain frozen. New daily algorithm/publication v3 and selected macro basket factv2 distinguish prospective eight-place outputs. Old daily publications and selected baskets retain their original validation/hash paths. Eight-place selected baskets must still satisfy the exact pairwise model bounds; AUTO attempts deterministic backoff; invalid MANUAL/PAUSED quantization is refused. An unrepresentable basket is rejected without publishing invalid rates.

Credit daily witnesses distinguish old and new accrual arithmetic; loan schedule validation retains a frozen legacy hash path. Existing settled account money remains signed64-bit minor units (cents, or whole JPY). No world/database reset, balance rescaling or historical rewrite is performed.

Nearest-minor funding and whole-yen receipts can produce signed reference-value FX residuals. New loan disbursement, loan repayment, credit repayment and collection repayment receipts use version2 to retain those values; version1 validators keep their original nonnegative requirement. Activity attribution and loan disbursement history accept both versions. Exact-debit quotes may retain zero rounded target credit so funded closure can drain and close tiny balances atomically.

See [verification](../verification/monetary-precision.md).
