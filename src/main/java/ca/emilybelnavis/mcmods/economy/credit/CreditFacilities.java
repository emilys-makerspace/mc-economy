package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.card.IssuedCards;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.interest.DepositYield;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

/** Sole-worker revolving contracts. Financial effects use the existing balanced institution ledger. */
public final class CreditFacilities {
    public static final long DAY=1_200_000L,MONTH=28*DAY;
    public static final int VERSION=1,MAX_REQUIREMENTS=16,MAX_SLICES=34;
    public static final String FACT="economy:a6/credit-facility",TOKEN_FACT="economy:a6/credit-token",STATEMENT_FACT="economy:a6/credit-statement",EVENT_FACT="economy:a6/credit-event",BORROWER_FACT="economy:a6/credit-borrower";
    public static final Journal.Kind ISSUE=new Journal.Kind("economy:credit","economy:issue"),DRAW=new Journal.Kind("economy:credit","economy:draw"),REPAY=new Journal.Kind("economy:credit","economy:repay"),REVOKE=new Journal.Kind("economy:credit","economy:revoke");
    public enum Status { ACTIVE, DEFAULTED, SETTLED }
    public enum Access { ENABLED, SUSPENDED, CLOSED }
    public enum DrawKind { PURCHASE, ADVANCE }
    public record Facility(WorldId world,UUID id,PlayerId owner,InstitutionCatalog.InstitutionId issuer,
            InstitutionCatalog.Network network,EconomyCurrency currency,long limitMinor,int tier,long anchor,long lastDay,
            long lastStatement,long revision,Status status,Access access,CardServicing.Bucket purchase,CardServicing.Bucket advance,
            List<CardServicing.Requirement> requirements,List<CardServicing.DebtSlice> slices) {
        public Facility {
            Objects.requireNonNull(world);Objects.requireNonNull(id);Objects.requireNonNull(owner);InstitutionCatalog.require(issuer);
            Objects.requireNonNull(network);Objects.requireNonNull(currency);Objects.requireNonNull(status);Objects.requireNonNull(access);
            Objects.requireNonNull(purchase);Objects.requireNonNull(advance);requirements=List.copyOf(requirements);slices=List.copyOf(slices);
            if(limitMinor<1||tier<1||tier>2||anchor<0||lastDay<anchor||lastStatement<anchor||lastStatement>lastDay||revision<1
                    ||(lastDay-anchor)%DAY!=0||(lastStatement-anchor)%MONTH!=0||requirements.size()>MAX_REQUIREMENTS||slices.size()>MAX_SLICES
                    ||purchase.kind()!=CardServicing.Kind.PURCHASE||advance.kind()!=CardServicing.Kind.ADVANCE)throw new IllegalArgumentException("Facility fields");
            long debt=Math.addExact(Math.addExact(purchase.principalMinor(),advance.principalMinor()),Math.addExact(purchase.interestMinor(),advance.interestMinor()));
            long required=0;var ids=new HashSet<String>();for(var r:requirements){if(!ids.add(r.id()))throw new IllegalArgumentException("Duplicate minimum");required=Math.addExact(required,r.unmetMinor());}
            if(required>debt)throw new IllegalArgumentException("Minimum exceeds debt");
            var sliceIds=new HashSet<String>();
            for(var kind:CardServicing.Kind.values()){
                long principal=0,interest=0;
                for(var slice:slices)if(slice.kind()==kind){
                    if(!sliceIds.add(slice.id()+"/"+kind) || slice.statementAt()>lastStatement || slice.statementAt()<=anchor || (slice.statementAt()-anchor)%MONTH!=0)throw new IllegalArgumentException("Statement attribution");
                    principal=Math.addExact(principal,slice.principalMinor());interest=Math.addExact(interest,slice.interestMinor());
                }
                var bucket=kind==CardServicing.Kind.PURCHASE?purchase:advance;
                if(principal>bucket.principalMinor() || interest>bucket.interestMinor())throw new IllegalArgumentException("Attributed debt exceeds bucket");
            }
        }
        public long owedMinor(){return Math.addExact(Math.addExact(purchase.principalMinor(),advance.principalMinor()),Math.addExact(purchase.interestMinor(),advance.interestMinor()));}
        public long principalMinor(){return Math.addExact(purchase.principalMinor(),advance.principalMinor());}
        public long requiredMinor(){long sum=0;for(var r:requirements)sum=Math.addExact(sum,r.unmetMinor());return sum;}
        public long nextDay(){return Math.addExact(lastDay,DAY);}
        public long nextStatement(){return Math.addExact(lastStatement,MONTH);}
        public PartyRef party(){return new PartyRef(world,new PartyRef.Player(owner));}
    }
    public record Token(WorldId world,UUID id,UUID facility,PlayerId owner,InstitutionCatalog.InstitutionId issuer,
                        InstitutionCatalog.Network network,int tier,IssuedCards.Status status,long issuedAt,long expiresAt,long revision,UUID custodyId) {
        public Token {
            Objects.requireNonNull(world);Objects.requireNonNull(id);Objects.requireNonNull(facility);Objects.requireNonNull(owner);InstitutionCatalog.require(issuer);
            Objects.requireNonNull(network);Objects.requireNonNull(status);Objects.requireNonNull(custodyId);
            if(tier<1||tier>2||issuedAt<0||expiresAt!=Math.addExact(issuedAt,IssuedCards.LIFETIME)||revision<1||!custodyId.equals(id))throw new IllegalArgumentException("Credit token fields");
        }
    }
    /** Trusted runtime captures complete liability/blocker and current immutable FX inputs. Never deserialize this from a packet. */
    public record Inputs(CreditUnderwriting.FxSnapshot fx,boolean complete,boolean creditBlocked,long otherExposureWorldMinor,boolean tier2Eligible) {
        public Inputs(CreditUnderwriting.FxSnapshot fx,boolean complete,boolean creditBlocked,long otherExposureWorldMinor){this(fx,complete,creditBlocked,otherExposureWorldMinor,false);}
        public Inputs {Objects.requireNonNull(fx);if(otherExposureWorldMinor<0)throw new IllegalArgumentException("Other exposure");}
    }
    @FunctionalInterface public interface InputSource {Inputs current(ReadView reads,PartyRef owner,long effective)throws Exception;}
    public record IssueRequest(BranchAdmission.Proof proof,UUID facility,InstitutionCatalog.Network network,EconomyCurrency currency,
                               long limitMinor,long profileRevision,long tierRevision,long calendarOrigin) {
        public IssueRequest {Objects.requireNonNull(proof);Objects.requireNonNull(facility);Objects.requireNonNull(network);Objects.requireNonNull(currency);if(limitMinor<1||profileRevision<0||tierRevision<0||calendarOrigin<0)throw new IllegalArgumentException("Credit issue inputs");}
        public Journal.CanonicalRequest request(){var c=proof.context();return canonical(c.world(),c.actor(),Map.ofEntries(
                Map.entry("lease",proof.id().toString()),Map.entry("session",c.session().toString()),Map.entry("issuer",c.bank().key()),
                Map.entry("facility",facility.toString()),Map.entry("network",network.name()),Map.entry("currency",currency.name()),Map.entry("limit",Long.toString(limitMinor)),
                Map.entry("profile_revision",Long.toString(profileRevision)),Map.entry("tier_revision",Long.toString(tierRevision)),Map.entry("denomination_revision",Long.toString(c.denominationRevision())),Map.entry("origin",Long.toString(calendarOrigin))));}
    }
    private CreditFacilities() {}
    public static Registration issuance(PartyRef owner,IssueRequest request,BankProviders.TierEligibility tiers,InputSource inputs,LongSupplier clock) {
        Objects.requireNonNull(tiers);Objects.requireNonNull(inputs);Objects.requireNonNull(clock);
        return new Registration(1,(intent,reads)->{
            var c=request.proof().context();
            if(!matches(owner,intent,ISSUE,request.request())||!CreditProfiles.owns(owner,c.world(),c.actor())||!c.action().name().equals("CREDIT_ISSUE"))return reject("CREDIT_REQUEST");
            if(!request.proof().valid(intent,reads))return decline("BRANCH_UNAVAILABLE");
            if(!reads.query("SELECT lease_id FROM bank_provider_leases WHERE lease_id=?",List.of(request.proof().id().toString()),1).isEmpty())return reject("LEASE_USED");
            var denom=RebaseSettlement.denomination(reads).orElse(null);
            if(denom==null||denom.revision()!=c.denominationRevision())return reject("DENOMINATION_STALE");
            if(!CreditProfiles.origin(reads).filter(v->v==request.calendarOrigin()).isPresent())return decline("CREDIT_CALENDAR_UNAVAILABLE");
            long now=clock.getAsLong();if(now<request.calendarOrigin())return decline("CREDIT_CLOCK_STALE");
            var bank=InstitutionCatalog.require(c.bank());
            if(!bank.networks().contains(request.network())||!enabled(reads,owner.world(),bank.id()))return reject("CREDIT_NETWORK_OR_ISSUER");
            var tier=tiers.current(reads,owner);
            if(bank.tier2()&&tier.available()&&tier.revision()!=request.tierRevision())return reject("CREDIT_TIER_STALE");
            if(!bank.tier1()&&(!tier.available()||!tier.eligible()))return reject("TIER2_UNAVAILABLE");
            int presentation=bank.tier2()&&tier.available()&&tier.eligible()?2:1;
            var profile=CreditProfiles.find(reads,owner).orElse(null);
            if((profile==null?0:profile.revision())!=request.profileRevision())return reject("CREDIT_PROFILE_STALE");
            if(find(reads,request.facility()).isPresent())return reject("CREDIT_FACILITY_EXISTS");
            var source=inputs.current(reads,owner,now);
            if(source==null||!source.complete()||!source.fx().available())return decline("CREDIT_EXPOSURE_UNAVAILABLE");
            if(reads.query("SELECT state FROM institution_capital_manifest WHERE world_id=? AND state='ACTIVE'",List.of(owner.world().value().toString()),1).isEmpty())return decline("CREDIT_CAPITAL_UNAVAILABLE");
            var facilities=reads.query("SELECT * FROM credit_facilities WHERE world_id=? AND owner_id=? AND status='ACTIVE' AND access<>'CLOSED' ORDER BY facility_id LIMIT 4",List.of(owner.world().value().toString(),c.actor().value().toString()),4).stream().map(CreditFacilities::readFacility).toList();
            int open=facilities.size();long committed=0;
            for(var f:facilities)committed=Math.addExact(committed,CreditUnderwriting.exposureToWorld(new Money(f.currency(),f.limitMinor()),denom.currency(),source.fx()).minorUnits());
            long exposure=Math.addExact(source.otherExposureWorldMinor(),exposure(reads,owner,denom.currency(),source.fx()));
            var portfolio=owned(reads,owner);if(portfolio.size()>128)return decline("CREDIT_EXPOSURE_UNAVAILABLE");
            boolean blocked=source.creditBlocked()||portfolio.stream().anyMatch(f->f.requirements().stream().anyMatch(r->r.unmetMinor()>0&&r.dueAt()<=now))
                ||!reads.query("SELECT facility_id FROM credit_facilities WHERE world_id=? AND owner_id=? AND status='DEFAULTED' AND debt_minor>0 LIMIT 1",List.of(owner.world().value().toString(),c.actor().value().toString()),1).isEmpty();
            if(profile!=null&&request.currency()!=bank.baseCurrency())return reject("CREDIT_CONTRACT_CURRENCY");
            if(profile==null&&request.currency()!=denom.currency())return reject("STARTER_WORLD_CURRENCY");
            var terms=new CreditUnderwriting.RevolvingRequest(profile!=null,profile!=null,profile==null?650:profile.score(),denom.currency(),presentation==2,tier.available()&&tier.eligible(),source.complete(),blocked,open,committed,exposure,request.limitMinor());
            var offer=CreditUnderwriting.revolvingForFacility(terms,new Money(request.currency(),request.limitMinor()),source.fx()).worldOffer();
            if(!offer.available())return decline(offer.reason());
            if(!offer.approved())return acceptedDenial(owner,request,intent,reads,now,offer.reason());
            if(DepositYield.distressed(reads,bank.id()))return acceptedDenial(owner,request,intent,reads,now,"ISSUER_YIELD_DISTRESSED");
            var reserveDecision=CreditTreasury.reserve(reads,c.world(),c.bank(),request.currency(),request.limitMinor(),now);
            if(!(reserveDecision instanceof Commit reserve))return acceptedDenial(owner,request,intent,reads,now,reserveDecision instanceof Reject r?r.result().code():"CREDIT_FUNDING_UNAVAILABLE");
            final int issuedTier=presentation;
            if(profile==null){var initial=new CreditProfiles.InitialRequest(owner.world(),c.actor(),c.session(),bank.id(),request.facility(),request.currency(),c.denominationRevision(),request.calendarOrigin());
                var adapted=adapt(intent,CreditProfiles.FIRST_ISSUANCE,initial.request());
                return CreditProfiles.firstIssuance(owner,initial,()->now,(i,r,starter)->issueCommit(intent,reads,request,issuedTier,now,reserve)).handler().prepare(adapted,reads);
            }
            var inquiry=inquiry(owner,request,now);return CreditProfiles.acceptedInquiry(owner,inquiry,()->now,(i,r)->issueCommit(intent,reads,request,issuedTier,now,reserve)).handler().prepare(adapt(intent,CreditProfiles.INQUIRY,inquiry.request()),reads);
        });
    }
    private static Decision acceptedDenial(PartyRef owner,IssueRequest request,Journal.Intent intent,ReadView reads,long now,String reason)throws Exception {
        var inquiry=inquiry(owner,request,now);var event=event("APPLICATION_DENIED",request.facility(),now,reason);
        return CreditProfiles.acceptedInquiry(owner,inquiry,()->now,(i,r)->new Commit(List.of(event),new Journal.Result(1,"CREDIT_APPLICATION_DENIED",reason),tx->{})).handler().prepare(adapt(intent,CreditProfiles.INQUIRY,inquiry.request()),reads);
    }
    private static CreditProfiles.InquiryRequest inquiry(PartyRef owner,IssueRequest request,long now){return new CreditProfiles.InquiryRequest(owner.world(),((PartyRef.Player)owner.owner()).id(),request.proof().context().bank(),request.facility(),CreditProfiles.ApplicationKind.CARD,now,request.calendarOrigin(),false);}
    private static Decision issueCommit(Journal.Intent original,ReadView reads,IssueRequest request,int tier,long now,Commit funded)throws Exception {
        var c=request.proof().context();
        var purchase=new CardServicing.Bucket(CardServicing.Kind.PURCHASE,0,0,zero(),zero(),apr(request.network(),false));
        var advance=new CardServicing.Bucket(CardServicing.Kind.ADVANCE,0,0,zero(),zero(),apr(request.network(),true));
        var facility=new Facility(c.world(),request.facility(),c.actor(),c.bank(),request.network(),request.currency(),request.limitMinor(),tier,now,now,now,1,Status.ACTIVE,Access.ENABLED,purchase,advance,List.of(),List.of());
        var token=new Token(c.world(),original.operationId().value(),facility.id(),c.actor(),c.bank(),request.network(),tier,IssuedCards.Status.ACTIVE,now,Math.addExact(now,IssuedCards.LIFETIME),1,original.operationId().value());
        var delivery=Custody.committedDelivery(original,token.id(),c.actor(),deliveryItems(token),"issued-card:"+token.id(),Journal.actorKey(c.actor()),reads);
        var facts=new ArrayList<>(funded.records());facts.add(fact(facility));facts.add(tokenFact(token));facts.addAll(delivery.records());
        return new Commit(facts,new Journal.Result(1,"CREDIT_CARD_ISSUED",facility.id().toString()),tx->{funded.projections().apply(tx);write(tx,facility);writeToken(tx,token);delivery.projections().apply(tx);tx.execute("INSERT INTO bank_provider_leases VALUES(?,?,?)",List.of(request.proof().id().toString(),original.operationId().value().toString(),original.canonical()));});
    }
    public static BigDecimal apr(InstitutionCatalog.Network network,boolean advance){return new BigDecimal(network==InstitutionCatalog.Network.KEYSTONE?(advance?"0.2399":"0.2199"):(advance?"0.2199":"0.1999"));}
    public static String itemId(Token token){return ca.emilybelnavis.mcmods.economy.card.CreditCardDesigns.id(token.issuer(),token.network().key(),token.tier());}
    public static String deliveryItems(Token token){return "issued-card-v1\n"+token.world().value()+"|"+token.id()+"|"+itemId(token);}
    /** Server-owned physical token + merchant/ATM/teller admission, checked again during preparation. */
    @FunctionalInterface public interface LiveDraw {boolean valid(Journal.Intent intent,ReadView reads,Token token,DrawKind kind,AccountId destination);}
    /** Frozen physical terminal and fee amounts included in the reviewed canonical request. */
    public record AdvanceFees(TerminalPolicy.Anchor terminal, UUID generation, long revision,
                              InstitutionCatalog.InstitutionId bank, long advanceMinor, long atmMinor, UUID fxPublication) {
        public AdvanceFees {
            Objects.requireNonNull(terminal); Objects.requireNonNull(generation); Objects.requireNonNull(fxPublication);
            if (revision < 1 || advanceMinor < 0 || atmMinor < 0) throw new IllegalArgumentException("Advance fees");
        }
        public static AdvanceFees capture(ReadView reads, Facility facility, TerminalPolicy.Snapshot terminal) {
            var fx = ca.emilybelnavis.mcmods.economy.fx.FxService.snapshot(reads);
            if (fx == null) throw new IllegalArgumentException("FEE_FX_UNAVAILABLE");
            var rates = new CurrencyConversion.Rates(fx.reference(), fx.rates());
            if (terminal.kind() != TerminalPolicy.Kind.ATM && terminal.kind() != TerminalPolicy.Kind.TELLER && terminal.kind() != TerminalPolicy.Kind.KIOSK)
                throw new IllegalArgumentException("CREDIT_PHYSICAL_ADMISSION");
            if (terminal.binding() == null || !terminal.binding().bound()) throw new IllegalArgumentException("UNBOUND_BANK");
            var bank = terminal.binding().institution();
            long atm = terminal.kind() == TerminalPolicy.Kind.ATM
                    ? ca.emilybelnavis.mcmods.economy.pricing.BankingFees.atmFee(facility.currency(), facility.issuer().key(), bank == null ? null : bank.key(), rates).minorUnits() : 0;
            return new AdvanceFees(terminal.anchor(), terminal.generation(), terminal.revision(), bank,
                    ca.emilybelnavis.mcmods.economy.pricing.BankingFees.advanceFee(facility.currency(), rates).minorUnits(), atm, fx.id());
        }
        String encoded() {
            return terminal.dimension()+"|"+terminal.x()+"|"+terminal.y()+"|"+terminal.z()+"|"+generation+"|"+revision+"|"+(bank==null?"":bank.key())+"|"+advanceMinor+"|"+atmMinor+"|CAD_FEE_V2|"+fxPublication;
        }
    }
    public record DrawRequest(WorldId world,PlayerId owner,UUID facility,UUID token,InstitutionCatalog.Network network,DrawKind kind,
                              AccountId destination,long amountMinor,long facilityRevision,long tokenRevision,long destinationRevision,
                              long profileRevision,UUID quote,long expiresAt,AdvanceFees fees) {
        public DrawRequest(WorldId world,PlayerId owner,UUID facility,UUID token,InstitutionCatalog.Network network,DrawKind kind,
                           AccountId destination,long amountMinor,long facilityRevision,long tokenRevision,long destinationRevision,
                           long profileRevision,UUID quote,long expiresAt) {
            this(world,owner,facility,token,network,kind,destination,amountMinor,facilityRevision,tokenRevision,destinationRevision,profileRevision,quote,expiresAt,null);
        }
        public DrawRequest {Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(facility);Objects.requireNonNull(token);Objects.requireNonNull(network);Objects.requireNonNull(kind);Objects.requireNonNull(destination);Objects.requireNonNull(quote);if(amountMinor<1||facilityRevision<1||tokenRevision<1||destinationRevision<1||profileRevision<1||expiresAt<1 || kind==DrawKind.PURCHASE && fees!=null)throw new IllegalArgumentException("Draw inputs");}
        public Journal.CanonicalRequest request(){
            var fields = new TreeMap<>(Map.ofEntries(Map.entry("facility",facility.toString()),Map.entry("token",token.toString()),Map.entry("network",network.name()),Map.entry("kind",kind.name()),Map.entry("destination",destination.value().toString()),Map.entry("amount",Long.toString(amountMinor)),Map.entry("facility_revision",Long.toString(facilityRevision)),Map.entry("token_revision",Long.toString(tokenRevision)),Map.entry("destination_revision",Long.toString(destinationRevision)),Map.entry("profile_revision",Long.toString(profileRevision)),Map.entry("quote",quote.toString()),Map.entry("expires",Long.toString(expiresAt))));
            if(fees!=null)fields.put("advance_fees_v2",fees.encoded());
            return canonical(world,owner,fields);
        }
    }
    public static Registration draw(PartyRef owner,DrawRequest request,InputSource inputs,LiveDraw live,LongSupplier clock) {
        Objects.requireNonNull(inputs);Objects.requireNonNull(live);Objects.requireNonNull(clock);
        return new Registration(1,(intent,reads)->{
            if(!matches(owner,intent,DRAW,request.request())||!CreditProfiles.owns(owner,request.world(),request.owner()))return reject("CREDIT_DRAW_REQUEST");
            long now=clock.getAsLong();if(now>=request.expiresAt())return reject("CREDIT_QUOTE_STALE");
            var f=find(reads,request.facility()).filter(v->v.party().equals(owner)).orElse(null);
            var t=usableToken(reads,request.world(),request.owner(),request.token(),now).orElse(null);
            if(f==null||t==null||!t.facility().equals(f.id())||f.network()!=request.network()||t.network()!=request.network()||t.revision()!=request.tokenRevision()||f.revision()!=request.facilityRevision())return reject("CREDIT_CARD_STALE");
            if(f.status()!=Status.ACTIVE||f.access()!=Access.ENABLED)return reject("CREDIT_DRAW_CLOSED");
            if(!live.valid(intent,reads,t,request.kind(),request.destination()))return decline("CREDIT_PHYSICAL_ADMISSION");
            if(now>=f.nextDay()||now>=f.nextStatement())return decline("CREDIT_CATCHUP_REQUIRED");
            var profile=CreditProfiles.find(reads,owner).orElse(null);if(profile==null||profile.revision()!=request.profileRevision())return reject("CREDIT_PROFILE_STALE");
            var source=inputs.current(reads,owner,now);if(!source.complete())return decline("CREDIT_EXPOSURE_UNAVAILABLE");
            var denom=RebaseSettlement.denomination(reads).orElse(null);if(denom==null)return decline("DENOMINATION_UNAVAILABLE");
            if(!InstitutionCatalog.require(f.issuer()).tier1()&&!source.tier2Eligible())return reject("TIER2_UNAVAILABLE");
            var total=exposure(reads,owner,denom.currency(),source.fx());
            var portfolio=owned(reads,owner);if(portfolio.size()>128)return decline("CREDIT_EXPOSURE_UNAVAILABLE");
            boolean blocked=source.creditBlocked()||portfolio.stream().anyMatch(v->v.status()==Status.DEFAULTED&&ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.blocks(reads,ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind.CARD,v.id(),now)||v.requirements().stream().anyMatch(r->r.unmetMinor()>0&&r.dueAt()<=now));
            long advanceFee = request.kind()==DrawKind.ADVANCE ? ca.emilybelnavis.mcmods.economy.pricing.BankingFees.convert(new BigDecimal("3.00"),f.currency(),source.fx().rates().get(EconomyCurrency.CAD),source.fx().rates().get(f.currency())).minorUnits() : 0;
            long atmFee = 0;
            if(request.fees()!=null) {
                var captured = request.fees();
                var terminal = TerminalPolicy.find(reads,request.world(),captured.terminal(),request.owner(),true).orElse(null);
                if(terminal==null || !terminal.active() || !AdvanceFees.capture(reads,f,terminal).equals(captured))return reject("CREDIT_FEE_STALE");
                if(captured.advanceMinor()!=advanceFee)return reject("CREDIT_FEE_STALE");
                atmFee = captured.atmMinor();
            }
            long drawTotal = Math.addExact(request.amountMinor(),Math.addExact(advanceFee,atmFee));
            if(!CreditUnderwriting.drawAllowed(profile.score(),InstitutionCatalog.require(f.issuer()).tier2()&&source.tier2Eligible(),blocked,f.limitMinor(),f.owedMinor(),Math.addExact(total,source.otherExposureWorldMinor()),drawTotal,denom.currency()))return reject("CREDIT_DRAW_LIMIT");
            if(request.kind()==DrawKind.ADVANCE&&Math.addExact(f.advance().principalMinor(),drawTotal)>f.limitMinor()/5)return reject("CREDIT_ADVANCE_SUBLIMIT");
            var destination=account(reads,request.destination());
            if(destination==null||destination.currency()!=f.currency()||destination.revision()!=request.destinationRevision())return reject("CREDIT_DESTINATION_STALE");
            if(request.kind()==DrawKind.ADVANCE&&!AccountCatalog.find(reads,owner,request.destination()).isPresent())return reject("CREDIT_ADVANCE_OWNER");
            var bucket=request.kind()==DrawKind.PURCHASE?f.purchase():f.advance();
            var changed=new CardServicing.Bucket(bucket.kind(),Math.addExact(bucket.principalMinor(),drawTotal),bucket.interestMinor(),bucket.residualMajor(),bucket.periodEarnedMajor(),bucket.apr());
            var next=change(f,request.kind()==DrawKind.PURCHASE?changed:f.purchase(),request.kind()==DrawKind.ADVANCE?changed:f.advance(),f.requirements(),f.slices(),f.lastDay(),f.lastStatement());
            var reserve=financial(f,FinancialAccounts.Role.RESERVATION);var receivable=financial(f,FinancialAccounts.Role.RECEIVABLE);var liability=borrower(f,FinancialAccounts.Role.LIABILITY);
            var accounts = new ArrayList<>(List.of(reserve,receivable,liability));
            var legs = new ArrayList<>(List.of(leg(reserve,-drawTotal),customer(request.destination(),f.currency(),request.amountMinor()),leg(receivable,drawTotal),leg(liability,-drawTotal)));
            // Fees are financed by the reserved line, earned by their supplying bank, and accrue at the advance APR.
            if(advanceFee>0)addDrawFee(f,f.issuer(),advanceFee,accounts,legs);
            if(atmFee>0)addDrawFee(f,request.fees().bank(),atmFee,accounts,legs);
            var posted=InstitutionSettlement.prepare(reads,legs,accounts);if(!(posted instanceof Commit money))return posted;
            if(request.kind()==DrawKind.ADVANCE)money=audited(money,event("ADVANCE_FEES",f.id(),now,f.currency()+"|"+advanceFee+"|"+atmFee+"|"+(request.fees()==null||request.fees().bank()==null?"":request.fees().bank().key())));
            return withState(withAccounts(reads,money,accounts),next,event("DRAW_"+request.kind(),f.id(),now,request.amountMinor()+"|"+request.token()+"|"+request.destination().value()),"CREDIT_DRAWN",tx->{});

        });
    }
    private static void addDrawFee(Facility facility, InstitutionCatalog.InstitutionId bank, long amount,
                                   List<FinancialAccounts.Account> accounts, List<PostingBatch.Leg> legs) {
        var cash=FinancialAccounts.account(facility.world(),bank,FinancialAccounts.Role.CASH,facility.currency());
        var income=FinancialAccounts.account(facility.world(),bank,FinancialAccounts.Role.INCOME,facility.currency());
        var expense=borrower(facility,FinancialAccounts.Role.EXPENSE);
        accounts.addAll(List.of(cash,income,expense));
        legs.addAll(List.of(leg(cash,amount),leg(income,-amount),leg(expense,amount)));
    }
    public record RepaymentRequest(WorldId world,PlayerId owner,UUID facility,AccountId funding,long amountMinor,
                                   long facilityRevision,long fundingRevision,long fundingLedgerRevision,UUID quote,long expiresAt) {
        public RepaymentRequest {Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(facility);Objects.requireNonNull(funding);Objects.requireNonNull(quote);if(amountMinor<1||facilityRevision<1||fundingRevision<1||fundingLedgerRevision<0||expiresAt<1)throw new IllegalArgumentException("Repayment inputs");}
        public Journal.CanonicalRequest request(){return canonical(world,owner,Map.of("facility",facility.toString(),"funding",funding.value().toString(),"amount",Long.toString(amountMinor),"facility_revision",Long.toString(facilityRevision),"funding_revision",Long.toString(fundingRevision),"funding_ledger_revision",Long.toString(fundingLedgerRevision),"quote",quote.toString(),"expires",Long.toString(expiresAt)));}
    }
    public static Registration repayment(PartyRef owner,RepaymentRequest request,LongSupplier clock) {
        return new Registration(1,(intent,reads)->{
            if(!matches(owner,intent,REPAY,request.request())||!CreditProfiles.owns(owner,request.world(),request.owner()))return reject("CREDIT_REPAY_REQUEST");
            long now=clock.getAsLong();if(now>=request.expiresAt())return reject("CREDIT_PAYOFF_STALE");
            var f=find(reads,request.facility()).filter(v->v.party().equals(owner)).orElse(null);if(f==null||f.revision()!=request.facilityRevision())return reject("CREDIT_FACILITY_STALE");
            if(f.status()==Status.DEFAULTED)return decline("CREDIT_COLLECTION_PAYMENT_REQUIRED");
            if(now>=f.nextDay()||now>=f.nextStatement())return decline("CREDIT_CATCHUP_REQUIRED");
            var funding=AccountCatalog.find(reads,owner,request.funding()).orElse(null);
            if(funding==null||funding.status()!=AccountIdentity.Status.OPEN||funding.purpose()!=AccountIdentity.Purpose.BANK||funding.revision()!=request.fundingRevision()||WealthValuation.ledgerRevision(reads,funding.id().value())!=request.fundingLedgerRevision())return reject("CREDIT_FUNDING_STALE");
            if(funding.currency()!=f.currency())return decline("CREDIT_EXPLICIT_FX_REQUIRED");
            return repay(reads,f,request.amountMinor(),now,List.of(customer(funding.id(),f.currency(),-request.amountMinor())),List.of());
        });
    }
    /** Trusted FX/autopay adapters provide balanced funding debit/clearing legs; cannot be invoked by a client DTO. */
    public static Decision repay(ReadView reads,Facility f,long amount,long effective,List<PostingBatch.Leg> fundingLegs,List<FinancialAccounts.Account> planned)throws Exception {
        if(!find(reads,f.id()).filter(f::equals).isPresent())return reject("CREDIT_FACILITY_STALE");
        if(f.status()==Status.DEFAULTED)return decline("CREDIT_COLLECTION_PAYMENT_REQUIRED");
        if(amount<=0||amount>f.owedMinor())return reject("CREDIT_FRESH_PAYOFF_REQUIRED");
        if(effective<f.lastDay()||effective>=f.nextDay()||effective>=f.nextStatement())return decline("CREDIT_CATCHUP_REQUIRED");
        var payment=CardServicing.repay(f.currency(),List.of(f.purchase(),f.advance()),f.requirements(),f.slices(),amount,effective);
        var purchases=payment.buckets().stream().filter(b->b.kind()==CardServicing.Kind.PURCHASE).findFirst().orElseThrow();
        var advances=payment.buckets().stream().filter(b->b.kind()==CardServicing.Kind.ADVANCE).findFirst().orElseThrow();
        long principalPaid=Math.subtractExact(f.principalMinor(),Math.addExact(purchases.principalMinor(),advances.principalMinor()));
        long interestPaid=Math.subtractExact(amount,principalPaid);
        var next=change(f,purchases,advances,payment.requirements().stream().filter(r->r.unmetMinor()>0).toList(),payment.slices().stream().filter(s->s.principalMinor()>0||s.interestMinor()>0).toList(),f.lastDay(),f.lastStatement());
        var cash=financial(f,FinancialAccounts.Role.CASH);var reserve=financial(f,FinancialAccounts.Role.RESERVATION);var receivable=financial(f,FinancialAccounts.Role.RECEIVABLE);var liability=borrower(f,FinancialAccounts.Role.LIABILITY);
        var legs=new ArrayList<>(fundingLegs);if(principalPaid>0)legs.add(leg(f.access()==Access.CLOSED?cash:reserve,principalPaid));if(interestPaid>0)legs.add(leg(cash,interestPaid));legs.add(leg(receivable,-amount));legs.add(leg(liability,amount));
        var accounts=new ArrayList<>(planned);accounts.addAll(List.of(cash,reserve,receivable,liability));
        var decision=InstitutionSettlement.prepare(reads,legs,accounts);if(!(decision instanceof Commit money))return decision;
        return withState(audited(withAccounts(reads,money,accounts),CreditAuditFacts.payment(f,effective,amount,principalPaid,interestPaid,payment)),next,event("REPAYMENT",f.id(),effective,amount+"|"+principalPaid+"|"+interestPaid+"|"+payment.waivedResidualMajor().toPlainString()),"CREDIT_REPAID",tx->{for(var a:accounts)FinancialAccounts.register(tx,a);});
    }
    public static Decision daily(ReadView reads,UUID facility,long effective)throws Exception {
        var f=find(reads,facility).orElse(null);if(f==null||f.status()!=Status.ACTIVE||effective!=f.nextDay())return reject("CREDIT_DAY_STALE");
        if(effective>f.nextStatement())return decline("CREDIT_STATEMENT_REQUIRED");
        var p=CardServicing.day(f.currency(),f.purchase());var a=CardServicing.day(f.currency(),f.advance());
        long posted=Math.addExact(p.postedMinor(),a.postedMinor());
        var next=change(f,p.bucket(),a.bucket(),f.requirements(),f.slices(),effective,f.lastStatement());
        var event=event("DAILY_INTEREST",f.id(),effective,p.earnedMajor().toPlainString()+"|"+a.earnedMajor().toPlainString()+"|"+p.postedMinor()+"|"+a.postedMinor());
        if(posted==0)return new Commit(List.of(fact(next),event,CreditAuditFacts.daily(f,effective,p,a)),new Journal.Result(1,"CREDIT_INTEREST_POSTED",f.id().toString()),tx->write(tx,next));
        var receivable=financial(f,FinancialAccounts.Role.RECEIVABLE);var income=financial(f,FinancialAccounts.Role.INCOME);var expense=borrower(f,FinancialAccounts.Role.EXPENSE);var liability=borrower(f,FinancialAccounts.Role.LIABILITY);
        var accounts=List.of(receivable,income,expense,liability);
        var money=InstitutionSettlement.prepare(reads,List.of(leg(receivable,posted),leg(income,-posted),leg(expense,posted),leg(liability,-posted)),accounts);
        if(!(money instanceof Commit committed))return money;
        return withState(audited(withAccounts(reads,committed,accounts),CreditAuditFacts.daily(f,effective,p,a)),next,event,"CREDIT_INTEREST_POSTED",tx->{for(var account:accounts)FinancialAccounts.register(tx,account);});
    }
    public static Decision statement(ReadView reads,UUID facility,long effective)throws Exception {
        var f=find(reads,facility).orElse(null);if(f==null||f.status()!=Status.ACTIVE||effective!=f.nextStatement())return reject("CREDIT_STATEMENT_STALE");
        if(f.lastDay()!=effective)return decline("CREDIT_PRIOR_INTEREST_REQUIRED");
        String id=UUID.nameUUIDFromBytes(("economy:credit-statement:v1|"+f.world().value()+"|"+f.id()+"|"+effective).getBytes(StandardCharsets.UTF_8)).toString();
        var statement=CardServicing.statement(f.currency(),id,effective,List.of(f.purchase(),f.advance()),f.requirements());
        var requirements=new ArrayList<>(f.requirements().stream().filter(r->r.unmetMinor()>0).toList());
        if(statement.newRequirementMinor()>0)requirements.add(new CardServicing.Requirement(id,effective,statement.dueAt(),statement.newRequirementMinor(),statement.newRequirementMinor()));
        var slices=new ArrayList<>(f.slices().stream().filter(s->s.principalMinor()>0||s.interestMinor()>0).toList());
        for(var bucket:List.of(f.purchase(),f.advance())) {
            long principal=0,interest=0;for(var s:slices)if(s.kind()==bucket.kind()){principal=Math.addExact(principal,s.principalMinor());interest=Math.addExact(interest,s.interestMinor());}
            long newPrincipal=Math.subtractExact(bucket.principalMinor(),principal),newInterest=Math.subtractExact(bucket.interestMinor(),interest);
            if(newPrincipal>0||newInterest>0)slices.add(new CardServicing.DebtSlice(id,effective,statement.dueAt(),bucket.kind(),newPrincipal,newInterest));
        }
        if(requirements.size()>MAX_REQUIREMENTS||slices.size()>MAX_SLICES)return decline("CREDIT_OBLIGATION_BOUND");
        var next=change(f,CardServicing.nextPeriod(f.purchase()),CardServicing.nextPeriod(f.advance()),requirements,slices,f.lastDay(),effective);
        String payload=encodeStatement(f,statement);
        return new Commit(List.of(fact(next),new Journal.Record(STATEMENT_FACT,1,payload)),new Journal.Result(1,"CREDIT_STATEMENT_CREATED",id),tx->{write(tx,next);tx.execute("INSERT INTO credit_statements VALUES(?,?,?,?,?,?,?)",List.of(f.world().value().toString(),f.id().toString(),id,f.owner().value().toString(),Long.toString(effective),Long.toString(statement.dueAt()),payload));});
    }
    public record StatementSnapshot(WorldId world,UUID facility,PlayerId owner,CardServicing.Statement statement) {}
    public record StatementPage(List<StatementSnapshot> statements,Long nextClosedAt){public StatementPage{statements=List.copyOf(statements);}}
    public static StatementPage statements(ReadView reads,PartyRef owner,UUID facility,long afterClosedAt,int limit){
        if(afterClosedAt< -1||limit<1||limit>16)throw new IllegalArgumentException("Statement cursor");
        var f=find(reads,facility).filter(v->v.party().equals(owner)).orElse(null);if(f==null)return new StatementPage(List.of(),null);
        var rows=reads.query("SELECT body,closed_at,due_at,statement_id FROM credit_statements WHERE world_id=? AND facility_id=? AND owner_id=? AND closed_at>? ORDER BY closed_at LIMIT ?",List.of(owner.world().value().toString(),facility.toString(),f.owner().value().toString(),Long.toString(afterClosedAt),Integer.toString(limit+1)),limit+1);
        var result=rows.stream().limit(limit).map(r->{var snapshot=decodeStatement(r.get("body"));
            if(!snapshot.world().equals(f.world())||!snapshot.facility().equals(f.id())||!snapshot.owner().equals(f.owner())
                ||snapshot.statement().closedAt()!=Long.parseLong(r.get("closed_at"))||snapshot.statement().dueAt()!=Long.parseLong(r.get("due_at"))||!snapshot.statement().id().equals(r.get("statement_id")))throw new IllegalArgumentException("Statement projection binding");
            return snapshot;}).toList();return new StatementPage(result,rows.size()>limit?result.getLast().statement().closedAt():null);
    }
    private static StatementSnapshot decodeStatement(String payload){validateStatementFact(payload);var p=unpack(payload,11);return new StatementSnapshot(new WorldId(UUID.fromString(p.get(0))),UUID.fromString(p.get(1)),new PlayerId(UUID.fromString(p.get(2))),new CardServicing.Statement(p.get(3),number(p.get(4)),number(p.get(5)),number(p.get(6)),number(p.get(7)),new BigDecimal(p.get(8)),number(p.get(9)),number(p.get(10))));}
    public static Optional<Facility> find(ReadView reads,UUID facility){return reads.query("SELECT * FROM credit_facilities WHERE facility_id=?",List.of(facility.toString()),1).stream().map(CreditFacilities::readFacility).findFirst();}
    /** The live open set is bounded by the three-facility product cap; settled history has separate keyset reads. */
    public static List<Facility> owned(ReadView reads,PartyRef owner) {
        if(!(owner.owner() instanceof PartyRef.Player p))return List.of();
        return reads.query("SELECT * FROM credit_facilities WHERE world_id=? AND owner_id=? AND (access<>'CLOSED' OR debt_minor>0) ORDER BY facility_id LIMIT 129",List.of(owner.world().value().toString(),p.id().value().toString()),129).stream().map(CreditFacilities::readFacility).toList();
    }
    public static List<Facility> history(ReadView reads,PartyRef owner,UUID after,int limit) {
        if(limit<1||limit>16)throw new IllegalArgumentException("Facility page");
        if(!(owner.owner() instanceof PartyRef.Player p))return List.of();
        return reads.query("SELECT * FROM credit_facilities WHERE world_id=? AND owner_id=? AND facility_id>? ORDER BY facility_id LIMIT ?",List.of(owner.world().value().toString(),p.id().value().toString(),after==null?"":after.toString(),Integer.toString(limit)),limit).stream().map(CreditFacilities::readFacility).toList();
    }
    public static Optional<Facility> nextDue(ReadView reads,boolean daily,long effective) {
        String field=daily?"next_day":"next_statement";
        return reads.query("SELECT * FROM credit_facilities WHERE status='ACTIVE' AND "+field+"<=? ORDER BY "+field+",facility_id LIMIT 1",List.of(Long.toString(effective)),1).stream().map(CreditFacilities::readFacility).findFirst();
    }
    public static Optional<Token> token(ReadView reads,WorldId world,UUID token){return reads.query("SELECT body FROM credit_facility_tokens WHERE world_id=? AND token_id=?",List.of(world.value().toString(),token.toString()),1).stream().map(r->decodeToken(r.get("body"))).findFirst();}
    public static Optional<Token> tokenForFacility(ReadView reads,WorldId world,UUID facility){return reads.query("SELECT body FROM credit_facility_tokens WHERE world_id=? AND facility_id=? AND status='ACTIVE'",List.of(world.value().toString(),facility.toString()),1).stream().map(r->decodeToken(r.get("body"))).findFirst();}
    public static Optional<Token> usableToken(ReadView reads,WorldId world,PlayerId owner,UUID token,long now) {
        return token(reads,world,token).filter(t->t.owner().equals(owner)&&t.status()==IssuedCards.Status.ACTIVE&&now>=t.issuedAt()&&now<t.expiresAt())
                .filter(t->find(reads,t.facility()).filter(f->f.world().equals(world)&&f.owner().equals(owner)&&f.status()==Status.ACTIVE&&f.access()==Access.ENABLED&&f.issuer().equals(t.issuer())&&f.network()==t.network()).isPresent());
    }
    public static Journal.CanonicalRequest revokeRequest(WorldId world,PlayerId owner,UUID token,long revision){return canonical(world,owner,Map.of("token",token.toString(),"revision",Long.toString(revision)));}
    public static Registration revocation(PartyRef owner,UUID token,long revision) {
        return new Registration(1,(intent,reads)->{
            if(!(owner.owner() instanceof PartyRef.Player p)||!matches(owner,intent,REVOKE,revokeRequest(owner.world(),p.id(),token,revision)))return reject("CREDIT_TOKEN_REQUEST");
            var old=token(reads,owner.world(),token).filter(t->t.owner().equals(p.id())).orElse(null);
            if(old==null||old.revision()!=revision)return reject("CREDIT_TOKEN_STALE");
            if(old.status()!=IssuedCards.Status.ACTIVE)return reject("CREDIT_ALREADY_REVOKED");
            var next=new Token(old.world(),old.id(),old.facility(),old.owner(),old.issuer(),old.network(),old.tier(),IssuedCards.Status.REVOKED,old.issuedAt(),old.expiresAt(),Math.addExact(old.revision(),1),old.custodyId());
            return new Commit(List.of(tokenFact(next)),new Journal.Result(1,"CREDIT_TOKEN_REVOKED",token.toString()),tx->writeToken(tx,next));
        });
    }
    /** Aggregated currency groups avoid scanning old settled facility history. */
    public static long exposure(ReadView reads,PartyRef owner,EconomyCurrency worldCurrency,CreditUnderwriting.FxSnapshot fx) {
        if(!(owner.owner() instanceof PartyRef.Player p))return 0;
        long total=0;for(var row:reads.query("SELECT currency,SUM(CASE WHEN status='ACTIVE' AND access<>'CLOSED' THEN MAX(limit_minor,debt_minor) ELSE debt_minor END) AS exposure FROM credit_facilities WHERE world_id=? AND owner_id=? GROUP BY currency",List.of(owner.world().value().toString(),p.id().value().toString()),EconomyCurrency.values().length)) {
            long amount=Long.parseLong(row.get("exposure"));if(amount<0)throw new ArithmeticException("Exposure overflow");
            total=Math.addExact(total,CreditUnderwriting.exposureToWorld(new Money(EconomyCurrency.valueOf(row.get("currency")),amount),worldCurrency,fx).minorUnits());
        }
        return total;
    }
    public static final Journal.Kind INCREASE=new Journal.Kind("economy:credit","economy:increase");
    public record IncreaseRequest(BranchAdmission.Proof proof,UUID facility,long newLimitMinor,long facilityRevision,long profileRevision,long tierRevision,long calendarOrigin) {
        public IncreaseRequest {Objects.requireNonNull(proof);Objects.requireNonNull(facility);if(newLimitMinor<1||facilityRevision<1||profileRevision<1||tierRevision<0||calendarOrigin<0)throw new IllegalArgumentException("Increase fields");}
        public Journal.CanonicalRequest request(){var c=proof.context();return canonical(c.world(),c.actor(),Map.ofEntries(Map.entry("facility",facility.toString()),Map.entry("new_limit",Long.toString(newLimitMinor)),Map.entry("facility_revision",Long.toString(facilityRevision)),Map.entry("profile_revision",Long.toString(profileRevision)),Map.entry("tier_revision",Long.toString(tierRevision)),Map.entry("origin",Long.toString(calendarOrigin)),Map.entry("lease",proof.id().toString()),Map.entry("session",c.session().toString()),Map.entry("issuer",c.bank().key()),Map.entry("denomination_revision",Long.toString(c.denominationRevision()))));}
    }
    public static Registration increase(PartyRef owner,IncreaseRequest request,BankProviders.TierEligibility tiers,InputSource inputs,LongSupplier clock) {
        return new Registration(1,(intent,reads)->{
            var c=request.proof().context();if(!matches(owner,intent,INCREASE,request.request())||!CreditProfiles.owns(owner,c.world(),c.actor())||!c.action().name().equals("CREDIT_INCREASE"))return reject("CREDIT_INCREASE_REQUEST");
            if(!request.proof().valid(intent,reads))return decline("BRANCH_UNAVAILABLE");
            if(!reads.query("SELECT lease_id FROM bank_provider_leases WHERE lease_id=?",List.of(request.proof().id().toString()),1).isEmpty())return reject("LEASE_USED");
            var f=find(reads,request.facility()).filter(v->v.party().equals(owner)).orElse(null);var profile=CreditProfiles.find(reads,owner).orElse(null);
            if(f==null||f.status()!=Status.ACTIVE||f.access()!=Access.ENABLED||f.revision()!=request.facilityRevision()||!f.issuer().equals(c.bank())||profile==null||profile.revision()!=request.profileRevision())return reject("CREDIT_FACILITY_STALE");
            if(request.newLimitMinor()<=f.limitMinor()||request.newLimitMinor()%f.currency().minorUnitsPerMajor()!=0)return reject("CREDIT_INCREASE_AMOUNT");
            long now=clock.getAsLong();if(now<f.lastDay()||now>=f.nextDay()||now>=f.nextStatement())return decline("CREDIT_CATCHUP_REQUIRED");
            var denomination=RebaseSettlement.denomination(reads).orElse(null);if(denomination==null||denomination.revision()!=c.denominationRevision())return reject("DENOMINATION_STALE");
            if(!CreditProfiles.origin(reads).filter(v->v==request.calendarOrigin()).isPresent())return decline("CREDIT_CALENDAR_UNAVAILABLE");
            var bank=InstitutionCatalog.require(f.issuer());if(!enabled(reads,f.world(),bank.id()))return decline("BANK_UNAVAILABLE");
            var tier=tiers.current(reads,owner);if(bank.tier2()&&tier.available()&&tier.revision()!=request.tierRevision())return reject("CREDIT_TIER_STALE");
            var source=inputs.current(reads,owner,now);if(source==null||!source.complete()||!source.fx().available())return decline("CREDIT_EXPOSURE_UNAVAILABLE");
            boolean tier2=bank.tier2()&&tier.available()&&tier.eligible();String reason="APPROVED";
            var facilities=owned(reads,owner);if(facilities.size()>128)return decline("CREDIT_EXPOSURE_UNAVAILABLE");
            boolean blocked=source.creditBlocked()||facilities.stream().anyMatch(v->v.status()==Status.DEFAULTED&&ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.blocks(reads,ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind.CARD,v.id(),now)||v.requirements().stream().anyMatch(r->r.unmetMinor()>0&&r.dueAt()<=now));
            long combined=0;for(var line:facilities)if(line.status()==Status.ACTIVE&&line.access()!=Access.CLOSED)combined=Math.addExact(combined,CreditUnderwriting.exposureToWorld(new Money(line.currency(),line.id().equals(f.id())?request.newLimitMinor():line.limitMinor()),denomination.currency(),source.fx()).minorUnits());
            long oldLimit=CreditUnderwriting.exposureToWorld(new Money(f.currency(),f.limitMinor()),denomination.currency(),source.fx()).minorUnits(),newLimit=CreditUnderwriting.exposureToWorld(new Money(f.currency(),request.newLimitMinor()),denomination.currency(),source.fx()).minorUnits(),owed=CreditUnderwriting.exposureToWorld(new Money(f.currency(),f.owedMinor()),denomination.currency(),source.fx()).minorUnits();
            long exposure=Math.addExact(Math.addExact(exposure(reads,owner,denomination.currency(),source.fx()),source.otherExposureWorldMinor()),Math.subtractExact(Math.max(newLimit,owed),Math.max(oldLimit,owed)));
            if(!bank.tier1()&&!tier2)reason="TIER2_UNAVAILABLE";else if(blocked)reason="ARREARS_DEFAULT_OR_COLLECTION";else if(profile.score()<500)reason="SCORE_BELOW_PRODUCT_FLOOR";
            else if(combined>Math.multiplyExact(CreditUnderwriting.revolvingCeiling(profile.score()),denomination.currency().minorUnitsPerMajor()))reason="COMBINED_REVOLVING_CEILING";
            else if(exposure>Math.multiplyExact(CreditUnderwriting.exposureCeiling(profile.score(),tier2),denomination.currency().minorUnitsPerMajor()))reason="OWNER_EXPOSURE_CEILING";
            else if(DepositYield.distressed(reads,bank.id()))reason="ISSUER_YIELD_DISTRESSED";
            Commit funding=null;if(reason.equals("APPROVED")){var decision=CreditTreasury.reserve(reads,f.world(),f.issuer(),f.currency(),Math.subtractExact(request.newLimitMinor(),f.limitMinor()),now);if(decision instanceof Commit funded)funding=funded;else reason=decision instanceof Reject r?r.result().code():"CREDIT_FUNDING_UNAVAILABLE";}
            var inquiry=new CreditProfiles.InquiryRequest(f.world(),f.owner(),f.issuer(),intent.operationId().value(),CreditProfiles.ApplicationKind.CARD,now,request.calendarOrigin(),false);
            final String outcome=reason;final Commit reserved=funding;
            return CreditProfiles.acceptedInquiry(owner,inquiry,()->now,(i,r)->{
                if(!outcome.equals("APPROVED"))return new Commit(List.of(event("INCREASE_DENIED",f.id(),now,outcome)),new Journal.Result(1,"CREDIT_APPLICATION_DENIED",outcome),tx->{});
                var next=new Facility(f.world(),f.id(),f.owner(),f.issuer(),f.network(),f.currency(),request.newLimitMinor(),f.tier(),f.anchor(),f.lastDay(),f.lastStatement(),Math.addExact(f.revision(),1),f.status(),f.access(),f.purchase(),f.advance(),f.requirements(),f.slices());
                return withState(reserved,next,event("LIMIT_INCREASED",f.id(),now,f.limitMinor()+"|"+request.newLimitMinor()),"CREDIT_LIMIT_INCREASED",tx->tx.execute("INSERT INTO bank_provider_leases VALUES(?,?,?)",List.of(request.proof().id().toString(),intent.operationId().value().toString(),intent.canonical())));
            }).handler().prepare(adapt(intent,CreditProfiles.INQUIRY,inquiry.request()),reads);
        });
    }
    public static final Journal.Kind CLOSE=new Journal.Kind("economy:credit","economy:close"),REPLACE=new Journal.Kind("economy:credit","economy:replace");
    public static Journal.CanonicalRequest closeRequest(WorldId world,PlayerId owner,UUID facility,long revision){return canonical(world,owner,Map.of("facility",facility.toString(),"revision",Long.toString(revision)));}
    public static Registration closure(PartyRef owner,UUID facility,long revision,LongSupplier clock) {
        return new Registration(1,(intent,reads)->{
            if(!(owner.owner() instanceof PartyRef.Player p)||!matches(owner,intent,CLOSE,closeRequest(owner.world(),p.id(),facility,revision)))return reject("CREDIT_CLOSE_REQUEST");
            var f=find(reads,facility).filter(v->v.party().equals(owner)).orElse(null);
            if(f==null||f.revision()!=revision||f.access()==Access.CLOSED)return reject("CREDIT_FACILITY_STALE");
            long now=clock.getAsLong();if(now<f.lastDay()||now>=f.nextDay()||now>=f.nextStatement())return decline("CREDIT_CATCHUP_REQUIRED");
            long release=Math.max(0,Math.subtractExact(f.limitMinor(),f.principalMinor()));
            var next=new Facility(f.world(),f.id(),f.owner(),f.issuer(),f.network(),f.currency(),f.limitMinor(),f.tier(),f.anchor(),f.lastDay(),f.lastStatement(),Math.addExact(f.revision(),1),f.owedMinor()==0?Status.SETTLED:f.status(),Access.CLOSED,f.purchase(),f.advance(),f.requirements(),f.slices());
            var token=tokenForFacility(reads,f.world(),f.id()).orElse(null);Token revoked=token==null?null:new Token(token.world(),token.id(),token.facility(),token.owner(),token.issuer(),token.network(),token.tier(),IssuedCards.Status.REVOKED,token.issuedAt(),token.expiresAt(),Math.addExact(token.revision(),1),token.custodyId());
            var reserve=financial(f,FinancialAccounts.Role.RESERVATION);var cash=financial(f,FinancialAccounts.Role.CASH);
            Commit money=null;if(release>0){var decision=InstitutionSettlement.prepare(reads,List.of(leg(reserve,-release),leg(cash,release)),List.of(reserve,cash));if(!(decision instanceof Commit c))return decision;money=c;}
            var records=new ArrayList<Journal.Record>();if(money!=null)records.addAll(money.records());records.add(fact(next));records.add(event("FACILITY_CLOSED",f.id(),now,Long.toString(release)));if(revoked!=null)records.add(tokenFact(revoked));var financial=money;
            return new Commit(records,new Journal.Result(1,"CREDIT_FACILITY_CLOSED",f.id().toString()),tx->{if(financial!=null)financial.projections().apply(tx);write(tx,next);if(revoked!=null)writeToken(tx,revoked);});
        });
    }
    public record ReplacementRequest(BranchAdmission.Proof proof,UUID facility,UUID token,long facilityRevision,long tokenRevision,IssuedCards.FeeQuote fee) {
        public ReplacementRequest {Objects.requireNonNull(proof);Objects.requireNonNull(facility);Objects.requireNonNull(token);if(facilityRevision<1||tokenRevision<1)throw new IllegalArgumentException("Replacement revision");}
        public Journal.CanonicalRequest request(){var c=proof.context();var fields=new TreeMap<String,String>();fields.put("facility",facility.toString());fields.put("token",token.toString());fields.put("facility_revision",Long.toString(facilityRevision));fields.put("token_revision",Long.toString(tokenRevision));fields.put("lease",proof.id().toString());fields.put("session",c.session().toString());fields.put("issuer",c.bank().key());fields.put("denomination_revision",Long.toString(c.denominationRevision()));fields.put("fee",fee==null?"":fee.id().toString());fields.put("fee_capture",fee==null?"":CreditProfiles.hash(fee.capture()));fields.put("fee_funding",fee==null?"":fee.funding().value().toString());fields.put("fee_funding_revision",fee==null?"":Long.toString(fee.fundingRevision()));fields.put("fee_denomination_revision",fee==null?"":Long.toString(fee.denominationRevision()));fields.put("fee_currency",fee==null?"":fee.currency().name());fields.put("fee_debit",fee==null?"":Long.toString(fee.debitMinor()));fields.put("fee_expiry",fee==null?"":Long.toString(fee.expiresAt()));return canonical(c.world(),c.actor(),fields);}
    }
    public static Registration replacement(PartyRef owner,ReplacementRequest request,IssuedCards.Fee fee,LongSupplier clock) {
        return new Registration(1,(intent,reads)->{
            var c=request.proof().context();if(!matches(owner,intent,REPLACE,request.request())||!CreditProfiles.owns(owner,c.world(),c.actor())||!c.action().name().equals("CREDIT_REPLACE"))return reject("CREDIT_REPLACE_REQUEST");
            if(!request.proof().valid(intent,reads))return decline("BRANCH_UNAVAILABLE");
            if(!reads.query("SELECT lease_id FROM bank_provider_leases WHERE lease_id=?",List.of(request.proof().id().toString()),1).isEmpty())return reject("LEASE_USED");
            var f=find(reads,request.facility()).filter(v->v.party().equals(owner)).orElse(null);var old=token(reads,owner.world(),request.token()).orElse(null);
            if(f==null||f.access()==Access.CLOSED||f.status()!=Status.ACTIVE||f.revision()!=request.facilityRevision()||old==null||!old.facility().equals(f.id())||!old.owner().equals(f.owner())||old.revision()!=request.tokenRevision()||!c.bank().equals(f.issuer()))return reject("CREDIT_CARD_STALE");
            // Only the newest token may be renewed, including a previously reported-lost token.
            var latest=reads.query("SELECT body FROM credit_facility_tokens WHERE world_id=? AND facility_id=? ORDER BY rowid DESC LIMIT 1",List.of(f.world().value().toString(),f.id().toString()),1);
            if(latest.isEmpty()||!decodeToken(latest.getFirst().get("body")).id().equals(old.id()))return reject("CREDIT_TOKEN_STALE");
            long now=clock.getAsLong();if(now<old.issuedAt())return decline("CREDIT_CLOCK_STALE");
            var denom=RebaseSettlement.denomination(reads).orElse(null);if(denom==null||denom.revision()!=c.denominationRevision())return reject("DENOMINATION_STALE");
            Commit paid=null;boolean free=now>=old.expiresAt()-IssuedCards.RENEWAL_WINDOW;
            if(!free){var q=request.fee();if(q==null||fee==null)return reject("CARD_FEE_REQUIRED");if(q.denominationRevision()!=denom.revision()||now>=q.expiresAt())return reject("CARD_FEE_STALE");var funding=AccountCatalog.find(reads,owner,q.funding()).orElse(null);if(funding==null||funding.status()!=AccountIdentity.Status.OPEN||funding.purpose()!=AccountIdentity.Purpose.BANK||funding.revision()!=q.fundingRevision()||funding.currency()!=q.currency())return reject("CARD_FUNDING_STALE");var decision=fee.prepare(intent,q,reads);if(!(decision instanceof Commit payment))return decision;paid=payment;}else if(request.fee()!=null)return reject("CARD_FEE_UNEXPECTED");
            var revoked=new Token(old.world(),old.id(),old.facility(),old.owner(),old.issuer(),old.network(),old.tier(),IssuedCards.Status.REVOKED,old.issuedAt(),old.expiresAt(),Math.addExact(old.revision(),1),old.custodyId());
            var next=new Token(f.world(),intent.operationId().value(),f.id(),f.owner(),f.issuer(),f.network(),old.tier(),IssuedCards.Status.ACTIVE,now,Math.addExact(now,IssuedCards.LIFETIME),1,intent.operationId().value());
            var delivery=Custody.committedDelivery(intent,next.id(),f.owner(),deliveryItems(next),"issued-card:"+next.id(),Journal.actorKey(f.owner()),reads);
            var facts=new ArrayList<Journal.Record>();if(paid!=null)facts.addAll(paid.records());facts.add(tokenFact(revoked));facts.add(tokenFact(next));facts.addAll(delivery.records());var payment=paid;
            return new Commit(facts,new Journal.Result(1,"CREDIT_CARD_REPLACED",next.id().toString()),tx->{if(payment!=null)payment.projections().apply(tx);writeToken(tx,revoked);writeToken(tx,next);delivery.projections().apply(tx);tx.execute("INSERT INTO bank_provider_leases VALUES(?,?,?)",List.of(request.proof().id().toString(),intent.operationId().value().toString(),intent.canonical()));});
        });
    }
    private record Destination(EconomyCurrency currency,long revision) {}
    private static Destination account(ReadView reads,AccountId account){return reads.query("SELECT currency,revision FROM account_catalog WHERE account_id=? AND purpose='BANK' AND status='OPEN'",List.of(account.value().toString()),1).stream().map(r->new Destination(EconomyCurrency.valueOf(r.get("currency")),Long.parseLong(r.get("revision")))).findFirst().orElse(null);}
    private static Facility change(Facility f,CardServicing.Bucket p,CardServicing.Bucket a,List<CardServicing.Requirement> r,List<CardServicing.DebtSlice>s,long day,long statement){return new Facility(f.world(),f.id(),f.owner(),f.issuer(),f.network(),f.currency(),f.limitMinor(),f.tier(),f.anchor(),day,statement,Math.addExact(f.revision(),1),f.access()==Access.CLOSED&&p.debtMinor()==0&&a.debtMinor()==0?Status.SETTLED:f.status(),f.access(),p,a,r,s);}
    private static Commit withAccounts(ReadView reads,Commit money,List<FinancialAccounts.Account> accounts){
        var records=new ArrayList<>(money.records());var seen=new HashSet<UUID>();var parties=new LinkedHashMap<String,FinancialAccounts.Account>();
        for(var a:accounts){if(a.owner().key().startsWith("economy:borrower/")&&reads.query("SELECT institution_key FROM institution_parties WHERE world_id=? AND institution_key=?",List.of(a.world().value().toString(),a.owner().key()),1).isEmpty())parties.putIfAbsent(a.owner().key(),a);
            if(seen.add(a.id())&&FinancialAccounts.find(reads,a.id()).isEmpty())records.add(new Journal.Record(InstitutionCapital.ACCOUNT,1,a.id()+"|"+a.world().value()+"|"+a.owner().key()+"|"+a.role()+"|"+a.currency()));}
        for(var a:parties.values())records.add(new Journal.Record(BORROWER_FACT,1,a.world().value()+"|"+a.owner().key().substring("economy:borrower/".length())+"|"+new InstitutionCatalog.SystemActor(a.world(),a.owner()).actorId().value()+"|"+a.currency()));
        return new Commit(records,money.result(),tx->{for(var a:parties.values())tx.execute("INSERT OR IGNORE INTO institution_parties(world_id,institution_key,actor_id,display_name,base_currency,tier,enabled) VALUES(?,?,?,?,?,'INTERNAL',1)",List.of(a.world().value().toString(),a.owner().key(),new InstitutionCatalog.SystemActor(a.world(),a.owner()).actorId().value().toString(),"Borrower credit claims",a.currency().name()));for(var a:accounts)FinancialAccounts.register(tx,a);money.projections().apply(tx);});
    }
    private static Commit audited(Commit commit,Journal.Record witness){var records=new ArrayList<>(commit.records());records.add(witness);return new Commit(records,commit.result(),commit.projections());}
    private static Commit withState(Commit money,Facility next,Journal.Record event,String code,Projection extras){var records=new ArrayList<>(money.records());records.add(fact(next));records.add(event);return new Commit(records,new Journal.Result(1,code,next.id().toString()),tx->{money.projections().apply(tx);extras.apply(tx);write(tx,next);});}
    private static FinancialAccounts.Account financial(Facility f,FinancialAccounts.Role role){return FinancialAccounts.account(f.world(),f.issuer(),role,f.currency());}
    private static FinancialAccounts.Account borrower(Facility f,FinancialAccounts.Role role){return FinancialAccounts.account(f.world(),new InstitutionCatalog.InstitutionId("economy:borrower/"+f.owner().value()),role,f.currency());}
    private static PostingBatch.Leg leg(FinancialAccounts.Account a,long amount){return new PostingBatch.Leg(a.id(),a.role().spendable()?PostingBatch.Role.INSTITUTION_CASH:PostingBatch.Role.INSTITUTION_CLAIM,a.currency(),new Money(a.currency(),amount));}
    private static PostingBatch.Leg customer(AccountId a,EconomyCurrency currency,long amount){return new PostingBatch.Leg(a.value(),PostingBatch.Role.ACCOUNT,currency,new Money(currency,amount));}
    private static boolean enabled(ReadView reads,WorldId world,InstitutionCatalog.InstitutionId bank){var rows=reads.query("SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",List.of(world.value().toString(),bank.key()),1);return !rows.isEmpty()&&rows.getFirst().get("enabled").equals("1");}
    private static boolean matches(PartyRef owner,Journal.Intent intent,Journal.Kind kind,Journal.CanonicalRequest request){return owner.owner() instanceof PartyRef.Player p&&intent.actor().equals(p.id())&&intent.kind().equals(kind)&&intent.request().equals(request);}
    private static Journal.CanonicalRequest canonical(WorldId world,PlayerId owner,Map<String,String> fields){var all=new TreeMap<>(fields);all.put("world",world.value().toString());all.put("economy:owner",Journal.actorKey(owner));return new Journal.CanonicalRequest(1,all);}
    private static Journal.Intent adapt(Journal.Intent intent,Journal.Kind kind,Journal.CanonicalRequest request){return new Journal.Intent(intent.operationId(),intent.requestId(),intent.actor(),kind,request,intent.economicUpdateId(),intent.correctionOf());}
    private static BigDecimal zero(){return BigDecimal.ZERO.setScale(18);}
    private static Reject reject(String code){return new Reject(new Journal.Result(1,code,""));}
    private static Decline decline(String code){return new Decline(new Journal.Result(1,code,""));}
    public static Journal.Record fact(Facility f){return new Journal.Record(FACT,VERSION,encode(f));}
    private static Journal.Record tokenFact(Token t){return new Journal.Record(TOKEN_FACT,VERSION,encode(t));}
    private static Journal.Record event(String kind,UUID facility,long effective,String detail){return new Journal.Record(EVENT_FACT,VERSION,pack(List.of(kind,facility.toString(),Long.toString(effective),detail)));}
    public static void write(FoundationStore.Transaction tx,Facility f){
        tx.execute("INSERT INTO credit_facilities(world_id,facility_id,owner_id,issuer,currency,status,debt_minor,limit_minor,access,next_day,next_statement,revision,body) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world_id,facility_id) DO UPDATE SET status=excluded.status,debt_minor=excluded.debt_minor,limit_minor=excluded.limit_minor,access=excluded.access,next_day=excluded.next_day,next_statement=excluded.next_statement,revision=excluded.revision,body=excluded.body",
                List.of(f.world().value().toString(),f.id().toString(),f.owner().value().toString(),f.issuer().key(),f.currency().name(),f.status().name(),Long.toString(f.owedMinor()),Long.toString(f.limitMinor()),f.access().name(),Long.toString(f.nextDay()),Long.toString(f.nextStatement()),Long.toString(f.revision()),encode(f)));
    }
    private static void writeToken(FoundationStore.Transaction tx,Token t){tx.execute("INSERT INTO credit_facility_tokens VALUES(?,?,?,?,?,?) ON CONFLICT(world_id,token_id) DO UPDATE SET status=excluded.status,body=excluded.body",List.of(t.world().value().toString(),t.id().toString(),t.facility().toString(),t.owner().value().toString(),t.status().name(),encode(t)));}
    private static Facility readFacility(Map<String,String> row){var f=decode(row.get("body"));
        if(!f.world().value().toString().equals(row.get("world_id"))||!f.id().toString().equals(row.get("facility_id"))||!f.owner().value().toString().equals(row.get("owner_id"))||!f.issuer().key().equals(row.get("issuer"))||!f.currency().name().equals(row.get("currency"))||!f.status().name().equals(row.get("status"))||!f.access().name().equals(row.get("access"))||f.owedMinor()!=number(row.get("debt_minor"))||f.limitMinor()!=number(row.get("limit_minor"))||f.revision()!=number(row.get("revision"))||f.nextDay()!=number(row.get("next_day"))||f.nextStatement()!=number(row.get("next_statement")))throw new IllegalStateException("Credit facility projection mismatch");
        return f;
    }
    private static String encode(Facility f){return pack(List.of(f.world().value().toString(),f.id().toString(),f.owner().value().toString(),f.issuer().key(),f.network().name(),f.currency().name(),Long.toString(f.limitMinor()),Integer.toString(f.tier()),Long.toString(f.anchor()),Long.toString(f.lastDay()),Long.toString(f.lastStatement()),Long.toString(f.revision()),f.status().name(),f.access().name(),bucket(f.purchase()),bucket(f.advance()),requirements(f.requirements()),slices(f.slices())));}
    private static Facility decode(String body){var v=unpack(body,18);return new Facility(new WorldId(UUID.fromString(v.get(0))),UUID.fromString(v.get(1)),new PlayerId(UUID.fromString(v.get(2))),new InstitutionCatalog.InstitutionId(v.get(3)),InstitutionCatalog.Network.valueOf(v.get(4)),EconomyCurrency.valueOf(v.get(5)),number(v.get(6)),Integer.parseInt(v.get(7)),number(v.get(8)),number(v.get(9)),number(v.get(10)),number(v.get(11)),Status.valueOf(v.get(12)),Access.valueOf(v.get(13)),bucket(v.get(14)),bucket(v.get(15)),requirements(v.get(16)),slices(v.get(17)));}
    private static String bucket(CardServicing.Bucket b){return String.join(",",b.kind().name(),Long.toString(b.principalMinor()),Long.toString(b.interestMinor()),b.residualMajor().toPlainString(),b.periodEarnedMajor().toPlainString(),b.apr().toPlainString());}
    private static CardServicing.Bucket bucket(String body){var p=body.split(",",-1);if(p.length!=6)throw new IllegalArgumentException("Bucket fields");return new CardServicing.Bucket(CardServicing.Kind.valueOf(p[0]),number(p[1]),number(p[2]),new BigDecimal(p[3]),new BigDecimal(p[4]),new BigDecimal(p[5]));}
    private static String requirements(List<CardServicing.Requirement> values){return String.join("\n",values.stream().map(r->String.join(",",r.id(),Long.toString(r.statementAt()),Long.toString(r.dueAt()),Long.toString(r.originalMinor()),Long.toString(r.unmetMinor()))).toList());}
    private static List<CardServicing.Requirement> requirements(String text){if(text.isEmpty())return List.of();var result=new ArrayList<CardServicing.Requirement>();for(var line:text.split("\n",-1)){var p=line.split(",",-1);if(p.length!=5)throw new IllegalArgumentException("Requirement fields");result.add(new CardServicing.Requirement(p[0],number(p[1]),number(p[2]),number(p[3]),number(p[4])));}return result;}
    private static String slices(List<CardServicing.DebtSlice> values){return String.join("\n",values.stream().map(s->String.join(",",s.id(),Long.toString(s.statementAt()),Long.toString(s.dueAt()),s.kind().name(),Long.toString(s.principalMinor()),Long.toString(s.interestMinor()))).toList());}
    private static List<CardServicing.DebtSlice> slices(String text){if(text.isEmpty())return List.of();var result=new ArrayList<CardServicing.DebtSlice>();for(var line:text.split("\n",-1)){var p=line.split(",",-1);if(p.length!=6)throw new IllegalArgumentException("Debt slice fields");result.add(new CardServicing.DebtSlice(p[0],number(p[1]),number(p[2]),CardServicing.Kind.valueOf(p[3]),number(p[4]),number(p[5])));}return result;}
    private static String encode(Token t){return pack(List.of(t.world().value().toString(),t.id().toString(),t.facility().toString(),t.owner().value().toString(),t.issuer().key(),t.network().name(),Integer.toString(t.tier()),t.status().name(),Long.toString(t.issuedAt()),Long.toString(t.expiresAt()),Long.toString(t.revision()),t.custodyId().toString()));}
    private static Token decodeToken(String body){var p=unpack(body,12);return new Token(new WorldId(UUID.fromString(p.get(0))),UUID.fromString(p.get(1)),UUID.fromString(p.get(2)),new PlayerId(UUID.fromString(p.get(3))),new InstitutionCatalog.InstitutionId(p.get(4)),InstitutionCatalog.Network.valueOf(p.get(5)),Integer.parseInt(p.get(6)),IssuedCards.Status.valueOf(p.get(7)),number(p.get(8)),number(p.get(9)),number(p.get(10)),UUID.fromString(p.get(11)));}
    private static String encodeStatement(Facility f,CardServicing.Statement s){return pack(List.of(f.world().value().toString(),f.id().toString(),f.owner().value().toString(),s.id(),Long.toString(s.closedAt()),Long.toString(s.dueAt()),Long.toString(s.debtMinor()),Long.toString(s.principalMinor()),s.periodInterestMajor().toPlainString(),Long.toString(s.newRequirementMinor()),Long.toString(s.totalRequiredMinor())));}
    private static String pack(List<String> fields){var encoder=Base64.getUrlEncoder().withoutPadding();return String.join("|",fields.stream().map(v->encoder.encodeToString(v.getBytes(StandardCharsets.UTF_8))).toList());}
    private static List<String> unpack(String text,int count){var p=text.split("\\|",-1);if(p.length!=count)throw new IllegalArgumentException("Credit record fields");var decoded=Arrays.stream(p).map(v->new String(Base64.getUrlDecoder().decode(v),StandardCharsets.UTF_8)).toList();if(!pack(decoded).equals(text))throw new IllegalArgumentException("Credit record encoding");return decoded;}
    private static long number(String text){long n=Long.parseLong(text);if(!Long.toString(n).equals(text))throw new IllegalArgumentException("Noncanonical number");return n;}
    /** Historical immutable snapshots for trusted downstream event consumers; not player authority. */
    public static Facility decodeSnapshot(String payload){validateFact(payload);return decode(payload);}
    public static StatementSnapshot decodeStatementSnapshot(String payload){return decodeStatement(payload);}
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(FACT,1),CreditFacilities::validateFact).with(new RecordCatalog.Key(TOKEN_FACT,1),CreditFacilities::validateTokenFact).with(new RecordCatalog.Key(STATEMENT_FACT,1),CreditFacilities::validateStatementFact).with(new RecordCatalog.Key(EVENT_FACT,1),CreditFacilities::validateEventFact).with(new RecordCatalog.Key(BORROWER_FACT,1),CreditFacilities::validateBorrowerFact);}
    public static void validateFact(String payload){var f=decode(payload);if(!encode(f).equals(payload)||!f.purchase().apr().equals(apr(f.network(),false))||!f.advance().apr().equals(apr(f.network(),true)))throw new IllegalArgumentException("Credit facility canonical fields");}
    public static void validateTokenFact(String payload){var t=decodeToken(payload);if(!encode(t).equals(payload)||!InstitutionCatalog.require(t.issuer()).networks().contains(t.network()))throw new IllegalArgumentException("Credit token canonical fields");}
    public static void validateStatementFact(String payload){var p=unpack(payload,11);UUID.fromString(p.get(0));UUID.fromString(p.get(1));UUID.fromString(p.get(2));UUID.fromString(p.get(3));long close=number(p.get(4)),due=number(p.get(5)),debt=number(p.get(6)),principal=number(p.get(7)),minimum=number(p.get(9)),total=number(p.get(10));var interest=new BigDecimal(p.get(8));if(close<0||due!=Math.addExact(close,14*DAY)||debt<0||principal<0||principal>debt||interest.signum()<0||interest.scale()!=18||minimum<0||minimum>total||total>debt)throw new IllegalArgumentException("Statement fields");}
    public static void validateBorrowerFact(String payload){var p=payload.split("\\|",-1);if(p.length!=4)throw new IllegalArgumentException("Borrower party fields");var world=new WorldId(UUID.fromString(p[0]));var owner=UUID.fromString(p[1]);var key=new InstitutionCatalog.InstitutionId("economy:borrower/"+owner);if(!new InstitutionCatalog.SystemActor(world,key).actorId().value().toString().equals(p[2]))throw new IllegalArgumentException("Borrower party identity");EconomyCurrency.valueOf(p[3]);}
    public static void validateEventFact(String payload){var p=unpack(payload,4);UUID.fromString(p.get(1));if(!p.get(0).matches("[A-Z][A-Z0-9_]{0,63}")||number(p.get(2))<0||p.get(3).getBytes(StandardCharsets.UTF_8).length>2048)throw new IllegalArgumentException("Credit event fields");}
}
