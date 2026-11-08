# A9 read-only handoff for A10

A9-04 delivers `ItemValuationIntegration` v1 through current `ServerServices`. See [API contracts](item-valuation-api.md), [integration example](../../docs/INTEGRATION_API.md#item-reference-reads-version1) and [evidence](../verification/A9-04.md). This document selects no A10 work.

A10 may authenticate ownership and request immutable reference values, structured unavailable reasons, rounded unit/quantity amounts, exact nonspendable derivations and bounded provenance. Use Current for a new capture, or Retained with both saved valuation and FX UUIDs for reproducibility. Store the complete selected result/proof with any durable consumer record; a hash alone is not replay data. Cached PENDING, missing inputs, unsupported components and limits never mean zero or one-cent fallback.

Preserve full identity, quantity/currency, valuation revision/config/recipe/provider versions and captured FX. Do not strip names/components to obtain an easier price. Generic mod integrations contribute immutable data explicitly; existing recipes alone do not opt a mod item in. Vanilla registry classification is distinct from supported numeric paths and every possible component permutation.

Reference reads do not mint money, establish collateral/wealth qualification, discover a listing, quote a trade or authorize goods/settlement. A9-05/A9-06 still own source-neutral durable facts, epochs, carry retirement, prospective activation and listing spread proofs. Existing temporary trader source and financial contracts remain authoritative until their separately selected changes. No specific integration mod is installed by this handoff.
