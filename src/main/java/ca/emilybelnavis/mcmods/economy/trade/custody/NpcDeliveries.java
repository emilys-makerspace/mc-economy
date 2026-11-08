package ca.emilybelnavis.mcmods.economy.trade.custody;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.journal.CashIssuance;
import ca.emilybelnavis.mcmods.economy.journal.Custody;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine.ReadView;
import ca.emilybelnavis.mcmods.economy.journal.WithdrawalDelivery;
import ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashFacts;
import ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashFacts;
import java.util.List;
import java.util.UUID;

/** Owner-bound NPC deliveries that have durable provenance and no unresolved physical effect. */
public final class NpcDeliveries {
    private NpcDeliveries() {}

    // Use the immutable journal rather than projection tables that older schemas may lack.
    private static final String CLAIMABLE = """
            SELECT c.id FROM custody_cases c
            JOIN operation_outcomes o ON o.world_id=(SELECT world_id FROM foundation_metadata WHERE singleton=1)
                AND o.operation_id=c.financial_operation AND o.actor=c.owner
            WHERE c.owner=? AND o.status='COMMITTED' AND o.service IN ('economy:a8','economy:a9')
                AND c.financial_intent=o.operation_id || char(10) || o.actor || char(10)
                    || o.service || char(10) || o.operation_type || char(10) || o.canonical_request
                AND c.compensation_operation IS NULL AND c.acknowledgment IS NULL AND c.delivery_witness IS NULL
                AND ((c.state='COMMITTED' AND c.revision=1 AND c.delivery_attempt IS NULL AND c.evidence GLOB 'UNKNOWN:*')
                    OR (c.state='CLAIM' AND c.evidence GLOB 'UNDELIVERED:*'))
                AND (
                    (c.reservation_operation=c.id AND c.reservation_witness IS NULL AND EXISTS (
                        SELECT 1 FROM journal_records r WHERE r.sequence=o.sequence AND r.record_version=1
                            AND CASE r.record_type
                                WHEN 'economy:a8/npc-trade' THEN o.operation_type='economy:a8/npc/settle'
                                    AND c.id=json_extract(r.payload,'$.outputCustody')
                                    AND c.source='npc-output:' || json_extract(r.payload,'$.trader')
                                    AND c.destination='npc-player-inventory:' || substr(c.owner,8)
                                WHEN 'economy:a8/npc-cash-trade' THEN o.operation_type='economy:a8/npc-cash/settle'
                                    AND ((c.id=json_extract(r.payload,'$.outputCustody')
                                            AND c.source='npc-cash-output:' || json_extract(r.payload,'$.trader')
                                            AND c.destination='npc-cash-player-inventory:' || substr(c.owner,8))
                                        OR (c.id=json_extract(r.payload,'$.cashCustody')
                                            AND c.source='npc-cash:' || json_extract(r.payload,'$.trader')
                                            AND c.destination=c.owner))
                                WHEN 'economy:a9/reference-npc-trade' THEN o.operation_type='economy:a9/reference-npc/settle'
                                    AND c.id=json_extract(r.payload,'$.outputCustody')
                                    AND c.source='npc-output:' || json_extract(r.payload,'$.trader')
                                    AND c.destination='npc-player-inventory:' || substr(c.owner,8)
                                WHEN 'economy:a9/reference-cash-trade' THEN o.operation_type='economy:a9/reference-cash/settle'
                                    AND ((c.id=json_extract(r.payload,'$.outputCustody')
                                            AND c.source='npc-cash-output:' || json_extract(r.payload,'$.trader')
                                            AND c.destination='npc-cash-player-inventory:' || substr(c.owner,8))
                                        OR (c.id=json_extract(r.payload,'$.cashCustody')
                                            AND c.source='npc-cash:' || json_extract(r.payload,'$.trader')
                                            AND c.destination=c.owner))
                                ELSE 0
                            END
                    ))
                    OR (c.state='CLAIM' AND c.reservation_operation<>c.id AND c.destination=c.source
                        AND o.result_payload=c.id
                        AND ((o.operation_type IN ('economy:a8/npc/account-return','economy:a9/reference-npc/account-return') AND o.result_code='NPC_ACCOUNT_INPUT_RETURN'
                                AND c.source='npc-player-inventory:' || substr(c.owner,8))
                            OR (o.operation_type IN ('economy:a8/npc-cash/return','economy:a9/reference-cash/return') AND o.result_code='NPC_CASH_INPUT_RETURN'
                                AND c.source='npc-cash-player-inventory:' || substr(c.owner,8)))
                        AND EXISTS (
                            SELECT 1 FROM custody_cases original WHERE original.id=c.reservation_operation
                                AND original.state='QUARANTINED' AND original.financial_operation IS NULL
                                AND original.owner=c.owner AND original.items=c.items AND original.source=c.source
                        )
                    )
                )
            """;

    public static List<UUID> page(ReadView reads, PlayerId owner, UUID after, int limit) {
        if (limit < 1 || limit > Custody.MAX_PAGE_SIZE) throw new IllegalArgumentException("NPC claim page bounds");
        return reads.query(CLAIMABLE + " AND c.id>? ORDER BY c.id LIMIT ?",
                        List.of(Journal.actorKey(owner), after == null ? "" : after.toString(), Integer.toString(limit)), limit)
                .stream().map(row -> UUID.fromString(row.get("id"))).toList();
    }

    public static boolean isClaimable(ReadView reads, Custody.Case current) {
        if (current.financialOperation() == null || !(current.terms().owner() instanceof PlayerId owner)
                || !current.terms().source().startsWith("npc-")
                || current.state() != Custody.State.COMMITTED && current.state() != Custody.State.CLAIM) return false;
        return !reads.query(CLAIMABLE + " AND c.id=?", List.of(Journal.actorKey(owner), current.id().toString()), 1)
                .isEmpty();
    }

    /** A newly issued lot remains safe if the original NPC payout has no physical effect. */
    public static boolean protectsCash(ReadView reads, CashIssuance.Lot lot) {
        if (lot.revision() != 1 || lot.remaining() != lot.issued() || !lot.evidence().startsWith("npc-cash:")) return false;
        UUID original;
        try {
            original = UUID.fromString(lot.evidence().substring("npc-cash:".length()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        boolean reference=ReferenceCashFacts.stable("lot|"+lot.world().value()+"|"+original+"|"+lot.item()).equals(lot.id());
        if (!reference&&!NpcCashFacts.stable("lot|" + lot.world().value() + "|" + original + "|" + lot.item()).equals(lot.id()))
            return false;
        var rows = reads.query("""
                SELECT r.payload FROM operation_outcomes o
                JOIN journal_records r ON r.sequence=o.sequence
                WHERE o.world_id=? AND o.operation_id=? AND o.status='COMMITTED'
                    AND r.record_type=? AND r.record_version=1
                """, List.of(lot.world().value().toString(), original.toString(), reference?ReferenceCashFacts.RECEIPT:NpcCashFacts.RECEIPT), 1);
        if (rows.isEmpty()) return false;
        var payload=rows.getFirst().get("payload");
        var actor=reference?ReferenceCashFacts.decodeReceipt(payload).actor():NpcCashFacts.decodeReceipt(payload).actor();
        UUID custody=reference?ReferenceCashFacts.decodeReceipt(payload).cashCustody():NpcCashFacts.decodeReceipt(payload).cashCustody();
        if(custody==null)return false;
        var payout=Custody.find(reads,actor,custody,false).orElse(null);
        return payout != null && isClaimable(reads, payout)
                && WithdrawalDelivery.claims(payout).stream()
                        .filter(claim -> claim.lot().equals(lot.id()) && claim.world().equals(lot.world())
                                && claim.item().equals(lot.item()))
                        .mapToInt(CashIssuance.Claim::count).sum() == lot.issued();
    }
}
