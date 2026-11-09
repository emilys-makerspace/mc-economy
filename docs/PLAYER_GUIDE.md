# Player guide

[Back to README](../README.md) · [Server guide](SERVER_GUIDE.md)

## Banking and shops

Right-click a teller or ATM, or use an active issued card for account, history, FX and claim access. Debit cards provide banking access without borrowing. The banking home page lets you choose My accounts, Deposit, Withdrawal, Exchange or Collect items. Issued cards offer the services available remotely. Click an existing account to see its transactions, or open account management to view its balance. Manage account is also available from transaction history. History and collection lists have bounded Next pages.

### Accounts and branches

The account limit is four open bank accounts, including the permanent primary, with one open account per currency. Existing accounts, balances and history remain available. Opening, closing, assigning a bank and switching providers require the appropriate branded branch kiosk. New primaries require a Tier 1/hybrid bank matching the world currency. Legacy accounts can be assigned at a matching-currency kiosk; provider switching requires a source visit then the named target within seven financial days. Tellers, ATMs and remote cards continue to serve existing accounts.

The institution catalog contains 23 fictional banks, including two CHF retail banks. Legacy accounts retain an unassigned provider until their owner assigns a bank at a matching-currency branch kiosk. Institution capital is funded once per world using the selected world currency and a captured FX publication; it does not credit player accounts.

Original issuer and network art now covers all 23 banks, approved card variants, matching native-currency ATMs and tellers, and distinct branch kiosk designs. Bank-specific hardware is selected in authenticated terminal settings. Issued debit cards are available at the account issuer's kiosk; issuer kiosks also offer credit applications. Every current card design is available as a creative collectible.

### Tiers, exchange fees and deposit interest

Assigned bank accounts use the bank's service tier. Tier 2 qualification requires at least 100,000,000 in the current world currency after supported assets and unpaid liabilities are valued together. Five hybrid banks and five specialists offer Tier 2; unavailable valuations cannot grant it. Existing unassigned accounts retain their earlier access and pricing during migration.

Tier 1 FX costs 0.50% of the source principal, rounded upward to a source minor unit; Tier 2 FX is free. Previews show principal, fee and the amount received. A branded terminal accepts only its bank's native cash currency per batch. Some cash totals cannot exactly fit principal plus the rounded fee; adjust the total or deposit into an account in that currency first.

Eligible assigned deposits earn 0.50% nominal APR at Tier 1 or 1.00% at Tier 2. Yield uses the funded time during each 20-minute financial day, with payouts at world-aligned 28-day boundaries. It is paid from the bank's own cash. Earned unpaid yield stays separate from spendable funds when a payout must wait; no replacement money is minted. Specialist accounts below qualification retain protected access but earn no future deposit yield. Fixed unsecured bank and Ironbridge subprime loans now have server-side underwriting, funded disbursement, monthly schedules and protected repayment, including explicit currency conversion. Production credit and loan borrowing APIs require complete default/reporting safeguards and current financial catch-up. Securities trading remains unavailable.

### Overdue debt and collections

Overdue credit and loan balances now have server-side late reports, default, funded collection transfers and protected repayments. Positive partial collection payments extend the two-year collection window. Default reports retain their original seven-year expiry, including after full payment. Collection expiry ends demands while historical unpaid liabilities remain recorded. The reporting and collection lifecycle is accepted for current and verified histories. Production borrowing APIs require the installed reporting dependency and completed financial catch-up. Use Bank and credit for applications, repayments and private reports.

### Issued debit cards

Use **Issued cards** to view an account's debit token. First issuance is free at the account's bank kiosk. Tokens last five financial years; renewal is free within 28 financial days before expiry or afterward. Earlier replacement costs 25 in the world's currency, converted into your selected funding account with a combined FX preview. Replacement revokes every copy of the old token only when the new issuance and fee commit. Report lost is free from an authenticated banking menu.

Receive committed cards through **Collect items**, including at the kiosk. Full inventory leaves an owner claim; uncertain delivery stays protected for evidence review. Copied items share one token status and cannot grant another owner's access or duplicate benefits. Issued debit cards create no credit score, loan or debt; creative copies carry no issued token and are collectibles.

### Credit profiles

**Credit profile** shows whether credit is established and provides saved scores, component explanations and paged history. Login, debit cards, previews and failed applications do not establish credit. The first real credit-card issuance starts at 650 with one 2,500 starter limit across issuers. Scheduled scores update every world-aligned 28 days from original report history and time-weighted revolving utilization. Calculations resume after restart. Lower score ceilings never erase existing debt or enlarge spending room.

### Credit facilities

Credit-facility processing now supports funded limits, purchase and advance balances, daily compound interest, monthly statements and repayments. Advances are limited to 20% of the facility limit in outstanding principal. Issuer kiosks offer credit applications, limit increases and replacement cards. Bank and credit offers owner repayments and physical-terminal advances. Production credit issuance, increases and draws require the accepted reporting dependency and current financial state. Reads and protected existing-debt actions remain available. Shared reporting and collection-clock services are installed.

### Setting up automatic payments

Open Bank and credit, then Automatic payments to choose a facility, a same-currency funding account and a payment target. Review and confirm each change. Payments start OFF for every facility. Cards support required minimum, statement balance less subsequent payments, and current full payoff. Loans pay total due installment arrears, capped by debt.

Automatic payments debit the entire target only when the named account has sufficient available funds. Failed attempts retry once per financial day for two days after the original due date. They continue while you are offline and stop for that window when the target is met, you opt out, or the facility closes or defaults. Automatic payments use the selected currency and account. FX repayment remains a separately reviewed manual action. Disable linked automatic payments before closing a funding account.

Changing settings cancels the old retry consent and applies to future due windows. Existing arrears may still need manual payment. Paying only the minimum leaves a larger statement or full-payoff target active until it is satisfied or its retry window ends. Spending after a completed target belongs to a later due window.

### Statements and balances

**Credit facilities** lists your issued facilities. Select one to review its limit, contractual headroom, purchase/advance rates, current balance, rounding carry, outstanding minimum and past-due amount. **Statements** shows the original dated amounts in the facility currency; later payments do not rewrite them. A pending-work label means the view shows the saved balance while scheduled processing catches up.

### Credit and loan commands

`/economy credit facility <facility-uuid>` reads your own facility.

Use `/economy loan list` and `/economy loan contract <loan-uuid>` for saved loan terms, debt and due status. Append `schedule` or `installments` to view original monthly rows, or `payoff <owned-funding-account-uuid>` for a preview without making a payment. `/economy loan application <application-uuid>` shows the original application decision. Lists use the next cursor shown in the response. Pending monthly work is labeled before a current payoff is available.

Use `/economy debt payments <card|loan> <facility-uuid>` for payment history, `/economy debt cases` for collection clocks and current creditors, `/economy debt reports` for original and cured report revisions, and `/economy debt history <case-uuid>` for collection payment, sale and write-off receipts. Follow the next-page command in each reply.

### Deposits, withdrawals and exchanges

Only Deposit shows the player inventory. Deposit and Withdrawal each provide an account selector.

ATMs and tellers start disabled and unbound. Their owner or an operator selects a bank, confirms its native currency and enables cash services. Each machine accepts or dispenses that bank's cash only; ATMs use bills and tellers can use coins. Select any owned account and review FX when its currency differs. Sneak-right-click with an empty hand to configure, then choose Use terminal to return to banking.

For a deposit, place cash in the 24 input slots, choose Review and check the bank credit, then confirm. Issued cash is redeemed against its remaining world quantity; unmarked Creative or command cash is minted and consumed by that one saved deposit. Changing pages or closing before reservation returns the originals; anything that cannot fit is retained as an owner claim.

For a withdrawal, choose your account and enter an amount, then Review and Confirm. The default uses the fewest bills; choose **Cash select** after reviewing to adjust each bill count with −/+, then **Review mix**. **Default mix** restores the automatic selection. Tellers fill any remaining amount with coins; ATM bill totals must match exactly. Where both note styles are available, each bill has a 1-in-20 chance of being vertical. The reviewed batch stays fixed through confirmation. Debit cards have one design per bank; Tier 1/2 belongs to the account service level. The bank determines the cash currency; a different account currency requires an explicit FX preview and its normal tier fee. Same-currency cross-bank service has no surcharge. A teller can dispense coins. The complete batch must fit before debit. A register owner holds the product batch and enters a whole-unit price in Shop. Buyers preview the exact batch and price before confirming.

Exchange has From and To account selectors. CHF pairs accept exact source-currency cents; other pairs retain whole-unit input. Review the debit, credit and fee before confirming. Stock trading accounts and screens are excluded.

### Collecting items and checking payments

Collect items lists cash and goods waiting for delivery. Click a batch to collect it when the whole batch fits in your inventory. Check payment resolves an uncertain request using its existing operation identity. Restored or uncertain physical deliveries require operator evidence review; the mod does not automatically create replacement items or repeat a charge.

Older worlds with incomplete financial history stop before financial readiness with a specific reconciliation reason, preserving their existing balances and records.

### Collection Agent encounters

Creative builders can place a persistent, stationary representative with `/summon economy:collection_agent ~ ~ ~` or the **Collection Agent Spawn Egg**, available in the Economy and Spawn Eggs tabs. `/give @s economy:collection_agent_spawn_egg` also gives operators the egg. Placed representatives offer the same private case and payment dialogue to each visitor, including an empty case list if nothing is collectible. They create no debt, do not follow players and do not alter automatic-agent replacement cooldowns.

A Collection Agent now follows online debtors with collectible cases. Right-click within six blocks to review current creditors and balances, choose an owned BANK account, preview a partial or full payment and confirm its exact debit. A different currency shows an explicit FX/fee review. Exit leaves debt unchanged. The agent is nonhostile and gives no loot or experience. Killing it delays replacement for seven financial days. A crash leaving an uncertain live presentation conservatively protects the same window from recovery. Saved payment receipts recover after reconnect even when final settlement removed the agent.

## Shops

Register owners can use `/economy shop price <units>` while looking at their register and holding the exact product batch, or set a whole-unit price in Shop. The price uses the seller's primary-account currency. Buyers review the exact batch and price before confirming and need room for the entire batch. Account-funded purchases require matching buyer currency; an active owned credit card can fund a purchase through the credit-card flow described below. Undelivered paid goods remain protected claims.

## Physical terminals

The Economy Terminals creative tab contains an ATM and register in a neutral finish and all 16 dye colors, plus a teller and branch kiosk. ATMs and tellers occupy two blocks across and two high; both lower cells need solid support. Place them facing you with the whole space clear. Breaking any machine cell removes the entire machine and returns one item in survival. Branch kiosks are one block wide and two high, with solid support under their lower anchor. Registers occupy one block and can sit directly on the stock chest below.

Craft a neutral ATM with iron ingots, glass pane, redstone and a chest. The teller also needs a hopper; registers use iron ingots, glass pane, redstone and a chest in a smaller pattern. Register dye recipes remain available. ATM dye recipes are retired; existing colored machine IDs and items remain compatible. Craft an unbound kiosk with iron/glass/iron, iron/redstone/iron, iron/chest/iron. A broken or recolored machine receives fresh owner and placement settings when placed again.

Owners configure ATMs, tellers and kiosks by sneaking and right-clicking with an empty hand. Select a bank, review its native currency and confirm. New and legacy unbound machines cannot start cash or FX operations. Pending old transactions and claims remain protected. For ATM/teller cash settings after binding, choose a mode, select **Review settings**, check the terminal, revision and complete bill/coin list, then select **Confirm these settings**. Reviewing alone changes nothing. If another edit changes the terminal, use **Refresh settings** and review the current values again. ATM uses bills, teller uses bills and coins. A binding is fixed after cash service has first been enabled for that placement.

ATMs and tellers open banking screens; kiosks open branch account services; registers open the shop and protected-purchase claims. The Stock Trading Computer is available as a decorative block only and has no trading screen or economic behavior. Operators can look at a ready terminal and use `/economy admin config` to open its privileged configuration screen. `/economy admin binding` reports the looked-at terminal's bank, currency, migration state, generation, geometry and blocking cash work without changing it.

## Cash and cards

Creative inventory includes 131 cash collectibles across thirteen tabs, including both Canadian bill styles. **Bank & Credit Cards** contains every available bank debit and credit card design, with one debit design per bank and network/tier variants for credit cards. Creative cards are collectibles; banking access requires an active card issued to you. The eight old generic cards have been removed.

CHF is the eleventh currency, with two-decimal electronic balances. Its game opening calibration is 1 CHF = 1.60 CAD, not a live market quote. Fresh worlds include CHF immediately; existing worlds add it at the next UTC FX publication after catch-up, without changing the world currency or inventing historical CHF rates. Operator diagnostics show whether its rate is available or scheduled.

Original CHF cash includes 10, 20, 50, 100, 200 and 1,000 notes, plus 0.05, 0.10, 0.20, 0.50, 1, 2 and 5 coins. Tellers support notes and coins; ATMs use notes only. Withdrawals must be exactly representable with enabled cash: 1.02 CHF is rejected rather than silently changed. Electronic balances can still hold 0.01 CHF. CHF cash conversions and CHF account settlement preserve account cents; other legacy currency groups keep their existing whole-unit rounding.

Cash taken from the Creative inventory or created with commands is accepted as newly minted cash when deposited. Existing world-marked cash still has its issued quantity checked against the world ledger and custody evidence. Trusted server APIs support deposit previews and returns, plus exact withdrawals with full-batch capacity checks.

## Bank and credit services

Open **Bank and credit** from an ATM, teller or authenticated card. At a branch kiosk, choose **Cards and applications** or **Bank and credit services**. Use **Actions** to choose the next offered account, facility or service. Enter an amount and select its review button, then read the terms and use **Actions** to confirm. Loan applications accept a principal and term in years, such as `1000 2`.

Credit and loan applications require the issuer’s live branch kiosk. Card replacement also requires the issuer’s branch. Moving away, breaking or rebranding the kiosk invalidates its authorization. Rates, minimums, dates, fees and payment consequences appear before confirmation. Net worth and tier explains the exact 100,000,000 threshold in current server currency. Fixed unsecured bank and subprime products are available according to underwriting. Variable-rate, collateral and trading services remain future work.

Hold an active owned credit card when reviewing a register purchase to pay with that card. The confirmed charge and protected goods claim settle together. A cash advance at an ATM or teller credits the selected owned account first. Physical cash uses a separate Withdrawal review and confirmation. Interest begins on the advance immediately, with no advance fee. Outstanding advance principal is capped at 20% of the facility limit and available overall credit.

Credit, loan and collection repayments can be reviewed remotely through authenticated banking. Select an owned funding account and review any explicit conversion and fee. Loan payoff uses a short-lived exact quote. Collection payment reviews explain acknowledgement, collection expiry and the original default report date. Use **Check payment** to resolve an uncertain original operation before starting another mutation.

## Financial time and card rates

The current release includes 23 banks, CHF notes and coins, branded banking hardware, issuer-linked cards, fixed unsecured credit and loans, private reports and manual collection payments. A financial day lasts 20 real minutes, including time while the server is stopped. Statements close every 28 financial days. A financial year has 13 months.

| Card network | Purchase APR | Advance APR |
| --- | --- | --- |
| Keystone | 21.99% | 23.99% |
| Orbit | 19.99% | 21.99% |
| Wayline | 19.99% | 21.99% |
| Ember | 19.99% | 21.99% |

Automatic payments support explicit per-card or per-loan consent from one named same-currency bank account. Securities trading, variable-rate and collateral-backed loans await their integration phases. Worlds with unsupported historical defaults or missing obligation history require reconciliation before affected borrowing becomes available.

## Ledger commands

- `/economy ledger balance` shows posted, available, and reserved funds on your primary account.
- `/economy ledger pay <recipient-uuid> <coins>` pays another player whose primary account uses the same currency. Keep the retry command printed at submission to check or safely retry an uncertain result.
- `/economy ledger history <account-uuid> ["cursor"]` lists signed postings for your own account, including closed accounts. Transaction IDs link the entries of one settlement.
