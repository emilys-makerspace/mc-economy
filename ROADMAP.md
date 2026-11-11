# Roadmap

Economy's delivered milestones and planned features. Future scope and order may change as systems are built and playtested.

## Stage A — Core economy

Stage A implementation is complete. See the [README](README.md) to get started with the alpha and the [changelog](CHANGELOG.md) for delivered changes.

### A1–A6 — Banking and commerce foundation

The alpha includes multiple currencies, bank accounts, physical cash, player shops, bank-branded terminals, issued cards, fixed unsecured loans, credit reporting, collections and optional automatic payments.

### A7 — Village banking

New eligible vanilla villages can generate staffed bank branches. Branches have native-currency ATMs, branded service kiosks and a banker desk. Existing narrow kiosks can be upgraded when space and support allow. Difficult terrain can leave a branch pending, and explored villages are preserved.

### A8 — Economic simulation

Monetary villager and wandering-trader commerce contributes to observed economic activity. Exchange rates and currency-specific loan prime update on a 20-minute schedule after activation. Existing fixed-rate loans retain their agreed terms.

### A9 — Basic item valuation

Versioned reference values combine configured base values and item overrides with costs derived from supported recipes. Server integrations can read values and provenance through the valuation API. Operators can opt supported villager and wandering-trader offers into reference pricing.

### A10 — Core integration

Core integration delivered localized banking navigation, transaction history and review screens, with layouts down to a 320×240 logical GUI viewport. Acceptance covered two authenticated local clients, reconnect recovery, a linked debt lifecycle and a complete-world backup restore. External-client verification remains pending. See the [server guide](docs/SERVER_GUIDE.md#operational-reference-measurements) for the scope of the reference workload.

## Stage B — Planned features

### B2 — Securities and portfolio valuation

- Add securities holdings and current market valuations.
- Include supported investments in net worth and banking qualification, alongside existing assets and debts.

### B4 — Trading

- Add order execution and trading interfaces beyond the current decorative Stock Trading Computer.
- Settle trades, holdings, cash and banking fees together.
- Apply the planned account-tier trading fees: 0.25% for Tier 1 and zero for Tier 2.

Trading depends on the securities and valuation foundations.

### B6 — Expanded lending

- Add variable-rate loans with clear rate-reset rules.
- Add collateral-backed products with ownership checks, valuation and collateral handling on repayment or default.

These products need their supporting rate and asset systems before they can become available.

Playtesting and polish can accompany other work. Phase labels describe scope, and release dates are open.
