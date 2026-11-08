package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.interest.DepositYield;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

/** Worker-confined unsecured fixed-rate loan contracts; production admission is installed by the service. */
public final class Loans {
    public static final int VERSION=1,MAX_LIVE=128,MAX_REQUIREMENTS=16;
    public static final long DAY=1_200_000L,MONTH=28*DAY;
    public static final String FACT="economy:a6/loan",INSTALLMENT_FACT="economy:a6/loan-installment",EVENT_FACT="economy:a6/loan-event",BORROWER_FACT="economy:a6/loan-borrower",APPLICATION_FACT="economy:a6/loan-application";
    public static final Journal.Kind APPLY=new Journal.Kind("economy:loans","economy:apply"),REPAY=new Journal.Kind("economy:loans","economy:repay");
    public enum Status { ACTIVE, DEFAULTED, SETTLED }
    public enum RateMode { FIXED, VARIABLE }
    public record Capabilities(boolean fixed,boolean variable,boolean secured,String collateralAdapter){
        public Capabilities{Objects.requireNonNull(collateralAdapter);}
    }
    /** B6 must install a separately accepted collateral/priority contract; this release makes no pledge. */
    public static Capabilities capabilities(){return new Capabilities(true,false,false,"UNSUPPORTED_B6_ADAPTER_REQUIRED");}
    public record Contract(WorldId world,UUID id,PlayerId owner,InstitutionCatalog.InstitutionId lender,
            CreditUnderwriting.Product product,EconomyCurrency currency,long originalPrincipalMinor,int years,
            BigDecimal apr,BigDecimal primeApr,int primeVersion,int capturedScore,long profileRevision,int tier,
            int policyVersion,long denominationRevision,UUID fxPublication,EconomyCurrency worldCurrency,long anchor,
            String scheduleHash,long revision,Status status,LoanServicing.State servicing,UUID primePolicyId,long primePolicyRevision){
        public Contract(WorldId world,UUID id,PlayerId owner,InstitutionCatalog.InstitutionId lender,
            CreditUnderwriting.Product product,EconomyCurrency currency,long originalPrincipalMinor,int years,
            BigDecimal apr,BigDecimal primeApr,int primeVersion,int capturedScore,long profileRevision,int tier,
            int policyVersion,long denominationRevision,UUID fxPublication,EconomyCurrency worldCurrency,long anchor,
            String scheduleHash,long revision,Status status,LoanServicing.State servicing){this(world,id,owner,lender,product,currency,originalPrincipalMinor,years,apr,primeApr,primeVersion,capturedScore,profileRevision,tier,policyVersion,denominationRevision,fxPublication,worldCurrency,anchor,scheduleHash,revision,status,servicing,null,0);}
        public Contract {
            Objects.requireNonNull(world);Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(lender);Objects.requireNonNull(product);Objects.requireNonNull(currency);
            Objects.requireNonNull(apr);Objects.requireNonNull(primeApr);Objects.requireNonNull(fxPublication);Objects.requireNonNull(worldCurrency);Objects.requireNonNull(status);Objects.requireNonNull(servicing);
            validatePrimeBinding(product,primePolicyId,primePolicyRevision,primeVersion,primeApr);
            if(originalPrincipalMinor<=0||years<1||years>(product==CreditUnderwriting.Product.BANK_LOAN?5:8)||primeVersion<0||capturedScore<300||capturedScore>900||profileRevision<1||tier<1||tier>2||policyVersion!=1||denominationRevision<0||anchor<0||revision<1||!scheduleHash.matches("[0-9a-f]{64}")
                ||servicing.currency()!=currency||servicing.apr().compareTo(apr)!=0||servicing.requirements().size()>MAX_REQUIREMENTS||servicing.periodStart()<anchor||(servicing.periodStart()-anchor)%MONTH!=0||servicing.totalPeriods()!=13*years
                ||servicing.completedPeriods()!=(servicing.periodStart()-anchor)/MONTH||(status==Status.SETTLED)!=servicing.closed())throw new IllegalArgumentException("Loan contract fields");
            for(var requirement:servicing.requirements())if(requirement.dueAt()<=anchor||requirement.dueAt()>servicing.periodStart()||(requirement.dueAt()-anchor)%MONTH!=0||!requirement.id().equals(stable("installment|"+world.value()+"|"+id+"|"+requirement.dueAt()).toString()))throw new IllegalArgumentException("Original loan obligation identity");
            if(product==CreditUnderwriting.Product.SUBPRIME_LOAN&&!lender.equals(InstitutionCatalog.IRONBRIDGE))throw new IllegalArgumentException("Subprime lender");
            if(product==CreditUnderwriting.Product.BANK_LOAN&&!InstitutionCatalog.require(lender).customerBank())throw new IllegalArgumentException("Bank lender");
            var band=product==CreditUnderwriting.Product.BANK_LOAN?CreditUnderwriting.bankBand(capturedScore):CreditUnderwriting.subprimeBand(capturedScore);
            var expectedApr=product==CreditUnderwriting.Product.SUBPRIME_LOAN?band.premiumApr():tier==2?primeApr.divide(BigDecimal.valueOf(4)).add(band.premiumApr().divide(BigDecimal.valueOf(4))):primeApr.add(new BigDecimal("0.02")).add(band.premiumApr());
            if(band.principalMajor()==0||years>band.maximumYears()||originalPrincipalMinor%currency.minorUnitsPerMajor()!=0||apr.compareTo(expectedApr)!=0||primeApr.signum()<0||primeApr.compareTo(new BigDecimal("0.5"))>0||primeApr.scale()>6
                ||product==CreditUnderwriting.Product.BANK_LOAN&&(primePolicyId==null&&primeVersion<1||InstitutionCatalog.require(lender).baseCurrency()!=currency)
                ||product==CreditUnderwriting.Product.SUBPRIME_LOAN&&(tier!=1||primeVersion!=0||primeApr.signum()!=0||currency!=worldCurrency))throw new IllegalArgumentException("Captured loan policy contract");
        }
        public PartyRef party(){return new PartyRef(world,new PartyRef.Player(owner));}
        public long owedMinor(){return servicing.debtMinor();}
        public long nextMonth(){return Math.addExact(servicing.periodStart(),MONTH);}
    }
    /** Immutable accepted offer/denial inputs. A missing profile has no actual score. */
    public record ApplicationDecision(WorldId world,PlayerId owner,UUID application,UUID loan,InstitutionCatalog.InstitutionId lender,
            CreditUnderwriting.Product product,EconomyCurrency currency,long requestedMinor,int years,boolean established,int capturedScore,
            long profileRevision,int tier,int primeVersion,BigDecimal primeApr,UUID fxPublication,EconomyCurrency worldCurrency,
            long currentExposureWorldMinor,long maximumPrincipalWorldMinor,long totalExposureCeilingWorldMinor,BigDecimal apr,
            int maximumYears,String reason,int policyVersion,long effectiveAt,UUID primePolicyId,long primePolicyRevision){
        public ApplicationDecision(WorldId world,PlayerId owner,UUID application,UUID loan,InstitutionCatalog.InstitutionId lender,
            CreditUnderwriting.Product product,EconomyCurrency currency,long requestedMinor,int years,boolean established,int capturedScore,
            long profileRevision,int tier,int primeVersion,BigDecimal primeApr,UUID fxPublication,EconomyCurrency worldCurrency,
            long currentExposureWorldMinor,long maximumPrincipalWorldMinor,long totalExposureCeilingWorldMinor,BigDecimal apr,
            int maximumYears,String reason,int policyVersion,long effectiveAt){this(world,owner,application,loan,lender,product,currency,requestedMinor,years,established,capturedScore,profileRevision,tier,primeVersion,primeApr,fxPublication,worldCurrency,currentExposureWorldMinor,maximumPrincipalWorldMinor,totalExposureCeilingWorldMinor,apr,maximumYears,reason,policyVersion,effectiveAt,null,0);}
        public ApplicationDecision{
            Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(application);Objects.requireNonNull(loan);Objects.requireNonNull(lender);Objects.requireNonNull(product);Objects.requireNonNull(currency);Objects.requireNonNull(primeApr);Objects.requireNonNull(fxPublication);Objects.requireNonNull(worldCurrency);Objects.requireNonNull(apr);Objects.requireNonNull(reason);
            validatePrimeBinding(product,primePolicyId,primePolicyRevision,primeVersion,primeApr);
            if(requestedMinor<=0||requestedMinor%currency.minorUnitsPerMajor()!=0||years<1||years>8||profileRevision<0||tier<1||tier>2||primeVersion<0
                ||currentExposureWorldMinor<0||maximumPrincipalWorldMinor<0||totalExposureCeilingWorldMinor<0||maximumYears<0||maximumYears>(product==CreditUnderwriting.Product.BANK_LOAN?5:8)||policyVersion!=1||effectiveAt<0
                ||!reason.matches("[A-Z][A-Z0-9_]{0,95}")||apr.signum()<0||apr.compareTo(BigDecimal.ONE)>0||apr.precision()>34||Math.abs(apr.scale())>18
                ||primeApr.signum()<0||primeApr.compareTo(new BigDecimal("0.5"))>0||primeApr.precision()>34||Math.abs(primeApr.scale())>6
                ||established&&(profileRevision<1||capturedScore<300||capturedScore>900)||!established&&(profileRevision!=0||capturedScore!=0))throw new IllegalArgumentException("Loan application decision fields");
            if(product==CreditUnderwriting.Product.SUBPRIME_LOAN&&(!lender.equals(InstitutionCatalog.IRONBRIDGE)||tier!=1||primeVersion!=0||primeApr.signum()!=0||currency!=worldCurrency))throw new IllegalArgumentException("Subprime application decision");
            if(product==CreditUnderwriting.Product.BANK_LOAN&&(!InstitutionCatalog.require(lender).customerBank()||primePolicyId==null&&primeVersion<1||currency!=InstitutionCatalog.require(lender).baseCurrency()))throw new IllegalArgumentException("Bank application decision");
        }
        public boolean approved(){return reason.equals("APPROVED");}
    }

    /** Captured trusted nongloan exposure includes cards; the engine adds its own loans exactly once. */
    public record Inputs(CreditUnderwriting.FxSnapshot fx,boolean complete,boolean creditBlocked,long otherExposureWorldMinor){
        public Inputs{Objects.requireNonNull(fx);if(otherExposureWorldMinor<0)throw new IllegalArgumentException("Loan exposure");}
    }
    @FunctionalInterface public interface InputSource {Inputs current(ReadView reads,PartyRef owner,long effective)throws Exception;}
    public record Application(BranchAdmission.Proof proof,UUID application,UUID loan,CreditUnderwriting.Product product,
            EconomyCurrency currency,long principalMinor,int years,AccountId destination,long destinationRevision,
            long profileRevision,long tierRevision,int primeVersion,UUID fxPublication,long calendarOrigin,RateMode rateMode,boolean secured,UUID primePolicyId,long primePolicyRevision){
        public Application(BranchAdmission.Proof proof,UUID application,UUID loan,CreditUnderwriting.Product product,
            EconomyCurrency currency,long principalMinor,int years,AccountId destination,long destinationRevision,
            long profileRevision,long tierRevision,int primeVersion,UUID fxPublication,long calendarOrigin,RateMode rateMode,boolean secured){this(proof,application,loan,product,currency,principalMinor,years,destination,destinationRevision,profileRevision,tierRevision,primeVersion,fxPublication,calendarOrigin,rateMode,secured,null,0);}
        public Application{
            Objects.requireNonNull(proof);Objects.requireNonNull(application);Objects.requireNonNull(loan);Objects.requireNonNull(product);Objects.requireNonNull(currency);Objects.requireNonNull(destination);Objects.requireNonNull(fxPublication);Objects.requireNonNull(rateMode);
            validatePrimeBinding(product,primePolicyId,primePolicyRevision,primeVersion,null);
            if(principalMinor<=0||years<1||years>8||destinationRevision<1||profileRevision<0||tierRevision<0||primeVersion<0||calendarOrigin<0)throw new IllegalArgumentException("Loan application fields");
        }
        public Journal.CanonicalRequest request(){var c=proof.context();var legacy=canonical(c.world(),c.actor(),Map.ofEntries(
            Map.entry("lease",proof.id().toString()),Map.entry("session",c.session().toString()),Map.entry("bank",c.bank().key()),Map.entry("application",application.toString()),Map.entry("loan",loan.toString()),Map.entry("product",product.name()),Map.entry("currency",currency.name()),Map.entry("principal",Long.toString(principalMinor)),Map.entry("years",Integer.toString(years)),Map.entry("destination",destination.value().toString()),Map.entry("destination_revision",Long.toString(destinationRevision)),Map.entry("profile_revision",Long.toString(profileRevision)),Map.entry("tier_revision",Long.toString(tierRevision)),Map.entry("prime_version",Integer.toString(primeVersion)),Map.entry("denomination_revision",Long.toString(c.denominationRevision())),Map.entry("publication",fxPublication.toString()),Map.entry("origin",Long.toString(calendarOrigin)),Map.entry("rate_mode",rateMode.name()),Map.entry("secured",Boolean.toString(secured))));if(primePolicyId==null)return legacy;var fields=new TreeMap<>(legacy.fields());fields.put("prime_policy_id",primePolicyId.toString());fields.put("prime_policy_revision",Long.toString(primePolicyRevision));return new Journal.CanonicalRequest(2,fields);}
    }
    public record DisbursementPlan(List<PostingBatch.Leg> legs,List<FinancialAccounts.Account> accounts,List<Journal.Record> facts,Projection projection){
        public DisbursementPlan{legs=List.copyOf(legs);accounts=List.copyOf(accounts);facts=List.copyOf(facts);Objects.requireNonNull(projection);}
    }
    @FunctionalInterface public interface Disbursement {DisbursementPlan prepare(Journal.Intent intent,ReadView reads,Application request,InstitutionCatalog.InstitutionId lender,long now)throws Exception;}
    private static void validatePrimeBinding(CreditUnderwriting.Product product,UUID id,long revision,int legacyVersion,BigDecimal apr) {
        if(id==null ? revision!=0 : revision<1||product!=CreditUnderwriting.Product.BANK_LOAN||legacyVersion!=0
                ||apr!=null&&(apr.signum()<0||apr.compareTo(new BigDecimal("0.25"))>0))throw new IllegalArgumentException("Captured currency prime identity");
    }
    public static boolean matchesPrime(Application request,CurrencyPrimes.Selection selection) {
        return selection.currencySpecific()?request.primeVersion()==0&&selection.id().equals(request.primePolicyId())&&selection.revision()==request.primePolicyRevision()
                :request.primePolicyId()==null&&request.primePolicyRevision()==0&&request.primeVersion()==selection.legacyVersion();
    }
    private Loans() {}
    public static Registration application(PartyRef owner,Application request,BankProviders.TierEligibility tiers,InputSource inputs,LongSupplier clock){return application(owner,request,tiers,inputs,clock,null);}
    public static Registration application(PartyRef owner,Application request,BankProviders.TierEligibility tiers,InputSource inputs,LongSupplier clock,Disbursement disbursement){
        Objects.requireNonNull(tiers);Objects.requireNonNull(inputs);Objects.requireNonNull(clock);
        return new Registration(request.request().version(),(intent,reads)->{
            try{return prepareApplication(owner,request,tiers,inputs,clock.getAsLong(),intent,reads,disbursement);}
            catch(ArithmeticException overflow){return reject("LOAN_OVERFLOW");}
        });
    }
    private static Decision prepareApplication(PartyRef owner,Application request,BankProviders.TierEligibility tiers,InputSource inputs,long now,Journal.Intent intent,ReadView reads,Disbursement disbursement)throws Exception{
        var context=request.proof().context();
        if(!matches(owner,intent,APPLY,request.request())||!owns(owner,context.world(),context.actor())||!context.action().name().equals("LOAN_APPLY"))return reject("LOAN_REQUEST");
        if(request.rateMode()!=RateMode.FIXED)return reject("VARIABLE_LOAN_UNSUPPORTED");if(request.secured())return reject("SECURED_LOAN_UNSUPPORTED");
        if(!request.proof().valid(intent,reads))return decline("BRANCH_UNAVAILABLE");
        if(!reads.query("SELECT lease_id FROM bank_provider_leases WHERE lease_id=?",List.of(request.proof().id().toString()),1).isEmpty())return reject("LEASE_USED");
        if(!reads.query("SELECT application_id FROM loan_application_ids WHERE world_id=? AND application_id=?",List.of(owner.world().value().toString(),request.application().toString()),1).isEmpty())return reject("LOAN_APPLICATION_REUSED");
        if(find(reads,request.loan()).isPresent())return reject("LOAN_EXISTS");
        var denomination=RebaseSettlement.denomination(reads).orElse(null);if(denomination==null||denomination.revision()!=context.denominationRevision())return reject("DENOMINATION_STALE");
        if(origin(reads)!=request.calendarOrigin()||now<request.calendarOrigin())return decline("LOAN_CALENDAR_UNAVAILABLE");
        var kiosk=InstitutionCatalog.require(context.bank());boolean subprime=request.product()==CreditUnderwriting.Product.SUBPRIME_LOAN;
        if(!kiosk.customerBank()||!enabled(reads,owner.world(),kiosk.id()))return reject("LOAN_KIOSK_ISSUER");
        if(subprime&&!kiosk.tier1())return reject("SUBPRIME_KIOSK_UNAVAILABLE");
        var lender=subprime?InstitutionCatalog.IRONBRIDGE:kiosk.id();
        if(!enabled(reads,owner.world(),lender))return reject("LOAN_LENDER_DISABLED");
        if(request.currency()!=(subprime?denomination.currency():kiosk.baseCurrency()))return reject("LOAN_CONTRACT_CURRENCY");
        var destination=AccountCatalog.find(reads,owner,request.destination()).orElse(null);
        if(destination==null||destination.status()!=AccountIdentity.Status.OPEN||destination.purpose()!=AccountIdentity.Purpose.BANK||destination.revision()!=request.destinationRevision())return reject("LOAN_DESTINATION_STALE");
        if(destination.currency()!=request.currency()&&disbursement==null)return reject("LOAN_EXPLICIT_FX_REQUIRED");
        var tier=tiers.current(reads,owner);if(kiosk.tier2()&&((tier.available()&&tier.revision()!=request.tierRevision())||(!tier.available()&&request.tierRevision()>0)))return reject("LOAN_TIER_STALE");
        boolean tierTwo=!subprime&&kiosk.tier2()&&tier.available()&&tier.eligible();
        if(!subprime&&!kiosk.tier1()&&!tierTwo)return reject("TIER2_UNAVAILABLE");
        var profile=CreditProfiles.find(reads,owner).orElse(null);if((profile==null?0:profile.revision())!=request.profileRevision())return reject("LOAN_PROFILE_STALE");
        var source=inputs.current(reads,owner,now);if(source==null||!source.complete()||!source.fx().available()||!source.fx().publication().equals(request.fxPublication()))return decline("LOAN_INPUTS_UNAVAILABLE");
        if(reads.query("SELECT state FROM institution_capital_manifest WHERE world_id=? AND state='ACTIVE'",List.of(owner.world().value().toString()),1).isEmpty())return decline("LOAN_CAPITAL_UNAVAILABLE");
        CurrencyPrimes.Selection prime=null;if(!subprime){try{prime=CurrencyPrimes.select(reads,owner.world(),request.currency(),now);}catch(IllegalStateException|IllegalArgumentException unavailable){return decline("LOAN_PRIME_UNAVAILABLE");}if(!matchesPrime(request,prime))return reject("LOAN_PRIME_STALE");}
        else if(request.primeVersion()!=0||request.primePolicyId()!=null)return reject("SUBPRIME_PRIME_UNEXPECTED");
        long exposure;boolean blocked;
        try{exposure=Math.addExact(source.otherExposureWorldMinor(),exposure(reads,owner,denomination.currency(),source.fx(),now));blocked=source.creditBlocked()||blocked(reads,owner,now);}
        catch(IllegalStateException|IllegalArgumentException unavailable){return decline("LOAN_EXPOSURE_UNAVAILABLE");}
        var terms=new CreditUnderwriting.LoanRequest(profile!=null,profile==null?650:profile.score(),denomination.currency(),request.product(),tierTwo,tier.available()&&tier.eligible(),true,blocked,exposure,request.principalMinor(),request.years(),prime==null?BigDecimal.ZERO:prime.primeApr());
        CreditUnderwriting.NativeLoanOffer offer;
        try{offer=CreditUnderwriting.loanForFacility(terms,new Money(request.currency(),request.principalMinor()),source.fx());}catch(IllegalArgumentException invalid){return reject("LOAN_AMOUNT_INVALID");}
        if(!offer.worldOffer().available())return decline(offer.worldOffer().reason());
        String reason=offer.worldOffer().approved()?"APPROVED":offer.worldOffer().reason();
        Commit funding=null;
        if(reason.equals("APPROVED")){
            if(DepositYield.distressed(reads,lender))reason="ISSUER_YIELD_DISTRESSED";
            else{var decision=LoanTreasury.prepare(reads,owner.world(),lender,request.currency(),request.principalMinor(),now);if(decision instanceof Commit c)funding=c;else reason=decision instanceof Reject r?r.result().code():"LOAN_CAPITAL_UNAVAILABLE";}
        }
        final String decision=reason;final Commit funded=funding;final var capturedPrime=prime;final int serviceTier=tierTwo?2:1;
        var inquiry=new CreditProfiles.InquiryRequest(owner.world(),context.actor(),lender,request.application(),CreditProfiles.ApplicationKind.LOAN,now,request.calendarOrigin(),false);
        return CreditProfiles.acceptedInquiry(owner,inquiry,()->now,(i,r)->{
            Commit result;
            if(!decision.equals("APPROVED"))result=new Commit(List.of(event("APPLICATION_DENIED",request.loan(),now,decision)),new Journal.Result(1,"LOAN_APPLICATION_DENIED",decision),tx->{});
            else{var actual=originate(owner,request,lender,serviceTier,profile,offer.worldOffer(),capturedPrime,denomination.currency(),now,intent,reads,funded,disbursement);if(!(actual instanceof Commit accepted))return actual;result=accepted;}
            var captured=new ApplicationDecision(owner.world(),context.actor(),request.application(),request.loan(),lender,request.product(),request.currency(),request.principalMinor(),request.years(),profile!=null,profile==null?0:profile.score(),profile==null?0:profile.revision(),serviceTier,capturedPrime==null?0:capturedPrime.legacyVersion(),capturedPrime==null?BigDecimal.ZERO:capturedPrime.primeApr(),request.fxPublication(),denomination.currency(),exposure,offer.worldOffer().maximumPrincipal().minorUnits(),offer.worldOffer().totalExposureCeiling().minorUnits(),offer.worldOffer().apr(),offer.worldOffer().maximumYears(),decision,VERSION,now,capturedPrime!=null&&capturedPrime.currencySpecific()?capturedPrime.id():null,capturedPrime!=null&&capturedPrime.currencySpecific()?capturedPrime.revision():0);
            var records=new ArrayList<>(result.records());records.add(new Journal.Record(APPLICATION_FACT,captured.primePolicyId()==null?1:2,encode(captured)));
            if(records.size()>61)return reject("LOAN_BATCH_LIMIT");
            return new Commit(records,result.result(),tx->{result.projections().apply(tx);tx.execute("INSERT INTO loan_application_ids VALUES(?,?,?)",List.of(owner.world().value().toString(),request.application().toString(),intent.operationId().value().toString()));
                if(!decision.equals("APPROVED"))tx.execute("INSERT INTO bank_provider_leases VALUES(?,?,?)",List.of(request.proof().id().toString(),intent.operationId().value().toString(),intent.canonical()));});
        }).handler().prepare(adapt(intent,CreditProfiles.INQUIRY,inquiry.request()),reads);
    }
    private static Decision originate(PartyRef owner,Application request,InstitutionCatalog.InstitutionId lender,int tier,CreditProfiles.Profile profile,CreditUnderwriting.LoanOffer offer,CurrencyPrimes.Selection prime,EconomyCurrency worldCurrency,long now,Journal.Intent intent,ReadView reads,Commit funding,Disbursement disbursement)throws Exception{
        var schedule=LoanServicing.schedule(request.currency(),request.principalMinor(),offer.apr(),request.years(),request.product()==CreditUnderwriting.Product.SUBPRIME_LOAN);
        var state=LoanServicing.initial(request.currency(),request.principalMinor(),offer.apr(),request.years(),request.product()==CreditUnderwriting.Product.SUBPRIME_LOAN,now);
        var contract=new Contract(owner.world(),request.loan(),((PartyRef.Player)owner.owner()).id(),lender,request.product(),request.currency(),request.principalMinor(),request.years(),offer.apr(),prime==null?BigDecimal.ZERO:prime.primeApr(),prime==null?0:prime.legacyVersion(),profile.score(),profile.revision(),tier,VERSION,request.proof().context().denominationRevision(),request.fxPublication(),worldCurrency,now,scheduleHash(schedule),1,Status.ACTIVE,state,prime!=null&&prime.currencySpecific()?prime.id():null,prime!=null&&prime.currencySpecific()?prime.revision():0);
        var reserve=financial(contract,FinancialAccounts.Role.RESERVATION);var receivable=financial(contract,FinancialAccounts.Role.RECEIVABLE);var liability=borrower(contract,FinancialAccounts.Role.LIABILITY);
        var accounts=new ArrayList<>(List.of(reserve,receivable,liability));
        var overlay=FeeSettlement.overlay(reads,funding.records());
        var plan=disbursement==null?new DisbursementPlan(List.of(leg(reserve,-request.principalMinor()),customer(request.destination(),request.currency(),request.principalMinor())),List.of(),List.of(),tx->{}):disbursement.prepare(intent,overlay,request,lender,now);
        if(plan==null)throw new IllegalArgumentException("Explicit loan disbursement unavailable");
        accounts.addAll(plan.accounts());var legs=new ArrayList<>(plan.legs());legs.add(leg(receivable,request.principalMinor()));legs.add(leg(liability,-request.principalMinor()));
        var posted=InstitutionSettlement.prepare(overlay,legs,accounts);
        if(!(posted instanceof Commit money))return posted;
        var records=new ArrayList<>(funding.records());records.addAll(accountFacts(reads,contract,accounts));records.addAll(money.records());records.addAll(plan.facts());records.add(fact(contract));records.add(event("ORIGINATED",contract.id(),now,request.application()+"|"+request.principalMinor()+"|"+schedule.installmentMinor()));
        return new Commit(records,new Journal.Result(1,"LOAN_ORIGINATED",contract.id().toString()),tx->{
            funding.projections().apply(tx);registerAccounts(tx,contract,accounts);money.projections().apply(tx);plan.projection().apply(tx);write(tx,contract);
            writeSchedule(tx,contract,schedule);
            tx.execute("INSERT INTO bank_provider_leases VALUES(?,?,?)",List.of(request.proof().id().toString(),intent.operationId().value().toString(),intent.canonical()));
        });
    }
    /** One original month boundary; prior periods must have completed before this call. */
    public static Decision month(ReadView reads,UUID id,long effective)throws Exception{
        var loan=find(reads,id).orElse(null);if(loan==null||loan.status()!=Status.ACTIVE||effective!=loan.nextMonth())return reject("LOAN_MONTH_STALE");
        String requirement=stable("installment|"+loan.world().value()+"|"+id+"|"+effective).toString();
        var close=LoanServicing.monthClose(loan.servicing(),requirement,effective);
        if(close.state().requirements().size()>MAX_REQUIREMENTS)return decline("LOAN_OBLIGATION_BOUND");
        var next=change(loan,close.state());long recognized=close.postedInterestMinor();
        var accounts=List.of(financial(loan,FinancialAccounts.Role.RECEIVABLE),financial(loan,FinancialAccounts.Role.INCOME),borrower(loan,FinancialAccounts.Role.EXPENSE),borrower(loan,FinancialAccounts.Role.LIABILITY));
        Commit money=null;
        if(recognized!=0){var decision=InstitutionSettlement.prepare(reads,List.of(leg(accounts.get(0),recognized),leg(accounts.get(1),-recognized),leg(accounts.get(2),recognized),leg(accounts.get(3),-recognized)),accounts);if(!(decision instanceof Commit c))return decision;money=c;}
        var records=new ArrayList<Journal.Record>();if(money!=null)records.addAll(money.records());records.addAll(accountFacts(reads,loan,accounts));records.add(fact(next));
        var installment=new InstallmentSnapshot(loan.world(),loan.id(),loan.owner(),requirement,close.state().completedPeriods(),effective,close.requirement()==null?0:close.requirement().originalMinor(),close.earnedMajor(),recognized,loan.servicing().installmentMinor(),next.servicing().debtMinor());
        records.add(new Journal.Record(INSTALLMENT_FACT,1,encode(installment)));records.add(event("MONTH_CLOSED",id,effective,recognized+"|"+close.earnedMajor().toPlainString()));var committed=money;
        return new Commit(records,new Journal.Result(1,"LOAN_MONTH_CLOSED",requirement),tx->{registerAccounts(tx,loan,accounts);if(committed!=null)committed.projections().apply(tx);write(tx,next);tx.execute("INSERT INTO loan_installments VALUES(?,?,?,?,?,?,?,?)",List.of(loan.world().value().toString(),id.toString(),requirement,loan.owner().value().toString(),Integer.toString(installment.ordinal()),Long.toString(effective),Long.toString(installment.originalMinor()),encode(installment)));});
    }
    public record RepaymentRequest(WorldId world,PlayerId owner,UUID loan,AccountId funding,long amountMinor,long loanRevision,long fundingRevision,long fundingLedgerRevision,UUID quote,long capturedAt,long expiresAt,boolean fullPayoff){
        public RepaymentRequest{Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(loan);Objects.requireNonNull(funding);Objects.requireNonNull(quote);if(amountMinor<=0||loanRevision<1||fundingRevision<1||fundingLedgerRevision<0||capturedAt<0||expiresAt<=capturedAt||expiresAt>Math.addExact(capturedAt,30000))throw new IllegalArgumentException("Loan payment quote");}
        public Journal.CanonicalRequest request(){return canonical(world,owner,Map.ofEntries(Map.entry("loan",loan.toString()),Map.entry("funding",funding.value().toString()),Map.entry("amount",Long.toString(amountMinor)),Map.entry("loan_revision",Long.toString(loanRevision)),Map.entry("funding_revision",Long.toString(fundingRevision)),Map.entry("funding_ledger_revision",Long.toString(fundingLedgerRevision)),Map.entry("quote",quote.toString()),Map.entry("captured_at",Long.toString(capturedAt)),Map.entry("expires_at",Long.toString(expiresAt)),Map.entry("full_payoff",Boolean.toString(fullPayoff))));}
    }
    public record PaymentQuote(RepaymentRequest request,LoanServicing.Payoff payoff){
        public PaymentQuote{Objects.requireNonNull(request);if(request.fullPayoff()!=(payoff!=null))throw new IllegalArgumentException("Loan payoff quote");}
    }
    /** Capture on the storage worker from current owned funding and the authoritative loan state. */
    public static PaymentQuote quote(ReadView reads,PartyRef owner,UUID id,AccountId funding,long amount,boolean fullPayoff,long now){
        var loan=find(reads,id).filter(l->l.party().equals(owner)).orElseThrow(()->new IllegalArgumentException("Loan unavailable"));
        if(loan.status()!=Status.ACTIVE||now<loan.servicing().lastAccrued()||now>=loan.nextMonth())throw new IllegalArgumentException("Loan servicing boundary");
        var account=AccountCatalog.find(reads,owner,funding).orElseThrow(()->new IllegalArgumentException("Funding unavailable"));
        if(account.status()!=AccountIdentity.Status.OPEN||account.purpose()!=AccountIdentity.Purpose.BANK)throw new IllegalArgumentException("Funding unavailable");
        var payoff=fullPayoff?LoanServicing.payoff(loan.servicing(),now):null;
        long actual=fullPayoff?payoff.amountMinor():amount;
        if(actual<=0)throw new IllegalArgumentException("Loan payment amount");
        long expires=Math.min(Math.addExact(now,30000),loan.nextMonth());
        var cards=CreditFacilities.owned(reads,owner);if(cards.size()>128)throw new IllegalArgumentException("Credit boundary input unavailable");
        for(var card:cards)if(card.status()==CreditFacilities.Status.ACTIVE){expires=Math.min(expires,Math.min(card.nextDay(),card.nextStatement()));}
        var otherLoans=owned(reads,owner);if(otherLoans.size()>MAX_LIVE)throw new IllegalArgumentException("Loan boundary input unavailable");
        for(var other:otherLoans)if(other.status()==Status.ACTIVE)expires=Math.min(expires,other.nextMonth());
        if(expires<=now)throw new IllegalArgumentException("Scheduled credit catch-up required");
        return new PaymentQuote(new RepaymentRequest(owner.world(),((PartyRef.Player)owner.owner()).id(),id,funding,actual,loan.revision(),account.revision(),WealthValuation.ledgerRevision(reads,funding.value()),UUID.randomUUID(),now,expires,fullPayoff),payoff);
    }
    public static Registration repayment(PartyRef owner,PaymentQuote quote,LongSupplier clock){
        Objects.requireNonNull(clock);return new Registration(1,(intent,reads)->{
            var request=quote.request();if(!matches(owner,intent,REPAY,request.request())||!owns(owner,request.world(),request.owner()))return reject("LOAN_REPAY_REQUEST");
            long now=clock.getAsLong();var loan=validatePayment(reads,owner,quote,now);if(loan==null)return reject("LOAN_PAYMENT_STALE");
            var funding=AccountCatalog.find(reads,owner,request.funding()).orElse(null);if(funding==null||funding.currency()!=loan.currency())return decline("LOAN_EXPLICIT_FX_REQUIRED");
            return repay(reads,loan,quote,now,List.of(customer(funding.id(),loan.currency(),-request.amountMinor())),List.of());
        });
    }
    /** Validate the protected owner/funding/revision quote for trusted FX composition as well. */
    public static Contract validatePayment(ReadView reads,PartyRef owner,PaymentQuote quote,long now){
        var request=quote.request();if(!owns(owner,request.world(),request.owner())||now<request.capturedAt()||now>=request.expiresAt())return null;
        var loan=find(reads,request.loan()).filter(l->l.party().equals(owner)).orElse(null);
        if(loan==null||loan.status()!=Status.ACTIVE||loan.revision()!=request.loanRevision()||now>=loan.nextMonth()||now<loan.servicing().lastAccrued())return null;
        if(quote.payoff()!=null&&(!quote.payoff().source().equals(loan.servicing())||quote.payoff().capturedAt()!=request.capturedAt()||quote.payoff().amountMinor()!=request.amountMinor()))return null;
        var account=AccountCatalog.find(reads,owner,request.funding()).orElse(null);
        if(account==null||account.status()!=AccountIdentity.Status.OPEN||account.purpose()!=AccountIdentity.Purpose.BANK||account.revision()!=request.fundingRevision()||WealthValuation.ledgerRevision(reads,account.id().value())!=request.fundingLedgerRevision())return null;
        return loan;
    }
    /** The funding legs provide exactly the contract-currency debit; fees are composed separately on a posting overlay. */
    public static Decision repay(ReadView reads,Contract loan,PaymentQuote quote,long now,List<PostingBatch.Leg> fundingLegs,List<FinancialAccounts.Account> planned)throws Exception{
        if(!find(reads,loan.id()).filter(loan::equals).isPresent()||loan.status()!=Status.ACTIVE)return reject("LOAN_PAYMENT_STALE");
        var request=quote.request();if(!loan.id().equals(request.loan())||loan.revision()!=request.loanRevision()||now<request.capturedAt()||now>=request.expiresAt()||now>=loan.nextMonth())return reject("LOAN_PAYMENT_STALE");
        if(!LoanAccounting.current(reads,loan))return decline("LOAN_ACCOUNTING_UNAVAILABLE");
        LoanServicing.Payment payment;
        try{payment=request.fullPayoff()?LoanServicing.settlePayoff(loan.servicing(),quote.payoff(),now):LoanServicing.repay(loan.servicing(),request.amountMinor(),now);}
        catch(IllegalArgumentException invalid){return reject("LOAN_FRESH_PAYOFF_REQUIRED");}
        var next=change(loan,payment.state());long recognize=payment.recognizedInterestMinor(),amount=request.amountMinor();
        var cash=financial(loan,FinancialAccounts.Role.CASH);var receive=financial(loan,FinancialAccounts.Role.RECEIVABLE);var income=financial(loan,FinancialAccounts.Role.INCOME);var expense=borrower(loan,FinancialAccounts.Role.EXPENSE);var liability=borrower(loan,FinancialAccounts.Role.LIABILITY);
        var accounts=new ArrayList<>(planned);accounts.addAll(List.of(cash,receive,income,expense,liability));
        var legs=new ArrayList<>(fundingLegs);
        if(recognize!=0){legs.add(leg(receive,recognize));legs.add(leg(income,-recognize));legs.add(leg(expense,recognize));legs.add(leg(liability,-recognize));}
        legs.add(leg(cash,amount));legs.add(leg(receive,-amount));legs.add(leg(liability,amount));
        var decision=InstitutionSettlement.prepare(reads,legs,accounts);if(!(decision instanceof Commit money))return decision;
        var records=new ArrayList<>(money.records());records.addAll(accountFacts(reads,loan,accounts));records.add(fact(next));
        records.add(event("PAYMENT",loan.id(),now,String.join("|",Long.toString(amount),Long.toString(payment.principalPaid()),Long.toString(payment.interestPaid()),Long.toString(recognize),payment.residualDisposition().toPlainString(),payment.additionalInterestWaived().toPlainString(),allocations(payment.allocations()))));
        return new Commit(records,new Journal.Result(1,next.status()==Status.SETTLED?"LOAN_SETTLED":"LOAN_REPAID",loan.id().toString()),tx->{registerAccounts(tx,loan,accounts);money.projections().apply(tx);write(tx,next);});
    }
    public record ScheduleRow(int ordinal,long dueAt,long paymentMinor,long principalMinor,long interestMinor,long remainingPrincipalMinor) {}
    public record InstallmentSnapshot(WorldId world,UUID loan,PlayerId owner,String id,int ordinal,long dueAt,long originalMinor,BigDecimal earnedMajor,long postedInterestMinor,long contractualInstallmentMinor,long debtMinor) {}
    public record InstallmentPage(List<InstallmentSnapshot> installments,Integer nextOrdinal){public InstallmentPage{installments=List.copyOf(installments);}}
    /** Owner-bound lookup uses the original outcome sequence, never an all-history application scan. */
    public static Optional<ApplicationDecision> applicationDecision(ReadView reads,PartyRef owner,UUID application){
        if(!(owner.owner() instanceof PartyRef.Player player))return Optional.empty();
        var outcomes=reads.query("SELECT o.sequence FROM loan_application_ids a JOIN operation_outcomes o ON o.world_id=a.world_id AND o.operation_id=a.operation_id WHERE a.world_id=? AND a.application_id=? AND o.actor=? AND o.status='COMMITTED' LIMIT 1",List.of(owner.world().value().toString(),application.toString(),Journal.actorKey(player.id())),1);
        if(outcomes.isEmpty())return Optional.empty();
        var rows=reads.query("SELECT record_version,payload FROM journal_records WHERE sequence=? AND record_type=? LIMIT 1",List.of(outcomes.getFirst().get("sequence"),APPLICATION_FACT),1);
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();int factVersion=Integer.parseInt(row.get("record_version"));if(factVersion!=1&&factVersion!=2)throw new IllegalArgumentException("Unsupported loan application decision version");
        var decision=decodeApplicationDecision(row.get("payload"),factVersion);
        if(!decision.world().equals(owner.world())||!decision.owner().equals(player.id())||!decision.application().equals(application))throw new IllegalArgumentException("Application decision owner binding");return Optional.of(decision);
    }

    public static Optional<Contract> find(ReadView reads,UUID id){return reads.query("SELECT * FROM loans WHERE loan_id=?",List.of(id.toString()),1).stream().map(Loans::read).findFirst();}
    public static List<Contract> owned(ReadView reads,PartyRef owner){if(!(owner.owner() instanceof PartyRef.Player p))return List.of();return reads.query("SELECT * FROM loans WHERE world_id=? AND owner_id=? AND status<>'SETTLED' ORDER BY loan_id LIMIT 129",List.of(owner.world().value().toString(),p.id().value().toString()),129).stream().map(Loans::read).toList();}
    public static List<Contract> history(ReadView reads,PartyRef owner,UUID after,int limit){bounds(limit);if(!(owner.owner() instanceof PartyRef.Player p))return List.of();return reads.query("SELECT * FROM loans WHERE world_id=? AND owner_id=? AND loan_id>? ORDER BY loan_id LIMIT ?",List.of(owner.world().value().toString(),p.id().value().toString(),after==null?"":after.toString(),Integer.toString(limit)),limit).stream().map(Loans::read).toList();}
    public static List<ScheduleRow> schedule(ReadView reads,PartyRef owner,UUID id,int after,int limit){bounds(limit);if(after<0)throw new IllegalArgumentException("Schedule cursor");var loan=find(reads,id).filter(l->l.party().equals(owner)).orElse(null);if(loan==null)return List.of();return reads.query("SELECT * FROM loan_schedule WHERE world_id=? AND loan_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",List.of(owner.world().value().toString(),id.toString(),Integer.toString(after),Integer.toString(limit)),limit).stream().map(r->new ScheduleRow(Integer.parseInt(r.get("ordinal")),number(r.get("due_at")),number(r.get("payment_minor")),number(r.get("principal_minor")),number(r.get("interest_minor")),number(r.get("balance_minor")))).toList();}
    public static InstallmentPage installments(ReadView reads,PartyRef owner,UUID id,int after,int limit){bounds(limit);if(after<0)throw new IllegalArgumentException("Installment cursor");var loan=find(reads,id).filter(l->l.party().equals(owner)).orElse(null);if(loan==null)return new InstallmentPage(List.of(),null);var rows=reads.query("SELECT body FROM loan_installments WHERE world_id=? AND loan_id=? AND owner_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",List.of(owner.world().value().toString(),id.toString(),loan.owner().value().toString(),Integer.toString(after),Integer.toString(limit+1)),limit+1);var items=rows.stream().limit(limit).map(r->decodeInstallment(r.get("body"))).toList();for(var item:items)if(!item.world().equals(loan.world())||!item.loan().equals(id)||!item.owner().equals(loan.owner()))throw new IllegalArgumentException("Installment owner binding");return new InstallmentPage(items,rows.size()>limit?items.getLast().ordinal():null);}
    public static long exposure(ReadView reads,PartyRef owner,EconomyCurrency worldCurrency,CreditUnderwriting.FxSnapshot fx,long effective){
        var loans=owned(reads,owner);if(loans.size()>MAX_LIVE)throw new IllegalStateException("Loan exposure bound");long total=0;
        for(var loan:loans){if(loan.status()==Status.SETTLED)continue;if(loan.status()==Status.ACTIVE&&effective>=loan.nextMonth())throw new IllegalStateException("Loan catch-up required");
            var state=loan.status()==Status.ACTIVE?LoanServicing.accrue(loan.servicing(),effective):loan.servicing();
            long nativeMinor=state.exactDebtMajor().multiply(BigDecimal.valueOf(loan.currency().minorUnitsPerMajor()),LoanServicing.MC).setScale(0,RoundingMode.CEILING).longValueExact();
            total=Math.addExact(total,CreditUnderwriting.exposureToWorld(new Money(loan.currency(),nativeMinor),worldCurrency,fx).minorUnits());
        }return total;
    }
    public static boolean blocked(ReadView reads,PartyRef owner,long effective){var loans=owned(reads,owner);if(loans.size()>MAX_LIVE)throw new IllegalStateException("Loan exposure bound");return loans.stream().anyMatch(l->l.status()==Status.DEFAULTED&&ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.blocks(reads,ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind.LOAN,l.id(),effective)||l.servicing().requirements().stream().anyMatch(r->r.unmetMinor()>0&&r.dueAt()<=effective));}
    private static Contract change(Contract loan,LoanServicing.State state){return new Contract(loan.world(),loan.id(),loan.owner(),loan.lender(),loan.product(),loan.currency(),loan.originalPrincipalMinor(),loan.years(),loan.apr(),loan.primeApr(),loan.primeVersion(),loan.capturedScore(),loan.profileRevision(),loan.tier(),loan.policyVersion(),loan.denominationRevision(),loan.fxPublication(),loan.worldCurrency(),loan.anchor(),loan.scheduleHash(),Math.addExact(loan.revision(),1),state.closed()?Status.SETTLED:loan.status(),state,loan.primePolicyId(),loan.primePolicyRevision());}
    private static FinancialAccounts.Account financial(Contract loan,FinancialAccounts.Role role){return FinancialAccounts.account(loan.world(),loan.lender(),role,loan.currency());}
    public static InstitutionCatalog.InstitutionId borrower(PlayerId owner){return new InstitutionCatalog.InstitutionId("economy:loan-borrower/"+owner.value());}
    private static FinancialAccounts.Account borrower(Contract loan,FinancialAccounts.Role role){return FinancialAccounts.account(loan.world(),borrower(loan.owner()),role,loan.currency());}
    private static List<Journal.Record> accountFacts(ReadView reads,Contract loan,List<FinancialAccounts.Account> accounts){
        var records=new ArrayList<Journal.Record>();
        if(reads.query("SELECT institution_key FROM institution_parties WHERE world_id=? AND institution_key=?",List.of(loan.world().value().toString(),borrower(loan.owner()).key()),1).isEmpty())records.add(new Journal.Record(BORROWER_FACT,1,loan.world().value()+"|"+loan.owner().value()+"|"+new InstitutionCatalog.SystemActor(loan.world(),borrower(loan.owner())).actorId().value()+"|"+loan.currency()));
        for(var a:accounts)if(FinancialAccounts.find(reads,a.id()).isEmpty())records.add(new Journal.Record(InstitutionCapital.ACCOUNT,1,a.id()+"|"+loan.world().value()+"|"+a.owner().key()+"|"+a.role()+"|"+a.currency()));
        return records;
    }
    private static void registerAccounts(FoundationStore.Transaction tx,Contract loan,List<FinancialAccounts.Account> accounts){
        var borrower=borrower(loan.owner());tx.execute("INSERT OR IGNORE INTO institution_parties VALUES(?,?,?,?,?,'INTERNAL',1)",List.of(loan.world().value().toString(),borrower.key(),new InstitutionCatalog.SystemActor(loan.world(),borrower).actorId().value().toString(),"Loan borrower claims",loan.currency().name()));
        for(var a:accounts)FinancialAccounts.register(tx,a);
    }
    private static PostingBatch.Leg leg(FinancialAccounts.Account a,long amount){return new PostingBatch.Leg(a.id(),a.role()==FinancialAccounts.Role.CASH?PostingBatch.Role.INSTITUTION_CASH:PostingBatch.Role.INSTITUTION_CLAIM,a.currency(),new Money(a.currency(),amount));}
    private static PostingBatch.Leg customer(AccountId id,EconomyCurrency currency,long amount){return new PostingBatch.Leg(id.value(),PostingBatch.Role.ACCOUNT,currency,new Money(currency,amount));}
    public static Journal.Record fact(Contract loan){return new Journal.Record(FACT,loan.primePolicyId()==null?1:2,encode(loan));}
    private static Journal.Record event(String kind,UUID loan,long effective,String details){return new Journal.Record(EVENT_FACT,1,pack(List.of(kind,loan.toString(),Long.toString(effective),details)));}
    private static String allocations(List<LoanServicing.Allocation> allocations){return String.join("\n",allocations.stream().map(a->a.requirementId()+","+a.interest()+","+a.minor()).toList());}
    private static List<String> values(Contract loan){return List.of(loan.world().value().toString(),loan.id().toString(),loan.owner().value().toString(),loan.lender().key(),loan.product().name(),loan.currency().name(),loan.status().name(),Long.toString(loan.owedMinor()),Long.toString(loan.nextMonth()),Long.toString(loan.revision()),encode(loan));}
    public static void write(FoundationStore.Transaction tx,Contract loan){tx.execute("INSERT INTO loans VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world_id,loan_id) DO UPDATE SET status=excluded.status,debt_minor=excluded.debt_minor,next_month=excluded.next_month,revision=excluded.revision,body=excluded.body",values(loan));}
    private static Contract read(Map<String,String> row){var loan=decode(row.get("body"));
        if(!row.get("world_id").equals(loan.world().value().toString())||!row.get("loan_id").equals(loan.id().toString())||!row.get("owner_id").equals(loan.owner().value().toString())||!row.get("lender").equals(loan.lender().key())||!row.get("product").equals(loan.product().name())||!row.get("currency").equals(loan.currency().name())||!row.get("status").equals(loan.status().name())||number(row.get("debt_minor"))!=loan.owedMinor()||number(row.get("next_month"))!=loan.nextMonth()||number(row.get("revision"))!=loan.revision())throw new IllegalArgumentException("Loan projection binding");return loan;
    }
    private static String encode(Contract l){String legacy=pack(List.of(l.world().value().toString(),l.id().toString(),l.owner().value().toString(),l.lender().key(),l.product().name(),l.currency().name(),Long.toString(l.originalPrincipalMinor()),Integer.toString(l.years()),l.apr().toPlainString(),l.primeApr().toPlainString(),Integer.toString(l.primeVersion()),Integer.toString(l.capturedScore()),Long.toString(l.profileRevision()),Integer.toString(l.tier()),Integer.toString(l.policyVersion()),Long.toString(l.denominationRevision()),l.fxPublication().toString(),l.worldCurrency().name(),Long.toString(l.anchor()),l.scheduleHash(),Long.toString(l.revision()),l.status().name(),encode(l.servicing())));return extendPrime(legacy,l.primePolicyId(),l.primePolicyRevision());}
    public static Contract decode(String payload){return decode(payload,primeFormat(payload,23));}
    public static Contract decode(String payload,int version){var p=unpack(payload,version==1?23:version==2?25:-1);var result=new Contract(new WorldId(UUID.fromString(p.get(0))),UUID.fromString(p.get(1)),new PlayerId(UUID.fromString(p.get(2))),new InstitutionCatalog.InstitutionId(p.get(3)),CreditUnderwriting.Product.valueOf(p.get(4)),EconomyCurrency.valueOf(p.get(5)),number(p.get(6)),Integer.parseInt(p.get(7)),new BigDecimal(p.get(8)),new BigDecimal(p.get(9)),Integer.parseInt(p.get(10)),Integer.parseInt(p.get(11)),number(p.get(12)),Integer.parseInt(p.get(13)),Integer.parseInt(p.get(14)),number(p.get(15)),UUID.fromString(p.get(16)),EconomyCurrency.valueOf(p.get(17)),number(p.get(18)),p.get(19),number(p.get(20)),Status.valueOf(p.get(21)),state(p.get(22)),version==2?UUID.fromString(p.get(23)):null,version==2?number(p.get(24)):0);if(!encode(result).equals(payload))throw new IllegalArgumentException("Loan canonical fields");return result;}
    private static String encode(LoanServicing.State s){return pack(List.of(s.currency().name(),s.apr().toPlainString(),Long.toString(s.principalMinor()),Long.toString(s.interestMinor()),s.residualMajor().toPlainString(),s.accruedMajor().toPlainString(),s.capitalizedInterestMajor().toPlainString(),Long.toString(s.periodStart()),Long.toString(s.lastAccrued()),Long.toString(s.installmentMinor()),Integer.toString(s.completedPeriods()),Integer.toString(s.totalPeriods()),Boolean.toString(s.closed()),requirements(s.requirements())));}
    private static LoanServicing.State state(String payload){var p=unpack(payload,14);if(!p.get(12).equals("true")&&!p.get(12).equals("false"))throw new IllegalArgumentException("Loan closed flag");return new LoanServicing.State(EconomyCurrency.valueOf(p.get(0)),new BigDecimal(p.get(1)),number(p.get(2)),number(p.get(3)),decimal(p.get(4)),decimal(p.get(5)),decimal(p.get(6)),number(p.get(7)),number(p.get(8)),number(p.get(9)),Integer.parseInt(p.get(10)),Integer.parseInt(p.get(11)),Boolean.parseBoolean(p.get(12)),requirements(p.get(13)));}
    private static String requirements(List<LoanServicing.Requirement> values){return String.join("\n",values.stream().map(r->String.join(",",r.id(),Long.toString(r.dueAt()),Long.toString(r.originalMinor()),Long.toString(r.unmetMinor()),Long.toString(r.principalMinor()),Long.toString(r.interestMinor()))).toList());}
    private static List<LoanServicing.Requirement> requirements(String text){if(text.isEmpty())return List.of();var result=new ArrayList<LoanServicing.Requirement>();for(var line:text.split("\n",-1)){var p=line.split(",",-1);if(p.length!=6)throw new IllegalArgumentException("Loan requirement fields");UUID.fromString(p[0]);result.add(new LoanServicing.Requirement(p[0],number(p[1]),number(p[2]),number(p[3]),number(p[4]),number(p[5])));}return result;}
    private static String encode(ApplicationDecision d){String legacy=pack(List.of(d.world().value().toString(),d.owner().value().toString(),d.application().toString(),d.loan().toString(),d.lender().key(),d.product().name(),d.currency().name(),Long.toString(d.requestedMinor()),Integer.toString(d.years()),Boolean.toString(d.established()),Integer.toString(d.capturedScore()),Long.toString(d.profileRevision()),Integer.toString(d.tier()),Integer.toString(d.primeVersion()),d.primeApr().toPlainString(),d.fxPublication().toString(),d.worldCurrency().name(),Long.toString(d.currentExposureWorldMinor()),Long.toString(d.maximumPrincipalWorldMinor()),Long.toString(d.totalExposureCeilingWorldMinor()),d.apr().toPlainString(),Integer.toString(d.maximumYears()),d.reason(),Integer.toString(d.policyVersion()),Long.toString(d.effectiveAt())));return extendPrime(legacy,d.primePolicyId(),d.primePolicyRevision());}
    public static ApplicationDecision decodeApplicationDecision(String payload){return decodeApplicationDecision(payload,primeFormat(payload,25));}
    public static ApplicationDecision decodeApplicationDecision(String payload,int version){
        var p=unpack(payload,version==1?25:version==2?27:-1);if(!p.get(9).equals("true")&&!p.get(9).equals("false"))throw new IllegalArgumentException("Application profile marker");
        var decision=new ApplicationDecision(new WorldId(UUID.fromString(p.get(0))),new PlayerId(UUID.fromString(p.get(1))),UUID.fromString(p.get(2)),UUID.fromString(p.get(3)),new InstitutionCatalog.InstitutionId(p.get(4)),CreditUnderwriting.Product.valueOf(p.get(5)),EconomyCurrency.valueOf(p.get(6)),number(p.get(7)),Integer.parseInt(p.get(8)),Boolean.parseBoolean(p.get(9)),Integer.parseInt(p.get(10)),number(p.get(11)),Integer.parseInt(p.get(12)),Integer.parseInt(p.get(13)),new BigDecimal(p.get(14)),UUID.fromString(p.get(15)),EconomyCurrency.valueOf(p.get(16)),number(p.get(17)),number(p.get(18)),number(p.get(19)),new BigDecimal(p.get(20)),Integer.parseInt(p.get(21)),p.get(22),Integer.parseInt(p.get(23)),number(p.get(24)),version==2?UUID.fromString(p.get(25)):null,version==2?number(p.get(26)):0);
        if(!encode(decision).equals(payload))throw new IllegalArgumentException("Application decision canonical encoding");return decision;
    }

    private static String encode(InstallmentSnapshot s){return pack(List.of(s.world().value().toString(),s.loan().toString(),s.owner().value().toString(),s.id(),Integer.toString(s.ordinal()),Long.toString(s.dueAt()),Long.toString(s.originalMinor()),s.earnedMajor().toPlainString(),Long.toString(s.postedInterestMinor()),Long.toString(s.contractualInstallmentMinor()),Long.toString(s.debtMinor())));}
    public static InstallmentSnapshot decodeInstallment(String payload){var p=unpack(payload,11);UUID.fromString(p.get(3));var s=new InstallmentSnapshot(new WorldId(UUID.fromString(p.get(0))),UUID.fromString(p.get(1)),new PlayerId(UUID.fromString(p.get(2))),p.get(3),Integer.parseInt(p.get(4)),number(p.get(5)),number(p.get(6)),decimal(p.get(7)),number(p.get(8)),number(p.get(9)),number(p.get(10)));if(s.ordinal()<1||s.dueAt()<0||s.originalMinor()<0||s.earnedMajor().signum()<0||s.contractualInstallmentMinor()<1||s.debtMinor()<s.originalMinor())throw new IllegalArgumentException("Loan installment fields");return s;}
    private static String scheduleHash(LoanServicing.Schedule schedule){var text=new StringBuilder("loan-schedule-v1|").append(schedule.initialMinor()).append('|').append(schedule.apr()).append('|').append(schedule.periods()).append('|').append(schedule.installmentMinor());for(var row:schedule.installments())text.append('|').append(row.ordinal()).append(',').append(row.principalMinor()).append(',').append(row.interestMinor()).append(',').append(row.paymentMinor()).append(',').append(row.remainingPrincipalMinor()).append(',').append(row.residualDisposition());return hash(text.toString());}
    private static void writeSchedule(FoundationStore.Transaction tx,Contract loan,LoanServicing.Schedule schedule){
        for(var row:schedule.installments())tx.execute("INSERT INTO loan_schedule VALUES(?,?,?,?,?,?,?,?)",List.of(
            loan.world().value().toString(),loan.id().toString(),Integer.toString(row.ordinal()),Long.toString(Math.addExact(loan.anchor(),Math.multiplyExact(row.ordinal(),MONTH))),
            Long.toString(row.paymentMinor()),Long.toString(row.principalMinor()),Long.toString(row.interestMinor()),Long.toString(row.remainingPrincipalMinor())));
    }
    private static String hash(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static int primeFormat(String payload,int legacyFields){if(payload==null)throw new IllegalArgumentException("Loan payload");int fields=payload.split("\\|",-1).length;if(fields==legacyFields)return 1;if(fields==legacyFields+2)return 2;throw new IllegalArgumentException("Loan record fields");}
    private static String extendPrime(String legacy,UUID id,long revision){return id==null?legacy:legacy+"|"+pack(List.of(id.toString(),Long.toString(revision)));}
    private static String pack(List<String> fields){return String.join("|",fields.stream().map(s->Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8))).toList());}
    private static List<String> unpack(String payload,int count){if(payload.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Loan record size");var p=payload.split("\\|",-1);if(p.length!=count)throw new IllegalArgumentException("Loan record fields");var values=Arrays.stream(p).map(s->new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8)).toList();if(!pack(values).equals(payload))throw new IllegalArgumentException("Loan record encoding");return values;}
    private static BigDecimal decimal(String text){var d=new BigDecimal(text);if(d.scale()!=18||d.precision()>50||!d.toPlainString().equals(text))throw new IllegalArgumentException("Loan decimal");return d;}
    private static long number(String text){long n=Long.parseLong(text);if(!Long.toString(n).equals(text))throw new IllegalArgumentException("Loan integer");return n;}
    private static UUID stable(String text){return UUID.nameUUIDFromBytes(("economy:loan:v1|"+text).getBytes(StandardCharsets.UTF_8));}
    private static Journal.CanonicalRequest canonical(WorldId world,PlayerId owner,Map<String,String> fields){var all=new TreeMap<>(fields);all.put("world",world.value().toString());all.put("economy:owner",Journal.actorKey(owner));return new Journal.CanonicalRequest(1,all);}
    private static Journal.Intent adapt(Journal.Intent i,Journal.Kind kind,Journal.CanonicalRequest request){return new Journal.Intent(i.operationId(),i.requestId(),i.actor(),kind,request,i.economicUpdateId(),i.correctionOf());}
    private static boolean matches(PartyRef owner,Journal.Intent intent,Journal.Kind kind,Journal.CanonicalRequest request){return owner.owner() instanceof PartyRef.Player p&&intent.actor().equals(p.id())&&intent.kind().equals(kind)&&intent.request().equals(request)&&owner.world().value().toString().equals(request.fields().get("world"));}
    private static boolean owns(PartyRef owner,WorldId world,PlayerId player){return owner.world().equals(world)&&owner.owner() instanceof PartyRef.Player p&&p.id().equals(player);}
    private static long origin(ReadView reads){var rows=reads.query("SELECT origin_ms FROM financial_calendar WHERE singleton=1",List.of(),1);return rows.isEmpty()?-1:number(rows.getFirst().get("origin_ms"));}
    private static boolean enabled(ReadView reads,WorldId world,InstitutionCatalog.InstitutionId bank){var rows=reads.query("SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",List.of(world.value().toString(),bank.key()),1);return rows.isEmpty()||rows.getFirst().get("enabled").equals("1");}
    private static void bounds(int limit){if(limit<1||limit>16)throw new IllegalArgumentException("Loan page bound");}
    private static Reject reject(String code){return new Reject(new Journal.Result(1,code,""));}
    private static Decline decline(String code){return new Decline(new Journal.Result(1,code,""));}
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(APPLICATION_FACT,1),payload->decodeApplicationDecision(payload,1)).with(new RecordCatalog.Key(APPLICATION_FACT,2),payload->decodeApplicationDecision(payload,2)).with(new RecordCatalog.Key(FACT,1),payload->validateFact(payload,1)).with(new RecordCatalog.Key(FACT,2),payload->validateFact(payload,2)).with(new RecordCatalog.Key(INSTALLMENT_FACT,1),Loans::decodeInstallment).with(new RecordCatalog.Key(EVENT_FACT,1),Loans::validateEvent).with(new RecordCatalog.Key(BORROWER_FACT,1),Loans::validateBorrower);}
    public static void validateFact(String payload){validateFact(payload,primeFormat(payload,23));}
    public static void validateFact(String payload,int version){var loan=decode(payload,version);if(!encode(loan).equals(payload))throw new IllegalArgumentException("Loan canonical fields");var schedule=LoanServicing.schedule(loan.currency(),loan.originalPrincipalMinor(),loan.apr(),loan.years(),loan.product()==CreditUnderwriting.Product.SUBPRIME_LOAN);if(!loan.scheduleHash().equals(scheduleHash(schedule))||schedule.installmentMinor()!=loan.servicing().installmentMinor())throw new IllegalArgumentException("Loan schedule witness");}
    private static void validateEvent(String payload){var p=unpack(payload,4);UUID.fromString(p.get(1));if(!p.get(0).matches("[A-Z][A-Z0-9_]{0,63}")||number(p.get(2))<0||p.get(3).length()>4096)throw new IllegalArgumentException("Loan event fields");}
    private static void validateBorrower(String payload){var p=payload.split("\\|",-1);if(p.length!=4)throw new IllegalArgumentException("Loan borrower fields");var world=new WorldId(UUID.fromString(p[0]));var owner=new PlayerId(UUID.fromString(p[1]));if(!new InstitutionCatalog.SystemActor(world,borrower(owner)).actorId().value().toString().equals(p[2]))throw new IllegalArgumentException("Loan borrower identity");EconomyCurrency.valueOf(p[3]);}
}
