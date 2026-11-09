package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

/** Worker-owned debit tokens. Item copies reference these records; they create no account, debt or score. */
public final class IssuedCards {
    public static final String FACT="economy:issued_card";
    public static final Journal.Kind ISSUE=new Journal.Kind("economy:cards","economy:issue");
    public static final Journal.Kind REVOKE=new Journal.Kind("economy:cards","economy:revoke");
    public static final long DAY_MILLIS=1_200_000L, LIFETIME=1_820L*DAY_MILLIS, RENEWAL_WINDOW=28L*DAY_MILLIS;
    public enum Product { DEBIT, CREDIT }
    public enum Status { ACTIVE, REVOKED, EXPIRED }
    public record Token(WorldId world,UUID id,PlayerId owner,InstitutionCatalog.InstitutionId issuer,AccountId account,
                        Product product,String network,int tier,long providerEpoch,Status status,long issuedAt,long expiresAt,
                        long revision,UUID previousToken,UUID custodyId) {
        public Token {
            Objects.requireNonNull(world);Objects.requireNonNull(id);Objects.requireNonNull(owner);InstitutionCatalog.require(issuer);
            Objects.requireNonNull(account);Objects.requireNonNull(product);Objects.requireNonNull(network);Objects.requireNonNull(status);Objects.requireNonNull(custodyId);
            if(product!=Product.DEBIT || !network.isEmpty() || tier<1 || tier>2 || providerEpoch<0 || issuedAt<0
                    || expiresAt!=Math.addExact(issuedAt,LIFETIME) || revision<1 || !custodyId.equals(id))throw new IllegalArgumentException("Card token fields");
        }
    }
    /** Captured fee inputs are opaque here, but every byte is canonical and the fee service revalidates them in this transaction. */
    public record FeeQuote(UUID id,AccountId funding,long fundingRevision,long denominationRevision,EconomyCurrency currency,
                           long debitMinor,long expiresAt,String capture) {
        public FeeQuote {
            Objects.requireNonNull(id);Objects.requireNonNull(funding);Objects.requireNonNull(currency);Objects.requireNonNull(capture);
            if(fundingRevision<1 || denominationRevision<0 || debitMinor<1 || expiresAt<1 || capture.isBlank()
                    || capture.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("Card fee quote");
        }
    }
    @FunctionalInterface public interface Fee {
        Decision prepare(Journal.Intent intent,FeeQuote quote,ReadView reads) throws Exception;
    }
    public record Request(BranchAdmission.Proof proof,long expectedTierRevision,UUID previousToken,FeeQuote fee) {
        public Request {Objects.requireNonNull(proof);if(expectedTierRevision<0)throw new IllegalArgumentException("Tier revision");}
        public Journal.CanonicalRequest request() {
            var c=proof.context();var fields=new TreeMap<String,String>();
            fields.put("economy:owner",Journal.actorKey(c.actor()));fields.put("world",c.world().value().toString());
            fields.put("lease",proof.id().toString());fields.put("session",c.session().toString());fields.put("action",c.action().name());
            fields.put("bank",c.bank().key());fields.put("account",c.account()==null?"":c.account().value().toString());
            fields.put("account_revision",Long.toString(c.accountRevision()));fields.put("provider_revision",Long.toString(c.providerRevision()));
            fields.put("denomination_revision",Long.toString(c.denominationRevision()));fields.put("tier_revision",Long.toString(expectedTierRevision));
            fields.put("previous_token",previousToken==null?"":previousToken.toString());
            fields.put("fee_id",fee==null?"":fee.id().toString());fields.put("funding",fee==null?"":fee.funding().value().toString());
            fields.put("funding_revision",fee==null?"":Long.toString(fee.fundingRevision()));fields.put("fee_denomination_revision",fee==null?"":Long.toString(fee.denominationRevision()));
            fields.put("fee_currency",fee==null?"":fee.currency().name());fields.put("fee_debit",fee==null?"":Long.toString(fee.debitMinor()));
            fields.put("fee_expires",fee==null?"":Long.toString(fee.expiresAt()));fields.put("fee_capture_hash",fee==null?"":captureHash(fee.capture()));
            return new Journal.CanonicalRequest(1,fields);
        }
    }
    private IssuedCards() {}
    private static String captureHash(String value) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }


    public static Registration registration(PartyRef owner,Request request,BankProviders.TierEligibility tiers,LongSupplier clock,Fee fee) {
        Objects.requireNonNull(tiers);Objects.requireNonNull(clock);
        return new Registration(1,(intent,reads)->prepare(owner,request,tiers,clock.getAsLong(),fee,intent,reads));
    }
    private static Decision prepare(PartyRef owner,Request request,BankProviders.TierEligibility tiers,long now,Fee fee,Journal.Intent intent,ReadView reads) throws Exception {
        var c=request.proof().context();
        if(!(owner.owner() instanceof PartyRef.Player player) || !owner.world().equals(c.world()) || !player.id().equals(c.actor())
                || !intent.actor().equals(player.id()) || !intent.kind().equals(ISSUE) || !request.request().equals(intent.request()))return reject("CARD_REQUEST");
        boolean replacement=c.action().name().equals("CARD_REPLACE");
        if(!replacement && !c.action().name().equals("CARD_ISSUE"))return reject("CARD_BRANCH_ACTION");
        if(!request.proof().valid(intent,reads))return decline("BRANCH_UNAVAILABLE");
        if(!reads.query("SELECT lease_id FROM bank_provider_leases WHERE lease_id=?",List.of(request.proof().id().toString()),1).isEmpty())return reject("LEASE_USED");
        var denomination=RebaseSettlement.denomination(reads).orElse(null);
        if(denomination==null || denomination.revision()!=c.denominationRevision())return reject("DENOMINATION_STALE");
        var account=c.account()==null?null:AccountCatalog.find(reads,owner,c.account()).orElse(null);
        var provider=account==null?null:BankProviders.find(reads,owner,account.id()).orElse(null);
        if(account==null || account.purpose()!=AccountIdentity.Purpose.BANK || account.status()!=AccountIdentity.Status.OPEN || account.revision()!=c.accountRevision())return reject("CARD_ACCOUNT_STALE");
        if(provider==null || provider.revision()!=c.providerRevision() || !provider.bankKey().equals(c.bank().key()))return reject("CARD_ISSUER");
        var bank=InstitutionCatalog.find(c.bank()).orElse(null);
        if(bank==null || !bank.customerBank() || !enabled(reads,owner.world(),bank.id()))return reject("BANK_UNAVAILABLE");
        var tier=tiers.current(reads,owner);
        if(bank.tier2() && tier.available() && tier.revision()!=request.expectedTierRevision())return reject("CARD_TIER_STALE");
        if(!bank.tier1() && (!tier.available() || !tier.eligible() || tier.revision()!=request.expectedTierRevision()))return reject("TIER2_UNAVAILABLE");
        int presentation=bank.tier2() && tier.available() && tier.eligible() && tier.revision()==request.expectedTierRevision()?2:1;
        var previous=currentEpoch(reads,owner,account.id(),provider.debitEpoch()).orElse(null);
        if(replacement) {
            if(previous==null || !previous.id().equals(request.previousToken()))return reject("CARD_TOKEN_STALE");
        } else if(previous!=null || request.previousToken()!=null)return reject("CARD_REPLACEMENT_REQUIRED");
        if(now<0 || previous!=null && now<previous.issuedAt())return reject("CARD_CLOCK_STALE");
        boolean paid=replacement && !freeRenewal(previous,now);
        Commit payment=null;
        if(paid) {
            var quote=request.fee();
            if(quote==null || fee==null)return reject("CARD_FEE_REQUIRED");
            if(now>=quote.expiresAt() || quote.denominationRevision()!=denomination.revision())return reject("CARD_FEE_STALE");
            var funding=AccountCatalog.find(reads,owner,quote.funding()).orElse(null);
            if(funding==null || funding.status()!=AccountIdentity.Status.OPEN || funding.purpose()!=AccountIdentity.Purpose.BANK
                    || funding.revision()!=quote.fundingRevision() || funding.currency()!=quote.currency())return reject("CARD_FUNDING_STALE");
            var decision=fee.prepare(intent,quote,reads);if(!(decision instanceof Commit committed))return decision;
            payment=committed;
        } else if(request.fee()!=null)return reject("CARD_FEE_UNEXPECTED");
        var token=new Token(owner.world(),intent.operationId().value(),player.id(),bank.id(),account.id(),Product.DEBIT,"",presentation,
                provider.debitEpoch(),Status.ACTIVE,now,Math.addExact(now,LIFETIME),1,previous==null?null:previous.id(),intent.operationId().value());
        var old=activeForAccount(reads,owner.world(),account.id());
        var delivery=Custody.committedDelivery(intent,token.id(),player.id(),deliveryItems(token),"issued-card:"+token.id(),Journal.actorKey(player.id()),reads);
        var facts=new ArrayList<Journal.Record>();if(payment!=null)facts.addAll(payment.records());
        old.ifPresent(value->facts.add(fact(status(value,Status.REVOKED))));facts.add(fact(token));facts.addAll(delivery.records());
        var financial=payment;
        return new Commit(facts,new Journal.Result(1,"CARD_ISSUED",token.id().toString()),tx->{
            if(financial!=null)financial.projections().apply(tx);
            if(old.isPresent())update(tx,old.orElseThrow(),Status.REVOKED);
            tx.execute("INSERT INTO issued_cards VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values(token));
            tx.execute("INSERT INTO bank_provider_leases VALUES(?,?,?)",List.of(request.proof().id().toString(),intent.operationId().value().toString(),intent.canonical()));
            delivery.projections().apply(tx);
        });
    }
    public static Status effectiveStatus(Token token,long now) {return token.status()==Status.ACTIVE && now>=token.expiresAt()?Status.EXPIRED:token.status();}
    public static boolean freeRenewal(Token token,long now) {return now>=Math.subtractExact(token.expiresAt(),RENEWAL_WINDOW);}
    public static String itemId(Token token) {return CardDesigns.id(token.issuer(),token.tier());}
    public static String deliveryItems(Token token) {return "issued-card-v1\n"+token.world().value()+"|"+token.id()+"|"+itemId(token);}
    public static Journal.CanonicalRequest revokeRequest(UUID token,long revision,PlayerId owner) {
        if(revision<1)throw new IllegalArgumentException("Token revision");
        return new Journal.CanonicalRequest(1,Map.of("economy:owner",Journal.actorKey(owner),"token",token.toString(),"revision",Long.toString(revision)));
    }
    public static Registration revocation(PartyRef owner) {
        if(!(owner.owner() instanceof PartyRef.Player player))throw new IllegalArgumentException("Card owner");
        return new Registration(1,(intent,reads)->{
            UUID id;long revision;
            try {id=UUID.fromString(intent.request().fields().get("token"));revision=Long.parseLong(intent.request().fields().get("revision"));
                if(!intent.kind().equals(REVOKE) || !intent.actor().equals(player.id()) || !revokeRequest(id,revision,player.id()).equals(intent.request()))return reject("CARD_REQUEST");
            } catch(RuntimeException malformed){return reject("CARD_REQUEST");}
            var token=find(reads,owner.world(),id).filter(value->value.owner().equals(player.id())).orElse(null);
            if(token==null)return reject("CARD_UNAVAILABLE");
            if(token.revision()!=revision)return reject("CARD_TOKEN_STALE");
            if(token.status()!=Status.ACTIVE)return reject("CARD_ALREADY_REVOKED");
            return new Commit(List.of(fact(status(token,Status.REVOKED))),new Journal.Result(1,"CARD_REVOKED",id.toString()),tx->update(tx,token,Status.REVOKED));
        });
    }
    public static Optional<Token> find(ReadView reads,WorldId world,UUID token) {
        return reads.query("SELECT * FROM issued_cards WHERE world_id=? AND token_id=?",List.of(world.value().toString(),token.toString()),1).stream().map(IssuedCards::read).findFirst();
    }
    public static Optional<Token> forAccount(ReadView reads,PartyRef owner,AccountId account) {
        if(!(owner.owner() instanceof PartyRef.Player player) || AccountCatalog.find(reads,owner,account).isEmpty())return Optional.empty();
        return reads.query("SELECT * FROM issued_cards WHERE world_id=? AND owner_id=? AND account_id=? ORDER BY rowid DESC LIMIT 1",
                List.of(owner.world().value().toString(),player.id().value().toString(),account.value().toString()),1).stream().map(IssuedCards::read).findFirst();
    }
    private static Optional<Token> currentEpoch(ReadView reads,PartyRef owner,AccountId account,long epoch) {
        return forAccount(reads,owner,account).filter(token->token.providerEpoch()==epoch);
    }
    private static Optional<Token> activeForAccount(ReadView reads,WorldId world,AccountId account) {
        return reads.query("SELECT * FROM issued_cards WHERE world_id=? AND account_id=? AND product='DEBIT' AND status='ACTIVE'",
                List.of(world.value().toString(),account.value().toString()),1).stream().map(IssuedCards::read).findFirst();
    }
    public static Optional<Token> usable(ReadView reads,WorldId world,PlayerId actor,UUID token,long now) {
        var owner=new PartyRef(world,new PartyRef.Player(actor));
        return find(reads,world,token).filter(value->value.owner().equals(actor) && value.status()==Status.ACTIVE && now>=value.issuedAt() && now<value.expiresAt())
                .filter(value->AccountCatalog.find(reads,owner,value.account()).filter(account->account.status()==AccountIdentity.Status.OPEN && account.purpose()==AccountIdentity.Purpose.BANK).isPresent())
                .filter(value->BankProviders.find(reads,owner,value.account()).filter(binding->binding.bankKey().equals(value.issuer().key())&&binding.debitEpoch()==value.providerEpoch()).isPresent());
    }
    /** Compose with provider switch/closure so old copies acquire an explicit revoked status in that same transaction. */
    public static Optional<Commit> invalidateAccount(ReadView reads,WorldId world,AccountId account) {
        return activeForAccount(reads,world,account).map(token->new Commit(List.of(fact(status(token,Status.REVOKED))),new Journal.Result(1,"CARD_REVOKED",token.id().toString()),tx->update(tx,token,Status.REVOKED)));
    }
    private static boolean enabled(ReadView reads,WorldId world,InstitutionCatalog.InstitutionId bank) {
        var rows=reads.query("SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",List.of(world.value().toString(),bank.key()),1);
        return rows.isEmpty() || rows.getFirst().get("enabled").equals("1");
    }
    private static Token status(Token token,Status status) {return new Token(token.world(),token.id(),token.owner(),token.issuer(),token.account(),token.product(),token.network(),token.tier(),token.providerEpoch(),status,token.issuedAt(),token.expiresAt(),Math.addExact(token.revision(),1),token.previousToken(),token.custodyId());}
    private static void update(ca.emilybelnavis.mcmods.economy.storage.FoundationStore.Transaction tx,Token old,Status status) {
        if(tx.execute("UPDATE issued_cards SET status=?,revision=revision+1 WHERE world_id=? AND token_id=? AND revision=?",List.of(status.name(),old.world().value().toString(),old.id().toString(),Long.toString(old.revision())))!=1)throw new IllegalStateException("Card status changed");
    }
    private static Journal.Record fact(Token token){return new Journal.Record(FACT,1,encode(token));}
    private static List<String> values(Token t) {return List.of(t.world().value().toString(),t.id().toString(),t.owner().value().toString(),t.issuer().key(),t.account().value().toString(),t.product().name(),t.network(),Integer.toString(t.tier()),Long.toString(t.providerEpoch()),t.status().name(),Long.toString(t.issuedAt()),Long.toString(t.expiresAt()),Long.toString(t.revision()),t.previousToken()==null?"":t.previousToken().toString(),t.custodyId().toString());}
    private static Token read(Map<String,String> r) {return new Token(new WorldId(UUID.fromString(r.get("world_id"))),UUID.fromString(r.get("token_id")),new PlayerId(UUID.fromString(r.get("owner_id"))),new InstitutionCatalog.InstitutionId(r.get("issuer")),new AccountId(UUID.fromString(r.get("account_id"))),Product.valueOf(r.get("product")),r.get("network"),Integer.parseInt(r.get("tier")),Long.parseLong(r.get("provider_epoch")),Status.valueOf(r.get("status")),Long.parseLong(r.get("issued_at")),Long.parseLong(r.get("expires_at")),Long.parseLong(r.get("revision")),r.get("previous_token").isEmpty()?null:UUID.fromString(r.get("previous_token")),UUID.fromString(r.get("custody_id")));}
    private static String encode(Token token){return String.join("|",values(token).subList(0,15));}
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(FACT,1),IssuedCards::validateFact);}
    public static void validateFact(String payload) {
        var p=payload.split("\\|",-1);if(p.length!=15)throw new IllegalArgumentException("Card fact fields");
        var names=List.of("world_id","token_id","owner_id","issuer","account_id","product","network","tier","provider_epoch","status","issued_at","expires_at","revision","previous_token","custody_id");
        var row=new HashMap<String,String>();for(int i=0;i<p.length;i++)row.put(names.get(i),p[i]);var token=read(row);
        if(!encode(token).equals(payload))throw new IllegalArgumentException("Noncanonical card fact");
    }
    private static Reject reject(String code){return new Reject(new Journal.Result(1,code,""));}
    private static Decline decline(String code){return new Decline(new Journal.Result(1,code,""));}
}
