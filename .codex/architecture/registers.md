# Register sales

Schema 17 stores owner prices, frozen sales, source high-water observations, stock locks and register merchant grants. RegisterSales handlers run on the storage worker; all Minecraft observations and mutations remain server-thread callbacks.

A price binds terminal world/anchor/generation/revision, owner, seller primary account/revision/currency, price revision, normalized exact component template and batch (1–64). Quote also freezes buyer primary and source generation/revision/fingerprint. Both primaries must share currency; self-purchases reject. Prices are positive whole-major bank units. CONFIGURE requires current owner/operator authorization. The shop price command and future menu use the same handler.

RESERVE requires full buyer capacity and locks the entire batch before physical removal. RegisterInventory targets the already-loaded container directly below the register. It rechecks generation, revision and fingerprint, removes exact components and retains originals in source persistent data. SAVED requires independently verified source-save evidence and exactly one removal revision. COMMIT rechecks authority, primaries, price, source and full capacity. Holds.checkout and MerchantPayments.checkout atomically capture a real hold, bank transfer, invoice, receipt and buyer custody obligation. Retry retains the original intent. Register merchant grants allow the seller to use existing receipt refund rules.

Mandatory Authority, Capacity, SourceReader and EvidenceVerifier adapters are trusted detached observations. Do not call Minecraft from worker jobs. Final controllers must bind online actor, dimension, reach, terminal/menu generation and current capacity to admission, then verify actual source/player save evidence. Ledger acknowledgment and setChanged are not disk witnesses.

RegisterInventory delivers one complete batch with source and player replay markers, never automatic drops. Known undelivered paid goods become buyer custody claims. Prepayment cancellation either proves removal never occurred, returns saved exact originals into a seller claim, or proves the originals were restocked and saved. A RESERVED cancellation must prove no physical removal even if hopper movement changed stock. Unknown interruptions quarantine; startup reconciliation is bounded to 16 cases per pass and does not invent goods. Seller claim locks remain held until custody closes. A proven restock updates source high-water without making another claim.

Limits: source containers at most 54 slots, observed quantity at most 4096, fingerprint NBT at most 64 KiB, normalized template at most 2048 encoded bytes with an 8192-byte NBT decoding budget, and 16 physical reservations/receipts. After independently verified source/player saves and current terminal journal completion, clearRestocked/clearDelivered prune completed markers. Missing originals continue to reject physical replay after cleanup. Do not prune pending/uncertain markers. Cleanup is an adapter contract, not a substitute for save verification.

Physical terminal blocks and purchase menus remain A5-08/A5-09. No external claim-protection integration is included. See [verification](../A5/verification/A5-07.md).


Current player-facing integration is delivered in [A5-09 banking menus](banking.md); that contract supersedes earlier statements here that menus are pending. Existing subsystem safety and save-evidence requirements still apply.
