package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditTreasury;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import java.time.*;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;

/**
 * Worker-confined, captured-supplier NPC cash FX. Produces typed groups for one NPC overlay;
 * it never writes a ledger balance or grants account/trader authority by itself.
 */
public final class NpcCashFx {
    public record Quote(FxQuotes.Quote conversion,ServicePricing.Quote pricing,InstitutionCatalog.InstitutionId supplier,Pricing.Side side,Money goodsGross,
                        Instant issuedAt,Instant expiresAt) {
        public Quote {
            Objects.requireNonNull(conversion);Objects.requireNonNull(pricing);Objects.requireNonNull(supplier);Objects.requireNonNull(side);Objects.requireNonNull(goodsGross);
            Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);
            if(goodsGross.minorUnits()<=0 || !expiresAt.isAfter(issuedAt) || expiresAt.isAfter(issuedAt.plusSeconds(30)))
                throw new IllegalArgumentException("NPC FX quote lifetime/amount");
        }
    }
    public record Plan(FxQuotes.Quote conversion,ServicePricing.Quote pricing,InstitutionCatalog.InstitutionId supplier,
                       List<NpcSettlement.Group> groups,List<FinancialAccounts.Account> accounts,
                       List<Journal.Record> facts,JournalEngine.Projection registrations) {
        public Plan {
            Objects.requireNonNull(conversion);Objects.requireNonNull(pricing);Objects.requireNonNull(supplier);
            groups=List.copyOf(groups);accounts=List.copyOf(accounts);facts=List.copyOf(facts);Objects.requireNonNull(registrations);
        }
    }
    private final FxQuotes conversions;
    private final ServicePricing pricing;
    private final Map<UUID,Quote> issued=new HashMap<>();
    public NpcCashFx(FxQuotes conversions,ServicePricing pricing) {
        this.conversions=Objects.requireNonNull(conversions);this.pricing=Objects.requireNonNull(pricing);
    }

    /** The supplier is resolved from the captured market slot-0 bank or Crownstone fallback. */
    public Quote issue(JournalEngine.ReadView reads,WorldId world,PlayerId owner,InstitutionCatalog.InstitutionId supplier,
                       Pricing.Side side,Money goodsGross,EconomyCurrency cashCurrency,Instant now,Instant tradeExpiry) {
        Objects.requireNonNull(side);Objects.requireNonNull(goodsGross);Objects.requireNonNull(now);Objects.requireNonNull(tradeExpiry);
        supplier(reads,world,supplier);
        if(goodsGross.minorUnits()<=0 || goodsGross.currency()==cashCurrency)throw new IllegalArgumentException("NPC_FOREIGN_FX_REQUIRED");
        var bank=supplier;
        var publication=publication(reads,now);
        Instant expires=earlier(tradeExpiry,now.plusSeconds(30));
        // Retain the installed 48-hour policy and its actual legacy effective time. A8-06
        // will replace this shared daily reader with exact-effective-time snapshot reads.
        expires=earlier(expires,publication.day().atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48)));
        if(!expires.isAfter(now))throw new IllegalArgumentException("NPC_FX_EXPIRED");
        issued.values().removeIf(q->!now.isBefore(q.expiresAt()));
        if(issued.size()>=1024)throw new IllegalStateException("NPC_FX_QUOTE_OVERLOAD");
        FxQuotes.Quote conversion;ServicePricing.Quote priced;
        if(side==Pricing.Side.BUY) {
            conversion=conversions.issue(owner,new FxQuotes.Pair(cashCurrency,goodsGross.currency()),FxQuotes.Side.EXACT_CREDIT,
                    goodsGross,publication,now,Duration.between(now,expires));
            priced=pricing.quote(reads,world,owner,bank,ServicePricing.Service.NPC_CASH_FX,conversion.debit(),now,expires);
        } else {
            priced=pricing.quote(reads,world,owner,bank,ServicePricing.Service.NPC_CASH_FX,goodsGross,now,expires);
            long net=Math.subtractExact(goodsGross.minorUnits(),priced.fee().minorUnits());
            if(net<=0)throw new IllegalArgumentException("NPC_FX_NET_ZERO");
            conversion=conversions.issue(owner,new FxQuotes.Pair(goodsGross.currency(),cashCurrency),FxQuotes.Side.EXACT_DEBIT,
                    new Money(goodsGross.currency(),net),publication,now,Duration.between(now,expires));
        }
        if(!conversion.world().equals(world) || !conversion.actor().equals(owner))throw new IllegalArgumentException("NPC_FX_BOOK_WORLD");
        if(conversion.debit().minorUnits()<=0 || conversion.credit().minorUnits()<=0)throw new IllegalArgumentException("NPC_FX_ZERO_PRINCIPAL");
        if(side==Pricing.Side.BUY)Math.addExact(conversion.debit().minorUnits(),priced.fee().minorUnits());
        var quote=new Quote(conversion,priced,supplier,side,goodsGross,now,
                earlier(expires,earlier(conversion.expires(),priced.expires())));
        issued.put(conversion.id(),quote);return quote;
    }

    /** Call after original financial lookup, inside authoritative worker preparation. */
    public Quote requireFresh(JournalEngine.ReadView reads,Quote quote,WorldId world,PlayerId owner,InstitutionCatalog.InstitutionId supplier,Instant now) {
        Objects.requireNonNull(now);
        if(quote==null || !quote.equals(issued.get(quote.conversion().id())) || now.isBefore(quote.issuedAt())
                || !now.isBefore(quote.expiresAt()))throw new IllegalArgumentException("NPC_FX_QUOTE_STALE");
        supplier(reads,world,supplier);
        if(!supplier.equals(quote.supplier()))throw new IllegalArgumentException("NPC_FX_PROVIDER_STALE");
        var publication=publication(reads,now);
        var conversion=conversions.requireFresh(quote.conversion().id(),owner,world,publication,1,now);
        var priced=pricing.requireFresh(quote.pricing(),reads,world,owner,now);
        if(!conversion.equals(quote.conversion()) || conversion.pricing()!=null || conversion.fee().minorUnits()!=0
                || !priced.institution().key().equals(supplier.key()) || priced.service()!=ServicePricing.Service.NPC_CASH_FX
                || !priced.fee().equals(ServicePricing.fee(priced.principal(),ServicePricing.Service.NPC_CASH_FX,priced.tier())))
            throw new IllegalArgumentException("NPC_FX_PRICING_STALE");
        if(!reads.query("SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",List.of(conversion.id().toString()),1).isEmpty())
            throw new IllegalArgumentException("NPC_FX_QUOTE_CONSUMED");
        if(quote.side()==Pricing.Side.BUY) {
            if(conversion.side()!=FxQuotes.Side.EXACT_CREDIT || conversion.pair().target()!=quote.goodsGross().currency() || !conversion.credit().equals(quote.goodsGross())
                    || !conversion.requested().equals(quote.goodsGross()) || !priced.principal().equals(conversion.debit()))
                throw new IllegalArgumentException("NPC_FX_PRINCIPAL_STALE");
        } else {
            long net=Math.subtractExact(quote.goodsGross().minorUnits(),priced.fee().minorUnits());
            if(net<=0 || conversion.side()!=FxQuotes.Side.EXACT_DEBIT || conversion.pair().source()!=quote.goodsGross().currency()
                    || !priced.principal().equals(quote.goodsGross())
                    || !conversion.debit().equals(new Money(quote.goodsGross().currency(),net))
                    || !conversion.requested().equals(conversion.debit()))throw new IllegalArgumentException("NPC_FX_PRINCIPAL_STALE");
        }
        return quote;
    }

    /** Returns group order relative to the goods leg: BUY before retirement; SELL after gross issue. */
    public Plan prepare(JournalEngine.ReadView reads,Quote quoted,WorldId world,PlayerId owner,InstitutionCatalog.InstitutionId supplier,Instant now) {
        var quote=requireFresh(reads,quoted,world,owner,supplier,now);var conversion=quote.conversion();
        var groups=new ArrayList<NpcSettlement.Group>();var accounts=new ArrayList<FinancialAccounts.Account>();
        var facts=new ArrayList<Journal.Record>();var from=conversion.pair().source();var to=conversion.pair().target();
        long debit=conversion.debit().minorUnits(),credit=conversion.credit().minorUnits(),fee=quote.pricing().fee().minorUnits();
        var bank=quote.supplier();
        var depositor=new InstitutionCatalog.InstitutionId("economy:depositor/"+owner.value());
        if(fee>0) {
            var cash=FinancialAccounts.account(world,bank,FinancialAccounts.Role.CASH,from);
            var income=FinancialAccounts.account(world,bank,FinancialAccounts.Role.INCOME,from);
            var expense=FinancialAccounts.account(world,depositor,FinancialAccounts.Role.EXPENSE,from);
            accounts.addAll(List.of(cash,income,expense));
            UUID payer=NpcSettlement.bridge(world,from);
            var payerRole=NPC_BRIDGE;
            groups.add(new NpcSettlement.Group(PostingBatch.Authority.TRANSFER,List.of(
                    leg(payer,payerRole,from,-fee),leg(cash.id(),INSTITUTION_CASH,from,fee))));
            groups.add(new NpcSettlement.Group(PostingBatch.Authority.TRANSFER,List.of(
                    leg(expense.id(),INSTITUTION_CLAIM,from,fee),leg(income.id(),INSTITUTION_CLAIM,from,-fee))));
            for(var account:accounts)if(FinancialAccounts.find(reads,account.id()).isEmpty())facts.add(new Journal.Record(InstitutionCapital.ACCOUNT,1,
                    account.id()+"|"+world.value()+"|"+account.owner().key()+"|"+account.role()+"|"+account.currency()));
        }
        UUID source=NpcSettlement.bridge(world,from);
        UUID target=NpcSettlement.bridge(world,to);
        groups.add(new NpcSettlement.Group(PostingBatch.Authority.FX,List.of(
                leg(source,NPC_BRIDGE,from,-debit),
                leg(Settlement.clearingAccount(from),CLEARING,from,debit),
                leg(Settlement.clearingAccount(to),CLEARING,to,-credit),
                leg(target,NPC_BRIDGE,to,credit))));
        // Generic FxSettlement facts would misstate SELL gross-fee semantics and duplicate
        // activity. The owning NPC receipt names the actual conversion principal instead.
        facts.add(ServicePricing.fact(quote.pricing()));
        return new Plan(conversion,quote.pricing(),quote.supplier(),groups,accounts,facts,tx->{
            if(fee>0) {
                tx.execute("INSERT OR IGNORE INTO institution_parties VALUES(?,?,?,?,?,'INTERNAL',1)",List.of(world.value().toString(),depositor.key(),
                        new InstitutionCatalog.SystemActor(world,depositor).actorId().value().toString(),"Depositor accounting",from.name()));
                for(var account:accounts)FinancialAccounts.register(tx,account);
            }
            tx.execute("INSERT INTO ledger_fx_quotes(quote_id) VALUES(?)",List.of(conversion.id().toString()));
        });
    }

    private static void supplier(JournalEngine.ReadView reads,WorldId world,InstitutionCatalog.InstitutionId id) {
        var bank=InstitutionCatalog.require(id);
        if(!bank.enabled()||!bank.customerBank())throw new IllegalArgumentException("NPC_CASH_FX_SUPPLIER_UNAVAILABLE");
        var rows=reads.query("SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",List.of(world.value().toString(),id.key()),1);
        if(rows.size()!=1||!"1".equals(rows.getFirst().get("enabled")))throw new IllegalArgumentException("NPC_CASH_FX_SUPPLIER_DISABLED_OR_MISSING");
    }
    private static FxFacts.Publication publication(JournalEngine.ReadView reads,Instant now) {
        var publication=CreditTreasury.publication(reads,now.toEpochMilli());
        if(publication==null)throw new IllegalArgumentException("NPC_FX_PUBLICATION_STALE");return publication;
    }
    private static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static PostingBatch.Leg leg(UUID account,PostingBatch.Role role,EconomyCurrency currency,long minor) {
        return new PostingBatch.Leg(account,role,currency,new Money(currency,minor));
    }
}
