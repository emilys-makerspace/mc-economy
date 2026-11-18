# Deferred organization identity and delegated access

Status: core player-corporation scope assigned to [B0 corporation packages](../B0-corporations/EXECUTION.md) on 2026-11-13. Specific integration adapters remain deferred. This planning note does not authorize implementation.

A3 provides stable, typed account owner references, player and system authorization, and audit actors. B0 drafts now own core organization registration, sole-owner assignment, scoped appointed managers/revocation, corporate accounts/payments and the extension API. Specific merchant/treasury integrations consume accepted core authority through independently selected adapters.

Use planned MineColonies and casino support as integration inputs when the task is scheduled. Establish how each integration maps its own entities and authority into the shared account API, handles authority changes, and preserves account and audit history. Verify that revocation takes effect for later actions and that callers cannot impersonate another owner or integration.

The user requested player-created corporations/companies, corporate accounts and an extension API for other mods. Follow-up answers approve one owner with appointed managers initially, and accounts/payments/transfers before corporate borrowing. B0 is the independent delivery phase. The user approved [C01–C08](../B0-corporations/POLICY-REVIEW.md), resolving initial registration/bank/manager/API policies under [BD10](B-DECISIONS.md). Actual source/ordinary-fee feasibility remains for B0-01; no implementation is selected. No shared equity, automatic public listing or corporate credit is approved by this scope.

The user subsequently requested [B8 equity/governance/actions](../B8-advanced-markets/CORPORATIONS-EXPANSION.md): private shares/classes/issues, optional listing, same/cross-player mergers and founder-to-voting transition. Thus prior initial-B0 shared-equity exclusions do not prohibit this explicitly added B8 scope. Governance/control/grant succession remains a separate authoritative layer, not implicit personal-account authority.
