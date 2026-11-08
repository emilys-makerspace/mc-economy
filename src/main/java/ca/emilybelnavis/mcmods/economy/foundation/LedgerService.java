package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.api.AccountFunds;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.api.RequestContext;
import ca.emilybelnavis.mcmods.economy.fx.FxQuotes;
import ca.emilybelnavis.mcmods.economy.fx.FxRequests;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import java.math.BigInteger;
import java.time.Clock;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Financial entry points for commands and trusted server integrations. Call on the server thread. */
public final class LedgerService implements ca.emilybelnavis.mcmods.economy.api.BankingIntegration {
    private static final JournalService.Publisher NO_PUBLICATION = new JournalService.Publisher() {
        public void invalidate(Journal.Outcome outcome) {}
        public void publish(Journal.Outcome outcome) {}
    };

    private final ServerServices services;
    private final FxRequests quotes;
    private final CardReplacementPricing cardFees = new CardReplacementPricing();
    private final CreditReplacementPricing creditCardFees = new CreditReplacementPricing();
    private final FoundationStore store;
    private final ca.emilybelnavis.mcmods.economy.pricing.ServicePricing pricing;

    LedgerService(ServerServices services, FoundationStore store) {
        this.services = services;
        this.store = store;
        this.pricing = services.servicePricing();
        this.quotes = new FxRequests(services, store, Clock.systemUTC());
    }

    public record CreditProfileView(Optional<ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.Profile> profile,
            Optional<ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.History> latest,boolean recurringScoringActive,String reason) {}
    /** A missing profile is unestablished; this read never initializes credit or schedules work. */
    public CompletionStage<Optional<CreditProfileView>> creditProfile(PartyAuthorization.Grant grant) {
        requireOwner(grant);
        return services.readAccounts(grant,false,reads->Optional.of(new CreditProfileView(
                ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.find(reads,grant.target()),ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.latestHistory(reads,grant.target()),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.installed(reads),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.reportingStatus(reads))));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.HistoryPage>> creditHistory(
            PartyAuthorization.Grant grant,long after,int limit) {
        requireOwner(grant);
        if(after<0 || limit<1 || limit>16)throw new IllegalArgumentException("Credit history page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.history(reads,grant.target(),after,limit)));
    }
    /** Separate audited operator capability; an owner grant cannot enter this inspection route. */
    public CompletionStage<Optional<CreditProfileView>> inspectCreditProfile(PartyAuthorization.Grant inspection,
            java.util.function.BooleanSupplier live) {
        return services.inspectAccounts(inspection,Map.of("domain","credit-profile"),live,reads->Optional.of(new CreditProfileView(
                ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.find(reads,inspection.target()),ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.latestHistory(reads,inspection.target()),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.installed(reads),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.reportingStatus(reads))));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.HistoryPage>> inspectCreditHistory(
            PartyAuthorization.Grant inspection,long after,int limit,java.util.function.BooleanSupplier live) {
        if(after<0 || limit<1 || limit>16)throw new IllegalArgumentException("Credit history page bounds");
        return services.inspectAccounts(inspection,Map.of("domain","credit-history","after",Long.toString(after),"limit",Integer.toString(limit)),live,
                reads->Optional.of(ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.history(reads,inspection.target(),after,limit)));
    }

    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.FacilityPage>> creditFacilityPage(
            PartyAuthorization.Grant grant,UUID after,int limit) {
        requireOwner(grant);
        if(limit<1 || limit>16)throw new IllegalArgumentException("Facility diagnostic page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.ownerPage(reads,grant.target(),after,limit,System.currentTimeMillis())));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.FacilityView>> creditFacilityView(
            PartyAuthorization.Grant grant,UUID facility) {
        requireOwner(grant);
        return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.facility(reads,grant.target(),facility,System.currentTimeMillis()));
    }
    /** Audits every operator page before any private facility data is read. */
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.OperatorView>> inspectCreditFacility(
            PartyAuthorization.Grant inspection,UUID facility,ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.EventCursor after,int limit,
            java.util.function.BooleanSupplier live) {
        if(limit<1 || limit>16)throw new IllegalArgumentException("Credit inspection page bounds");
        var cursor=after==null?ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.EventCursor.start():after;
        return services.inspectAccountsDuringCatchup(inspection,Map.of("domain","credit-facility","facility",facility.toString(),
                "after",after==null?"":after.toString(),"limit",Integer.toString(limit)),live,
                reads->ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.inspect(reads,inspection.target(),facility,System.currentTimeMillis(),cursor,limit));
    }

    /** PartyOperation restores this same owner field from the grant; retain the original canonical bytes. */
    private Journal.CanonicalRequest unboundCreditRequest(PartyAuthorization.Grant grant,Journal.CanonicalRequest request){
        var fields=new java.util.TreeMap<>(request.fields());
        var owner=Journal.actorKey(((PartyRef.Player)grant.target().owner()).id());
        if(!owner.equals(fields.remove("economy:owner"))||!grant.target().world().value().toString().equals(fields.get("world")))throw new IllegalArgumentException("Credit request owner/world");
        return new Journal.CanonicalRequest(request.version(),fields);
    }

    /** Facility reads and payments remain available without possession of a token. */
    public CompletionStage<Optional<List<ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Facility>>> creditFacilities(
            PartyAuthorization.Grant grant,UUID after,int limit) {
        requireOwner(grant);
        if(limit<1 || limit>16)throw new IllegalArgumentException("Facility page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.history(reads,grant.target(),after,limit)));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.StatementPage>> creditStatements(
            PartyAuthorization.Grant grant,UUID facility,long afterClosedAt,int limit) {
        requireOwner(grant);
        if(afterClosedAt<0 || limit<1 || limit>16)throw new IllegalArgumentException("Statement page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.statements(reads,grant.target(),facility,afterClosedAt,limit)));
    }
    private ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Inputs creditInputs(JournalEngine.ReadView reads,PartyRef owner,long now) {
        var publication=ca.emilybelnavis.mcmods.economy.credit.CreditTreasury.publication(reads,now);
        if(publication==null)return new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Inputs(new ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting.FxSnapshot(new UUID(0,0),now,now,Map.of(),false),false,false,0);
        var fx=new ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting.FxSnapshot(publication.updateId(),
                ca.emilybelnavis.mcmods.economy.fx.FxService.effectiveMillis(reads,publication.updateId()),now,publication.rates(),true);
        var player=((PartyRef.Player)owner.owner()).id();
        boolean reconciled=new ca.emilybelnavis.mcmods.economy.credit.CreditLiabilities().read(reads,owner.world(),player,java.time.Instant.ofEpochMilli(now)).available()
                &&new ca.emilybelnavis.mcmods.economy.loan.LoanLiabilities().read(reads,owner.world(),player,java.time.Instant.ofEpochMilli(now)).available();
        var tier=pricing.valuation().tierEligibility(owner.world(),Clock.systemUTC()).current(reads,owner);
        long loanExposure=0;boolean blocked=false;
        try {var denomination=RebaseSettlement.denomination(reads).orElseThrow();loanExposure=ca.emilybelnavis.mcmods.economy.loan.Loans.exposure(reads,owner,denomination.currency(),fx,now);blocked=ca.emilybelnavis.mcmods.economy.loan.Loans.blocked(reads,owner,now);}
        catch(IllegalArgumentException|IllegalStateException unavailable){reconciled=false;}
        return new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Inputs(fx,reconciled,blocked,loanExposure,tier.available()&&tier.eligible());
    }
    public JournalService.Submission issueCreditFacility(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.IssueRequest request) {
        requireOwner(grant);
        var handler=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.issuance(grant.target(),request,
                pricing.valuation().tierEligibility(grant.target().world(),Clock.systemUTC()),this::creditInputs,System::currentTimeMillis);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.ISSUE,operation,attempt,unboundCreditRequest(grant,request.request()),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.production(handler),null);
    }
    public JournalService.Submission increaseCreditFacility(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.IncreaseRequest request) {
        requireOwner(grant);
        var handler=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.increase(grant.target(),request,
                pricing.valuation().tierEligibility(grant.target().world(),Clock.systemUTC()),this::creditInputs,System::currentTimeMillis);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.INCREASE,operation,attempt,unboundCreditRequest(grant,request.request()),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.production(handler),null);
    }
    @Override public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy.Policy>> prime(PartyAuthorization.Grant grant) {
        requireOwner(grant);
        return services.readAccounts(grant, false, reads -> Optional.of(ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy.current(reads, System.currentTimeMillis())));
    }
    @Override public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.Policy>> prime(PartyAuthorization.Grant grant,EconomyCurrency currency) {
        requireOwner(grant);Objects.requireNonNull(currency);
        return services.readAccounts(grant,false,reads->{long now=System.currentTimeMillis();
            return ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.ready(reads,grant.target().world(),now)
                    ?ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.current(reads,grant.target().world(),currency,now):Optional.empty();
        });
    }
    @Override public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.Policy>> primeAt(PartyAuthorization.Grant grant,EconomyCurrency currency,long effectiveMillis) {
        requireOwner(grant);Objects.requireNonNull(currency);
        return services.readAccounts(grant,false,reads->{
            if(effectiveMillis<0||effectiveMillis>System.currentTimeMillis())throw new IllegalArgumentException("Past/current prime instant required");
            return ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.ready(reads,grant.target().world(),effectiveMillis)
                    ?ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.current(reads,grant.target().world(),currency,effectiveMillis):Optional.empty();
        });
    }
    @Override public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.Snapshot>> valuation(PartyAuthorization.Grant grant) {
        var actor = requireOwner(grant);
        return services.readAccounts(grant, false, reads -> Optional.of(pricing.valuation().snapshot(reads,
                grant.target().world(), actor, java.time.Instant.now())));
    }
    @Override public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Quote>> feeQuote(
            PartyAuthorization.Grant grant, ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId institution,
            ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service service, Money principal) {
        var actor = requireOwner(grant);
        if (service == ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service.TRADING)
            throw new IllegalArgumentException("TRADING_UNAVAILABLE");
        return services.readAccounts(grant, false, reads -> {
            var now = java.time.Instant.now();
            return Optional.of(pricing.quote(reads, grant.target().world(), actor, institution, service, principal, now, now.plusSeconds(30)));
        });
    }

    public CompletionStage<Optional<AccountIdentity>> ownedBankAccount(PartyAuthorization.Grant grant, AccountId account) {
        requireOwner(grant);
        return services.readAccounts(grant, false, reads -> AccountCatalog.find(reads, grant.target(), account));
    }

    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.autopay.Autopay.Settings>> autopay(
            PartyAuthorization.Grant grant, ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind kind, UUID facility) {
        requireOwner(grant);
        return services.readAccounts(grant,false,reads -> ca.emilybelnavis.mcmods.economy.autopay.Autopay.find(reads,grant.target(),kind,facility));
    }
    public JournalService.Submission configureAutopay(PartyAuthorization.Grant grant, OperationId operation, RequestId attempt,
            ca.emilybelnavis.mcmods.economy.autopay.Autopay.Configure request) {
        requireOwner(grant);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.autopay.Autopay.CONFIGURE,operation,attempt,
                unboundCreditRequest(grant,request.request()),
                ca.emilybelnavis.mcmods.economy.autopay.Autopay.configuration(grant.target(),request,System::currentTimeMillis),null);
    }

    public record CreditDrawQuote(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request,
            TerminalPolicy.Snapshot terminal, ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Facility facility) {}

    /** Destination, token, price and all revisions are resolved on the existing worker. */
    public CompletionStage<Optional<CreditDrawQuote>> quoteCreditDraw(PartyAuthorization.Grant grant, UUID token,
            TerminalPolicy.Anchor anchor, UUID generation, AccountId funding, long amount, boolean purchase) {
        var actor = requireOwner(grant);
        return services.readAccounts(grant, false, reads -> {
            long now = System.currentTimeMillis();
            var card = ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.usableToken(reads, grant.target().world(), actor, token, now).orElseThrow();
            var facility = ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.find(reads, card.facility()).filter(f -> f.party().equals(grant.target())).orElseThrow();
            var terminal = TerminalPolicy.find(reads, grant.target().world(), anchor, actor, true).orElseThrow();
            if (!terminal.active() || !terminal.generation().equals(generation)) throw new IllegalArgumentException("TERMINAL_STALE");
            AccountId destination;
            long destinationRevision;
            long principal;
            if (purchase) {
                var price = RegisterSales.price(reads, grant.target().world(), anchor).orElseThrow();
                if (price.currency() != facility.currency()) throw new IllegalArgumentException("CREDIT_CURRENCY");
                destination = new AccountId(price.sellerAccount());
                destinationRevision = price.sellerAccountRevision();
                principal = price.amount().minorUnits();
            } else {
                var account = AccountCatalog.find(reads, grant.target(), funding).orElseThrow();
                if (account.currency() != facility.currency() || account.purpose() != AccountIdentity.Purpose.BANK
                        || account.status() != AccountIdentity.Status.OPEN) throw new IllegalArgumentException("CREDIT_DESTINATION");
                destination = funding;
                destinationRevision = account.revision();
                principal = amount;
            }
            var profile = ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.find(reads, grant.target()).orElseThrow();
            long expires = Math.min(Math.addExact(now, 30_000), Math.min(facility.nextDay(), facility.nextStatement()));
            var request = new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest(grant.target().world(), actor,
                    facility.id(), token, card.network(), purchase ? ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawKind.PURCHASE
                    : ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawKind.ADVANCE, destination, principal,
                    facility.revision(), card.revision(), destinationRevision, profile.revision(), UUID.randomUUID(), expires);
            return Optional.of(new CreditDrawQuote(request, terminal, facility));
        });
    }

    /** Only a sealed server-thread physical card/terminal proof can authorize a new draw. */
    public JournalService.Submission drawCredit(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request,ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof) {
        requireOwner(grant);
        if (request.kind() != ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawKind.ADVANCE)
            throw new IllegalArgumentException("Use atomic register checkout for purchases");
        var handler=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.draw(grant.target(),request,this::creditInputs,proof,System::currentTimeMillis);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DRAW,operation,attempt,unboundCreditRequest(grant,request.request()),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.production(handler),null);
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.RepaymentRequest>> quoteCreditRepayment(
            PartyAuthorization.Grant grant,UUID facility,AccountId funding,long amountMinor) {
        var actor=requireOwner(grant);
        return services.readAccounts(grant,false,reads->{
            var f=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.find(reads,facility).filter(v->v.party().equals(grant.target())).orElseThrow();
            var a=AccountCatalog.find(reads,grant.target(),funding).orElseThrow();
            if(a.currency()!=f.currency() || amountMinor<=0 || amountMinor>f.owedMinor())throw new IllegalArgumentException("Fresh repayment amount/currency required");
            long now=System.currentTimeMillis();long expires=Math.min(Math.addExact(now,30_000),Math.min(f.nextDay(),f.nextStatement()));
            return Optional.of(new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.RepaymentRequest(grant.target().world(),actor,facility,funding,amountMinor,
                    f.revision(),a.revision(),ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.ledgerRevision(reads,funding.value()),UUID.randomUUID(),expires));
        });
    }
    public CompletionStage<ca.emilybelnavis.mcmods.economy.credit.CreditRepaymentFx.Quote> quoteCreditRepaymentFx(
            PartyAuthorization.Grant grant,UUID facility,AccountId funding,long amountMinor) {
        var actor=requireOwner(grant);
        record Source(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Facility facility,AccountIdentity funding) {}
        return services.readAccounts(grant,false,reads->Optional.of(new Source(
                ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.find(reads,facility).filter(f->f.party().equals(grant.target())).orElseThrow(),
                AccountCatalog.find(reads,grant.target(),funding).orElseThrow())))
            .thenCompose(found->{var source=found.orElseThrow();
                return quotes.quoteRepayment(new RequestContext(actor.value(),services.sessionId(),UUID.randomUUID()),
                        new FxQuotes.Pair(source.funding().currency(),source.facility().currency()),
                        new Money(source.facility().currency(),amountMinor))
                    .thenCompose(fx->services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.credit.CreditRepaymentFx.capture(
                            reads,grant.target(),source.facility(),source.funding(),amountMinor,fx,System.currentTimeMillis()))))
                    .thenApply(Optional::orElseThrow);
            });
    }
    public JournalService.Submission repayCreditFx(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.credit.CreditRepaymentFx.Quote quote) {
        requireOwner(grant);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REPAY,operation,attempt,unboundCreditRequest(grant,quote.canonical()),
                ca.emilybelnavis.mcmods.economy.credit.CreditRepaymentFx.registration(grant.target(),quote,quotes::requireFresh,System::currentTimeMillis),null);
    }
    public JournalService.Submission repayCredit(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.RepaymentRequest request) {
        requireOwner(grant);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REPAY,operation,attempt,unboundCreditRequest(grant,request.request()),
                ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.repayment(grant.target(),request,System::currentTimeMillis),null);
    }
    public JournalService.Submission revokeCreditCard(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,UUID token,long revision) {
        var actor=requireOwner(grant);
        var request=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.revokeRequest(grant.target().world(),actor,token,revision);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REVOKE,operation,attempt,unboundCreditRequest(grant,request),
                ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.revocation(grant.target(),token,revision),null);
    }

    public JournalService.Submission closeCreditFacility(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,UUID facility,long revision) {
        var actor=requireOwner(grant);
        var request=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.closeRequest(grant.target().world(),actor,facility,revision);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.CLOSE,operation,attempt,unboundCreditRequest(grant,request),
                ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.closure(grant.target(),facility,revision,System::currentTimeMillis),null);
    }
    public CompletionStage<ca.emilybelnavis.mcmods.economy.card.IssuedCards.FeeQuote> quoteCreditCardReplacement(
            PartyAuthorization.Grant grant,UUID facility,AccountId funding) {
        var actor=requireOwner(grant);
        record Source(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Facility facility,AccountIdentity funding,EconomyCurrency worldCurrency) {}
        return services.readAccounts(grant,false,reads->Optional.of(new Source(
                ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.find(reads,facility).filter(f->f.party().equals(grant.target())).orElseThrow(),
                AccountCatalog.find(reads,grant.target(),funding).orElseThrow(),RebaseSettlement.denomination(reads).orElseThrow().currency())))
            .thenCompose(found->{var source=found.orElseThrow();
                CompletionStage<FxQuotes.Quote> fx=source.funding().currency()==source.worldCurrency()?CompletableFuture.completedFuture(null):
                    quotes.quote(new RequestContext(actor.value(),services.sessionId(),UUID.randomUUID()),new FxQuotes.Pair(source.funding().currency(),source.worldCurrency()),FxQuotes.Side.EXACT_CREDIT,
                            new Money(source.worldCurrency(),Math.multiplyExact(25L,source.worldCurrency().minorUnitsPerMajor())));
                return fx.thenCompose(quote->services.readAccounts(grant,false,reads->Optional.of(creditCardFees.capture(reads,grant.target(),source.facility().issuer(),facility,source.funding(),quote,System.currentTimeMillis())))).thenApply(Optional::orElseThrow);
            });
    }
    public JournalService.Submission replaceCreditCard(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.ReplacementRequest request) {
        requireOwner(grant);
        var handler=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.replacement(grant.target(),request,
                (intent,fee,reads)->creditCardFees.prepare(intent,fee,reads,fx->quotes.requireFresh(fx.id(),(PlayerId)intent.actor(),grant.target().world(),reads),System.currentTimeMillis()),System::currentTimeMillis);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REPLACE,operation,attempt,unboundCreditRequest(grant,request.request()),handler,null);
    }

    public CompletionStage<Optional<Journal.Outcome>> lookupCollectionPaymentReceipt(PartyAuthorization.Grant grant,OperationId operation) {
        requireOwner(grant);java.util.Objects.requireNonNull(operation);
        return services.readAccounts(grant,false,reads->{
            var rows=reads.query("SELECT * FROM operation_outcomes WHERE world_id=? AND operation_id=? AND actor=? AND service=? AND operation_type=?",
                List.of(grant.target().world().value().toString(),operation.value().toString(),Journal.actorKey(grant.actor()),ca.emilybelnavis.mcmods.economy.debt.DebtPayments.PAY.service(),ca.emilybelnavis.mcmods.economy.debt.DebtPayments.PAY.operationType()),1);
            if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
            return Optional.of(new Journal.Outcome(operation,new RequestId(UUID.fromString(row.get("request_id"))),Journal.Status.valueOf(row.get("status")),
                row.get("transaction_id")==null?null:new TransactionId(UUID.fromString(row.get("transaction_id"))),
                row.get("sequence")==null?null:new JournalSequence(Long.parseLong(row.get("sequence"))),
                new Journal.Result(Integer.parseInt(row.get("result_version")),row.get("result_code"),row.get("result_payload"))));
        });
    }

    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.Snapshot>> collectionAgentState(
            PartyAuthorization.Grant grant,UUID after,int limit) {
        requireOwner(grant);if(limit<1||limit>16)throw new IllegalArgumentException("Collection agent page bounds");
        return services.readCollectionAgentState(grant,after,limit);
    }
    public CompletionStage<Journal.Outcome> collectionAgentTransition(PartyAuthorization.Grant grant,
            ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.Action action,UUID lease,long observedDeathAt) {
        requireOwner(grant);return services.collectionAgentTransition(grant,action,lease,observedDeathAt);
    }

    public CompletionStage<Optional<List<ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Case>>> collectionCases(
            PartyAuthorization.Grant grant,UUID after,int limit) {
        requireOwner(grant);if(limit<1||limit>16)throw new IllegalArgumentException("Collection page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.cases(reads,grant.target(),after,limit)));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Case>> collectionCase(PartyAuthorization.Grant grant,UUID id) {
        requireOwner(grant);return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ownedCase(reads,grant.target(),id));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ReportPage>> debtReports(
            PartyAuthorization.Grant grant,ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ReportCursor after,int limit) {
        requireOwner(grant);if(limit<1||limit>16)throw new IllegalArgumentException("Debt report page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.reports(reads,grant.target(),after,limit)));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtPayments.Quote>> quoteCollectionPayment(
            PartyAuthorization.Grant grant,UUID id,AccountId funding,long amount) {
        requireOwner(grant);return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.debt.DebtPayments.quote(reads,grant.target(),id,funding,amount,System.currentTimeMillis())));
    }
    public JournalService.Submission repayCollection(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.debt.DebtPayments.Quote quote) {
        requireOwner(grant);return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.debt.DebtPayments.PAY,operation,attempt,
            unboundCreditRequest(grant,quote.request()),ca.emilybelnavis.mcmods.economy.debt.DebtPayments.registration(grant.target(),quote,System::currentTimeMillis),null);
    }
    public CompletionStage<ca.emilybelnavis.mcmods.economy.debt.DebtPayments.FxQuote> quoteCollectionPaymentFx(
            PartyAuthorization.Grant grant,UUID id,AccountId funding,long amount) {
        var actor=requireOwner(grant);
        return quoteCollectionPayment(grant,id,funding,amount).thenCompose(found->{var payment=found.orElseThrow();
            return services.readAccounts(grant,false,reads->Optional.of(Map.entry(AccountCatalog.find(reads,grant.target(),funding).orElseThrow().currency(),
                ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ownedCase(reads,grant.target(),id).orElseThrow().currency())))
                .thenCompose(pair->quotes.quoteRepayment(new RequestContext(actor.value(),services.sessionId(),UUID.randomUUID()),new FxQuotes.Pair(pair.orElseThrow().getKey(),pair.orElseThrow().getValue()),new Money(pair.orElseThrow().getValue(),amount)))
                .thenCompose(fx->services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.debt.DebtPayments.capture(reads,grant.target(),payment,fx,System.currentTimeMillis()))))
                .thenApply(Optional::orElseThrow);
        });
    }
    public JournalService.Submission repayCollectionFx(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.debt.DebtPayments.FxQuote quote) {
        requireOwner(grant);return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.debt.DebtPayments.PAY,operation,attempt,
            unboundCreditRequest(grant,quote.canonical()),ca.emilybelnavis.mcmods.economy.debt.DebtPayments.registrationFx(grant.target(),quote,quotes::requireFresh,System::currentTimeMillis),null);
    }
    public record LoanContext(ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Denomination denomination,long calendarOrigin,
            Optional<ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.Profile> profile,ca.emilybelnavis.mcmods.economy.institution.BankProviders.TierSnapshot tier,
            Optional<ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy.Policy> prime,ca.emilybelnavis.mcmods.economy.loan.Loans.Inputs inputs,
            Optional<ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.Selection> currencyPrime) {
        public LoanContext(ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Denomination denomination,long calendarOrigin,
                Optional<ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.Profile> profile,ca.emilybelnavis.mcmods.economy.institution.BankProviders.TierSnapshot tier,
                Optional<ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy.Policy> prime,ca.emilybelnavis.mcmods.economy.loan.Loans.Inputs inputs){
            this(denomination,calendarOrigin,profile,tier,prime,inputs,Optional.empty());
        }
    }
    private ca.emilybelnavis.mcmods.economy.loan.Loans.Inputs loanInputs(JournalEngine.ReadView reads,PartyRef owner,long now){
        var credit=creditInputs(reads,owner,now);long other=0;boolean blocked=credit.creditBlocked(),complete=credit.complete();
        try {
            var cards=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.owned(reads,owner);if(cards.size()>128)throw new IllegalStateException("Credit exposure bound");
            var denomination=RebaseSettlement.denomination(reads).orElseThrow();
            for(var card:cards){long raw=card.status()==ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Status.ACTIVE&&card.access()!=ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Access.CLOSED?Math.max(card.limitMinor(),card.owedMinor()):card.owedMinor();
                other=Math.addExact(other,ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting.exposureToWorld(new Money(card.currency(),raw),denomination.currency(),credit.fx()).minorUnits());
                blocked|=card.status()==ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Status.DEFAULTED&&ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.blocks(reads,ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind.CARD,card.id(),now)||card.requirements().stream().anyMatch(r->r.unmetMinor()>0&&r.dueAt()<=now);
            }
        }catch(IllegalArgumentException|IllegalStateException unavailable){complete=false;}
        return new ca.emilybelnavis.mcmods.economy.loan.Loans.Inputs(credit.fx(),complete,blocked,other);
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.CasePage>> debtCases(
            PartyAuthorization.Grant grant,UUID after,int limit) {
        requireOwner(grant);debtPageBounds(limit);
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.ownerCases(reads,grant.target(),after,limit,System.currentTimeMillis())));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.ReportPage>> debtReportPage(
            PartyAuthorization.Grant grant,ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ReportCursor after,int limit) {
        requireOwner(grant);debtPageBounds(limit);
        var cursor=after==null?ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ReportCursor.start():after;
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.ownerReports(reads,grant.target(),cursor,limit,System.currentTimeMillis())));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.EventPage>> debtEvents(
            PartyAuthorization.Grant grant,UUID caseId,ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.EventCursor after,int limit) {
        requireOwner(grant);debtPageBounds(limit);
        var cursor=after==null?ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.EventCursor.start():after;
        return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.ownerEvents(reads,grant.target(),caseId,cursor,limit));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.OperatorView>> inspectDebt(
            PartyAuthorization.Grant inspection,ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind kind,UUID facility,UUID after,int limit,
            java.util.function.BooleanSupplier live) {
        debtPageBounds(limit);java.util.Objects.requireNonNull(kind);java.util.Objects.requireNonNull(facility);
        return services.inspectAccountsDuringCatchup(inspection,Map.of("domain","debt","kind",kind.name(),"facility",facility.toString(),
            "after",after==null?"":after.toString(),"limit",Integer.toString(limit)),live,
            reads->ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.inspect(reads,inspection.target(),kind,facility,System.currentTimeMillis(),after,limit));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.PaymentPage>> cardPaymentHistory(
            PartyAuthorization.Grant grant,UUID facility,ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.EventCursor after,int limit) {
        requireOwner(grant);debtPageBounds(limit);
        return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.ownerPayments(reads,grant.target(),facility,after,limit));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.EventPage>> loanPaymentHistory(
            PartyAuthorization.Grant grant,UUID facility,ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.EventCursor after,int limit) {
        requireOwner(grant);debtPageBounds(limit);
        return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.ownerPayments(reads,grant.target(),facility,after,limit));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.ReportPage>> inspectDebtReports(
            PartyAuthorization.Grant inspection,ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ReportCursor after,int limit,
            java.util.function.BooleanSupplier live) {
        debtPageBounds(limit);
        var cursor=after==null?ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ReportCursor.start():after;
        return services.inspectAccountsDuringCatchup(inspection,Map.of("domain","debt-reports","after",cursor.toString(),"limit",Integer.toString(limit)),live,
            reads->Optional.of(ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.ownerReports(reads,inspection.target(),cursor,limit,System.currentTimeMillis())));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.EventPage>> inspectDebtEvents(
            PartyAuthorization.Grant inspection,UUID caseId,ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.EventCursor after,int limit,
            java.util.function.BooleanSupplier live) {
        debtPageBounds(limit);
        var cursor=after==null?ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.EventCursor.start():after;
        return services.inspectAccountsDuringCatchup(inspection,Map.of("domain","debt-events","case",caseId.toString(),"after",cursor.toString(),"limit",Integer.toString(limit)),live,
            reads->ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics.ownerEvents(reads,inspection.target(),caseId,cursor,limit));
    }
    private static void debtPageBounds(int limit) {
        if(limit<1||limit>16)throw new IllegalArgumentException("Debt diagnostic page bounds");
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.ContractPage>> loanPage(
            PartyAuthorization.Grant grant,UUID after,int limit) {
        requireOwner(grant);if(limit<1||limit>16)throw new IllegalArgumentException("Loan diagnostic page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.ownerPage(reads,grant.target(),after,limit,System.currentTimeMillis())));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.ContractView>> loanView(
            PartyAuthorization.Grant grant,UUID loan) {
        requireOwner(grant);return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.contract(reads,grant.target(),loan,System.currentTimeMillis()));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.SchedulePage>> loanSchedulePage(
            PartyAuthorization.Grant grant,UUID loan,int after,int limit) {
        requireOwner(grant);if(after<0||after>104||limit<1||limit>16)throw new IllegalArgumentException("Loan schedule page bounds");
        return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.loan.Loans.find(reads,loan).filter(l->l.party().equals(grant.target()))
            .map(l->ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.schedule(reads,grant.target(),loan,after,limit)));
    }
    /** Separate audited authority; this narrow read remains available during financial catch-up. */
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.OperatorView>> inspectLoan(
            PartyAuthorization.Grant inspection,UUID loan,ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.EventCursor after,int limit,
            java.util.function.BooleanSupplier live) {
        if(limit<1||limit>16)throw new IllegalArgumentException("Loan inspection page bounds");
        var cursor=after==null?ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.EventCursor.start():after;
        return services.inspectAccountsDuringCatchup(inspection,Map.of("domain","loan","loan",loan.toString(),
            "after",cursor.toString(),"limit",Integer.toString(limit)),live,
            reads->ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.inspect(reads,inspection.target(),loan,System.currentTimeMillis(),cursor,limit));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.Loans.ApplicationDecision>> inspectLoanApplication(
            PartyAuthorization.Grant inspection,UUID application,java.util.function.BooleanSupplier live) {
        return services.inspectAccountsDuringCatchup(inspection,Map.of("domain","loan-application","application",application.toString()),live,
            reads->ca.emilybelnavis.mcmods.economy.loan.Loans.applicationDecision(reads,inspection.target(),application));
    }
    /** Legacy/general underwriting context: currency selection is deliberately absent. */
    public CompletionStage<Optional<LoanContext>> loanContext(PartyAuthorization.Grant grant){return loanContextInternal(grant,null);}
    public CompletionStage<Optional<LoanContext>> loanContext(PartyAuthorization.Grant grant,EconomyCurrency currency){return loanContextInternal(grant,Objects.requireNonNull(currency));}
    private CompletionStage<Optional<LoanContext>> loanContextInternal(PartyAuthorization.Grant grant,EconomyCurrency currency){
        requireOwner(grant);
        return services.readAccounts(grant,false,reads->{long now=System.currentTimeMillis();
            Optional<ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy.Policy> prime;
            try{prime=Optional.of(ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy.current(reads,now));}catch(IllegalStateException unavailable){prime=Optional.empty();}
            return Optional.of(new LoanContext(RebaseSettlement.denomination(reads).orElseThrow(),Long.parseLong(reads.query("SELECT origin_ms FROM financial_calendar WHERE singleton=1",List.of(),1).getFirst().get("origin_ms")),
                    ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.find(reads,grant.target()),pricing.valuation().tierEligibility(grant.target().world(),Clock.systemUTC()).current(reads,grant.target()),prime,loanInputs(reads,grant.target(),now),currency==null?Optional.empty():Optional.of(ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.select(reads,grant.target().world(),currency,now))));
        });
    }
    public JournalService.Submission applyLoan(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,ca.emilybelnavis.mcmods.economy.loan.Loans.Application application){
        requireOwner(grant);
        var handler=ca.emilybelnavis.mcmods.economy.loan.Loans.application(grant.target(),application,pricing.valuation().tierEligibility(grant.target().world(),Clock.systemUTC()),this::loanInputs,System::currentTimeMillis);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.loan.Loans.APPLY,operation,attempt,unboundCreditRequest(grant,application.request()),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.production(handler),null);
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.LoanDisbursementFx.Quote>> quoteLoanDisbursementFx(PartyAuthorization.Grant grant,ca.emilybelnavis.mcmods.economy.loan.Loans.Application application){
        requireOwner(grant);return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.LoanDisbursementFx.capture(reads,grant.target(),application,pricing,System.currentTimeMillis())));
    }
    public JournalService.Submission applyLoanFx(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,ca.emilybelnavis.mcmods.economy.loan.LoanDisbursementFx.Quote quote){
        requireOwner(grant);
        var handler=ca.emilybelnavis.mcmods.economy.loan.LoanDisbursementFx.application(grant.target(),quote,pricing.valuation().tierEligibility(grant.target().world(),Clock.systemUTC()),this::loanInputs,pricing,System::currentTimeMillis);
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.loan.Loans.APPLY,operation,attempt,unboundCreditRequest(grant,quote.canonical()),ca.emilybelnavis.mcmods.economy.credit.CreditSafety.production(handler),null);
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.Loans.ApplicationDecision>> loanApplicationDecision(PartyAuthorization.Grant grant,UUID application){
        requireOwner(grant);return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.loan.Loans.applicationDecision(reads,grant.target(),application));
    }
    public CompletionStage<Optional<List<ca.emilybelnavis.mcmods.economy.loan.Loans.Contract>>> loans(PartyAuthorization.Grant grant,UUID after,int limit){
        requireOwner(grant);if(limit<1||limit>16)throw new IllegalArgumentException("Loan page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.Loans.history(reads,grant.target(),after,limit)));
    }
    public CompletionStage<Optional<List<ca.emilybelnavis.mcmods.economy.loan.Loans.ScheduleRow>>> loanSchedule(PartyAuthorization.Grant grant,UUID loan,int after,int limit){
        requireOwner(grant);if(after<0||after>104||limit<1||limit>16)throw new IllegalArgumentException("Loan schedule page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.Loans.schedule(reads,grant.target(),loan,after,limit)));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.Loans.InstallmentPage>> loanInstallments(PartyAuthorization.Grant grant,UUID loan,int after,int limit){
        requireOwner(grant);if(after<0||limit<1||limit>16)throw new IllegalArgumentException("Loan installment page bounds");
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.Loans.installments(reads,grant.target(),loan,after,limit)));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.loan.Loans.PaymentQuote>> quoteLoanRepayment(PartyAuthorization.Grant grant,UUID loan,AccountId funding,long amount,boolean fullPayoff){
        requireOwner(grant);return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.Loans.quote(reads,grant.target(),loan,funding,amount,fullPayoff,System.currentTimeMillis())));
    }
    public JournalService.Submission repayLoan(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,ca.emilybelnavis.mcmods.economy.loan.Loans.PaymentQuote quote){
        requireOwner(grant);return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.loan.Loans.REPAY,operation,attempt,unboundCreditRequest(grant,quote.request().request()),ca.emilybelnavis.mcmods.economy.loan.Loans.repayment(grant.target(),quote,System::currentTimeMillis),null);
    }
    public CompletionStage<ca.emilybelnavis.mcmods.economy.loan.LoanRepaymentFx.Quote> quoteLoanRepaymentFx(PartyAuthorization.Grant grant,UUID loan,AccountId funding,long amount,boolean fullPayoff){
        var actor=requireOwner(grant);
        return services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.Loans.quote(reads,grant.target(),loan,funding,amount,fullPayoff,System.currentTimeMillis())))
            .thenCompose(found->{var payment=found.orElseThrow();
                return services.readAccounts(grant,false,reads->Optional.of(Map.entry(AccountCatalog.find(reads,grant.target(),funding).orElseThrow().currency(),ca.emilybelnavis.mcmods.economy.loan.Loans.find(reads,loan).filter(l->l.party().equals(grant.target())).orElseThrow().currency())))
                    .thenCompose(currencies->quotes.quoteRepayment(new RequestContext(actor.value(),services.sessionId(),UUID.randomUUID()),new FxQuotes.Pair(currencies.orElseThrow().getKey(),currencies.orElseThrow().getValue()),new Money(currencies.orElseThrow().getValue(),payment.request().amountMinor())))
                    .thenCompose(fx->services.readAccounts(grant,false,reads->Optional.of(ca.emilybelnavis.mcmods.economy.loan.LoanRepaymentFx.capture(reads,grant.target(),payment,fx,System.currentTimeMillis())))).thenApply(Optional::orElseThrow);
            });
    }
    public JournalService.Submission repayLoanFx(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,ca.emilybelnavis.mcmods.economy.loan.LoanRepaymentFx.Quote quote){
        requireOwner(grant);return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.loan.Loans.REPAY,operation,attempt,unboundCreditRequest(grant,quote.canonical()),ca.emilybelnavis.mcmods.economy.loan.LoanRepaymentFx.registration(grant.target(),quote,quotes::requireFresh,System::currentTimeMillis),null);
    }

    public record CardFeeSource(ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId issuer,
            AccountIdentity funding,EconomyCurrency worldCurrency) {}
    public CompletionStage<ca.emilybelnavis.mcmods.economy.card.IssuedCards.FeeQuote> quoteCardReplacement(
            PartyAuthorization.Grant grant,AccountId account,AccountId funding) {
        var actor=requireOwner(grant);
        return services.readAccounts(grant,false,reads->{
            var source=AccountCatalog.find(reads,grant.target(),funding).orElseThrow();
            var provider=ca.emilybelnavis.mcmods.economy.institution.BankProviders.find(reads,grant.target(),account).orElseThrow();
            var issuer=ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.require(new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(provider.bankKey())).id();
            return Optional.of(new CardFeeSource(issuer,source,RebaseSettlement.denomination(reads).orElseThrow().currency()));
        }).thenCompose(found->{
            var source=found.orElseThrow();
            CompletionStage<FxQuotes.Quote> fx=source.funding().currency()==source.worldCurrency()
                    ?CompletableFuture.completedFuture(null):quotes.quote(new RequestContext(actor.value(),services.sessionId(),UUID.randomUUID()),
                        new FxQuotes.Pair(source.funding().currency(),source.worldCurrency()),FxQuotes.Side.EXACT_CREDIT,
                        new Money(source.worldCurrency(),Math.multiplyExact(25L,source.worldCurrency().minorUnitsPerMajor())));
            return fx.thenCompose(quote->services.readAccounts(grant,false,reads->Optional.of(cardFees.capture(reads,grant.target(),source.issuer(),account,
                    source.funding(),quote,System.currentTimeMillis())))).thenApply(Optional::orElseThrow);
        });
    }
    public JournalService.Submission issueCard(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,
            ca.emilybelnavis.mcmods.economy.card.IssuedCards.Request request) {
        requireOwner(grant);
        var handler=ca.emilybelnavis.mcmods.economy.card.IssuedCards.registration(grant.target(),request,
                pricing.valuation().tierEligibility(grant.target().world(),Clock.systemUTC()),System::currentTimeMillis,
                (intent,fee,reads)->cardFees.prepare(intent,fee,reads,fx->quotes.requireFresh(fx.id(),(PlayerId)intent.actor(),grant.target().world(),reads),System.currentTimeMillis()));
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.card.IssuedCards.ISSUE,operation,attempt,unboundCreditRequest(grant,request.request()),handler,null);
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.card.IssuedCards.Token>> accountCard(PartyAuthorization.Grant grant,AccountId account) {
        requireOwner(grant);
        return services.readAccounts(grant,false,reads->ca.emilybelnavis.mcmods.economy.card.IssuedCards.forAccount(reads,grant.target(),account));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.card.IssuedCards.Token>> usableCard(PlayerId actor,WorldId world,UUID token) {
        return services.storage(actor,()->store.transaction(tx->{
            if(!store.snapshot().worldId().equals(world))return Optional.empty();
            return ca.emilybelnavis.mcmods.economy.card.IssuedCards.usable(tx::query,world,actor,token,System.currentTimeMillis());
        }));
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.card.CardAccess>> usableCardAccess(PlayerId actor,WorldId world,UUID token) {
        return services.storage(actor,()->store.transaction(tx->{
            if(!store.snapshot().worldId().equals(world))return Optional.empty();
            return ca.emilybelnavis.mcmods.economy.card.CardAccess.usable(tx::query,world,actor,token,System.currentTimeMillis());
        }));
    }
    public JournalService.Submission revokeCard(PartyAuthorization.Grant grant,OperationId operation,RequestId attempt,UUID token,long revision) {
        var actor=requireOwner(grant);
        var request=ca.emilybelnavis.mcmods.economy.card.IssuedCards.revokeRequest(token,revision,actor);
        var fields = new java.util.TreeMap<>(request.fields());
        fields.remove("economy:owner");
        return services.submitOwned(grant,ca.emilybelnavis.mcmods.economy.card.IssuedCards.REVOKE,operation,attempt,
                new Journal.CanonicalRequest(request.version(), fields),
                ca.emilybelnavis.mcmods.economy.card.IssuedCards.revocation(grant.target()),null);
    }

    public record PrimaryFunds(AccountIdentity account, AccountFunds funds) {}
    public record BankingContext(WorldId world, AccountIdentity account, TerminalPolicy.Snapshot terminal,
                                 Deposits.FxSnapshot fx, RegisterSales.Price price) {}
    public CompletionStage<BankingContext> bankingContext(PlayerId actor, TerminalPolicy.Anchor anchor, AccountId selected) {
        return services.storage(actor, () -> store.transaction(tx -> {
            var world = store.snapshot().worldId();
            var owner = new PartyRef(world, new PartyRef.Player(actor));
            var account = selected == null ? AccountCatalog.primary(tx::query, owner).orElse(null)
                    : AccountCatalog.find(tx::query, owner, selected).orElse(null);
            var terminal = TerminalPolicy.find(tx::query, world, anchor, actor, true).orElse(null);
            return new BankingContext(world, account, terminal, depositFx(tx::query),
                    RegisterSales.price(tx::query, world, anchor).orElse(null));
        }));
    }
    public record BranchContext(TerminalPolicy.Snapshot terminal, AccountIdentity primary, AccountIdentity account,
            ca.emilybelnavis.mcmods.economy.institution.BankProviders.Binding provider,
            ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Denomination denomination,
            ca.emilybelnavis.mcmods.economy.institution.BankProviders.TierSnapshot tier) {}
    public CompletionStage<Optional<BranchContext>> branchContext(PartyAuthorization.Grant grant,
            TerminalPolicy.Anchor anchor, AccountId selected) {
        var player = requireOwner(grant);
        return services.readAccounts(grant, false, reads -> {
            var primary = AccountCatalog.primary(reads, grant.target()).orElse(null);
            var account = selected == null ? null : AccountCatalog.find(reads, grant.target(), selected).orElse(null);
            var terminal = TerminalPolicy.find(reads, grant.target().world(), anchor, player, true).orElse(null);
            var provider = account == null ? null : ca.emilybelnavis.mcmods.economy.institution.BankProviders.find(reads, grant.target(), account.id()).orElse(null);
            var denomination = RebaseSettlement.denomination(reads).orElse(null);
            var tier = pricing.valuation().tierEligibility(grant.target().world(), Clock.systemUTC()).current(reads, grant.target());
            return Optional.of(new BranchContext(terminal, primary, account, provider, denomination, tier));
        });
    }
    public JournalService.Submission closeAtBranch(PartyAuthorization.Grant grant, OperationId operation, RequestId attempt,
            FundedClosure.Instruction instruction, FundedClosure.ClaimsReader claims,
            ca.emilybelnavis.mcmods.economy.institution.BranchAdmission.Proof proof) {
        requireOwner(grant);
        var handler = FundedClosure.registrationAtBranch(grant.target(), instruction, quotes::requireFresh, claims, proof,
                (intent, reads) -> proof.valid(intent, reads));
        var request = new ca.emilybelnavis.mcmods.economy.institution.BankProviders.Request(proof, 0, instruction.request().fields()).request();
        return services.submitOwned(grant, ca.emilybelnavis.mcmods.economy.institution.BankProviders.CLOSE, operation, attempt, unboundCreditRequest(grant,request), handler, null);
    }

    public CompletionStage<Optional<Deposits.Reservation>> deposit(PlayerId actor, UUID id) {
        return services.storage(actor, () -> store.transaction(tx -> Deposits.find(tx::query, actor, id)));
    }


    public record Reconciliation(EconomyCurrency currency, long posted, BigInteger journal, boolean complete) {
        public boolean matches() {
            return complete && journal.equals(BigInteger.valueOf(posted));
        }
    }

    public CompletionStage<Optional<AccountFunds>> funds(PartyAuthorization.Grant grant, AccountId account) {
        return services.readAccounts(grant, false, reads -> AccountCatalog.find(reads, grant.target(), account)
                .flatMap(identity -> bankFunds(reads, identity)));
    }

    public CompletionStage<Optional<PrimaryFunds>> primaryFunds(PartyAuthorization.Grant grant) {
        return services.readAccounts(grant, false, reads -> AccountCatalog.primary(reads, grant.target())
                .flatMap(account -> bankFunds(reads, account).map(funds -> new PrimaryFunds(account, funds))));
    }

    static Optional<AccountFunds> bankFunds(JournalEngine.ReadView reads, AccountIdentity account) {
        if (account.purpose() != AccountIdentity.Purpose.BANK) return Optional.empty();
        var id = account.id().value();
        var currency = account.currency();
        long reserved = Holds.reserved(reads, id, currency);
        long posted = Settlement.posted(reads, id, currency);
        return Optional.of(new AccountFunds(currency, Math.subtractExact(posted, reserved), reserved, 0));
    }

    public CompletionStage<Optional<LedgerHistory.Page>> history(
            PartyAuthorization.Grant grant, AccountId account, String cursor, int limit) {
        return services.readAccounts(grant, false, reads -> LedgerHistory.page(reads, grant.target(), account, cursor, limit));
    }

    /** One owner-authorized worker transaction; no section advances financial time or mutates state. */
    /** New frozen owner query; the legacy ascending history facade above remains unchanged. */
    public CompletionStage<Optional<OwnerHistory.Page>> ownerHistory(PartyAuthorization.Grant grant,
            OwnerHistory.Query query, Long highWater, OwnerHistory.Key before) {
        requireOwner(grant);
        return services.readAccounts(grant, false, reads -> Optional.of(OwnerHistory.begin(query,
                highWater == null ? OwnerHistory.highWater(reads) : highWater, before)))
                .thenCompose(start -> start.isEmpty() ? java.util.concurrent.CompletableFuture.completedStage(Optional.empty())
                        : ownerHistoryTurn(grant, start.orElseThrow()));
    }
    private CompletionStage<Optional<OwnerHistory.Page>> ownerHistoryTurn(PartyAuthorization.Grant grant, OwnerHistory.Scan scan) {
        return services.readAccounts(grant, false, reads -> Optional.of(OwnerHistory.scan(reads, grant.target(), scan)))
                .thenCompose(next -> next.isEmpty() ? java.util.concurrent.CompletableFuture.completedStage(Optional.empty())
                        : next.orElseThrow().complete() ? java.util.concurrent.CompletableFuture.completedStage(Optional.of(OwnerHistory.finish(next.orElseThrow())))
                        : ownerHistoryTurn(grant, next.orElseThrow()));
    }
    public CompletionStage<Optional<HistoryDetails.Detail>> historyDetail(PartyAuthorization.Grant grant, UUID transaction, long highWater) {
        requireOwner(grant); return services.readAccounts(grant, false, reads -> HistoryDetails.read(reads, grant.target(), transaction, highWater));
    }
    public CompletionStage<Optional<List<AccountIdentity>>> historyAccounts(PartyAuthorization.Grant grant, AccountId after) {
        requireOwner(grant); return services.readAccounts(grant, false, reads -> Optional.of(AccountCatalog.history(reads, grant.target(), after, 17)));
    }
    public CompletionStage<Optional<BankingHistory.RatePage>> historyRates(PartyAuthorization.Grant grant, BankingHistory.RateQuery query) {
        requireOwner(grant); return services.readAccounts(grant, false, reads -> Optional.of(BankingHistory.readRates(reads, grant.target().world(), query)));
    }

    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.banking.BankingDashboardView>> dashboard(
            PartyAuthorization.Grant grant, AccountId selected, TerminalPolicy.Anchor anchor) {
        requireOwner(grant);
        return services.readAccounts(grant, false, reads -> Optional.of(
                BankingDashboard.read(reads, grant.target(), selected, anchor, System.currentTimeMillis())));
    }

    public CompletionStage<Optional<LedgerHistory.Page>> inspectHistory(CommandSourceStack source,
            Function<MinecraftServer, ServerServices> sessions, PartyRef target, AccountId account, String cursor, int limit) {
        return inspect(source, sessions, target, Map.of("ledger_history", account.value().toString()),
                reads -> LedgerHistory.page(reads, target, account, cursor, limit));
    }

    public CompletionStage<Optional<Reconciliation>> reconcile(CommandSourceStack source,
            Function<MinecraftServer, ServerServices> sessions, PartyRef target, AccountId account) {
        return inspect(source, sessions, target, Map.of("ledger_reconcile", account.value().toString()), reads ->
                AccountCatalog.find(reads, target, account).flatMap(identity -> bankFunds(reads, identity).flatMap(funds ->
                        LedgerHistory.page(reads, target, account, null, LedgerHistory.MAX_PAGE_SIZE).map(page -> {
                            var journal = page.entries().stream().map(LedgerHistory.Entry::amount)
                                    .filter(amount -> amount.currency() == funds.currency())
                                    .map(amount -> BigInteger.valueOf(amount.minorUnits()))
                                    .reduce(BigInteger.ZERO, BigInteger::add);
                            return new Reconciliation(funds.currency(), funds.postedMinorUnits(), journal, page.nextCursor() == null);
                        }))));
    }

    private <T> CompletionStage<Optional<T>> inspect(CommandSourceStack source,
            Function<MinecraftServer, ServerServices> sessions, PartyRef target, Map<String, String> scope,
            Function<JournalEngine.ReadView, Optional<T>> query) {
        var grant = PartyAuthorization.inspection(source, sessions, target);
        if (grant.isEmpty() || sessions.apply(source.getServer()) != services) {
            return CompletableFuture.completedStage(Optional.empty());
        }
        return services.inspectAccounts(grant.get(), scope,
                () -> sessions.apply(source.getServer()) == services && PartyAuthorization.inspection(source, sessions, target).isPresent(),
                query);
    }

    /** Snapshot expected revisions for a fresh payment. Keep the resulting instruction for retries. */
    public CompletionStage<Optional<SameCurrency.Payment>> planPayment(
            PartyAuthorization.Grant grant, PlayerId recipient, long wholeCoins) {
        if (wholeCoins <= 0) throw new IllegalArgumentException("Positive amount required");
        return services.readAccounts(grant, false, reads -> {
            var owner = (PartyRef.Player) grant.target().owner();
            var target = new PartyRef(grant.target().world(), new PartyRef.Player(recipient));
            return AccountCatalog.primary(reads, grant.target()).flatMap(debit ->
                    AccountCatalog.primary(reads, target).map(credit -> new SameCurrency.Payment(
                            owner.id(), revision(debit), recipient, revision(credit),
                            new Money(debit.currency(), Math.multiplyExact(wholeCoins, debit.currency().minorUnitsPerMajor())))));
        });
    }

    public JournalService.Submission submit(PartyAuthorization.Grant grant, OperationId operation,
                                            RequestId attempt, SameCurrency.Instruction instruction) {
        var intent = ownedIntent(grant, operation, attempt, instruction);
        var handlers = SameCurrency.registrations(grant.target().world(), (request, decoded, reads) -> true);
        return ownedJournal(grant, intent, handlers.get(intent.kind())).submit(intent);
    }

    public CompletionStage<Optional<Journal.Outcome>> lookup(PartyAuthorization.Grant grant, OperationId operation,
            RequestId attempt, SameCurrency.Instruction instruction) {
        var intent = ownedIntent(grant, operation, attempt, instruction);
        return ownedJournal(grant, intent, null).lookup(intent);
    }

    public CompletionStage<FxQuotes.Quote> quote(PartyAuthorization.Grant grant, RequestId attempt,
                                                  FxQuotes.Pair pair, FxQuotes.Side side, Money amount) {
        var player = requireOwner(grant);
        return quotes.quote(new RequestContext(player.value(), services.sessionId(), attempt.value()), pair, side, amount);
    }

    public CompletionStage<FxQuotes.Quote> quoteClosure(PartyAuthorization.Grant grant,RequestId attempt,
            FxQuotes.Pair pair,Money budget) {
        var player=requireOwner(grant);
        return quotes.quoteForClosure(new RequestContext(player.value(),services.sessionId(),attempt.value()),pair,budget);
    }

    public JournalService.Submission exchange(PartyAuthorization.Grant grant, OperationId operation,
                                               RequestId attempt, FxSettlement.Exchange exchange) {
        return exchange(grant, operation, attempt, exchange, (intent, reads) -> true);
    }
    public JournalService.Submission exchange(PartyAuthorization.Grant grant, OperationId operation, RequestId attempt,
            FxSettlement.Exchange exchange, BiPredicate<Journal.Intent, JournalEngine.ReadView> authority) {
        if (!requireOwner(grant).equals(exchange.owner())) throw new IllegalArgumentException("Exchange owner");
        var intent = new Journal.Intent(operation, attempt, grant.actor(), FxSettlement.EXCHANGE, exchange.request(), null, null);
        var handler = FxSettlement.registration(grant.target().world(), quotes::requireFresh, (request, decoded, reads) -> authority.test(request, reads));
        return ownedJournal(grant, intent, handler).submit(intent);
    }

    public CompletionStage<Optional<Journal.Outcome>> lookupExchange(PartyAuthorization.Grant grant,
            OperationId operation, RequestId attempt, FxSettlement.Exchange exchange) {
        if (!requireOwner(grant).equals(exchange.owner())) throw new IllegalArgumentException("Exchange owner");
        var intent = new Journal.Intent(operation, attempt, grant.actor(), FxSettlement.EXCHANGE, exchange.request(), null, null);
        return ownedJournal(grant, intent, null).lookup(intent);
    }

    public JournalService.Submission close(PartyAuthorization.Grant grant, OperationId operation, RequestId attempt,
            FundedClosure.Instruction instruction, FundedClosure.ClaimsReader claims,
            BiPredicate<Journal.Intent, JournalEngine.ReadView> authority) {
        requireOwner(grant);
        var handler = FundedClosure.registration(grant.target(), instruction, quotes::requireFresh, claims, authority);
        return services.submitOwned(grant, AccountLifecycle.CLOSE, operation, attempt, instruction.request(), handler, null);
    }

    public CompletionStage<Optional<Journal.Outcome>> lookupClosure(PartyAuthorization.Grant grant,
            OperationId operation, RequestId attempt, FundedClosure.Instruction instruction) {
        requireOwner(grant);
        return services.lookupOwned(grant, AccountLifecycle.CLOSE, operation, attempt, instruction.request(),
                new JournalEngine.Registration(1, (intent, reads) -> {
                    throw new IllegalStateException("Lookup cannot prepare closure");
                }));
    }

    /** Bind a trusted credit or debit handler with separate admission and live adjustment policies. */
    public JournalService adjustments(Journal.Kind kind, JournalEngine.AccessPolicy access,
                                       SameCurrency.Authority authority, JournalService.Publisher publisher) {
        if (!kind.equals(SameCurrency.CREDIT) && !kind.equals(SameCurrency.DEBIT)) {
            throw new IllegalArgumentException("Credit or debit kind required");
        }
        var handler = SameCurrency.registrations(services.partyWorld(), authority).get(kind);
        return trustedJournal(kind, access, handler, publisher);
    }

    /**
     * For trusted server code. The caller derives actor and policy from authenticated server authority.
     * The access policy gates replay and lookup. The registration checks live worker-side business authority.
     */
    public JournalService trustedJournal(Journal.Kind kind, JournalEngine.AccessPolicy access,
                                         JournalEngine.Registration registration, JournalService.Publisher publisher) {
        return services.journal((actor, requested) -> kind.equals(requested) && access.allows(actor, requested),
                Map.of(kind, registration), publisher == null ? NO_PUBLICATION : publisher);
    }

    /** Bind a payment action to authenticated server admission and live worker authority. */
    public JournalService merchantPayments(MerchantPayments.Action action, JournalEngine.AccessPolicy admission,
                                           MerchantPayments.Authority authority, JournalService.Publisher publisher) {
        return trustedJournal(action.kind(), admission,
                MerchantPayments.registration(action, authority, Clock.systemUTC()), publisher);
    }

    public CompletionStage<Optional<MerchantPayments.ReceiptView>> paymentReceipt(
            ActorId actor, UUID operation, MerchantPayments.ReceiptAuthority authority) {
        return services.storage(actor, () -> store.transaction(tx ->
                MerchantPayments.receipt(tx::query, actor, operation, authority)));
    }

    public record PaymentInspection(PaymentDiagnostics.Page page, String releaseWorkerFailure) {}

    /** Invoke with a server-authenticated actor and trusted live operator resolver. */
    public CompletionStage<PaymentInspection> inspectPayments(ActorId actor, PaymentDiagnostics.Operators operators,
                                                              UUID after, int limit, boolean overdueOnly) {
        String failure = services.paymentReleaseFailure();
        return services.storage(actor, () -> store.transaction(tx -> {
            boolean operator = operators.allows(actor, tx::query);
            var page = PaymentDiagnostics.page(tx::query, actor, (a, reads) -> operator,
                    after, limit, overdueOnly, Clock.systemUTC());
            return new PaymentInspection(page, operator ? failure : null);
        }));
    }

    public CompletionStage<Optional<PaymentDiagnostics.Audit>> inspectPaymentOperation(
            ActorId actor, PaymentDiagnostics.Operators operators, UUID invoice, UUID operation) {
        return services.storage(actor, () -> store.transaction(tx ->
                PaymentDiagnostics.audit(tx::query, actor, operators, invoice, operation)));
    }

    /** Trusted adapters bind immutable intent, external evidence and live server authority. */
    public JournalService custody(Custody.Action action, JournalEngine.AccessPolicy admission,
                                  Custody.Authority authority, Custody.EvidenceVerifier verifier,
                                  Custody.Financial financial, Custody.Capacity capacity,
                                  JournalService.Publisher publisher) {
        return trustedJournal(action.kind(), admission,
                Custody.registration(action, authority, verifier, financial, capacity), publisher);
    }

    public CompletionStage<Custody.Page> custodyClaims(ActorId authenticatedOwner, UUID after, int limit) {
        return services.storage(authenticatedOwner, () -> store.transaction(tx ->
                Custody.claims(tx::query, authenticatedOwner, after, limit)));
    }

    public CompletionStage<Optional<Custody.Case>> custodyCase(ActorId actor, UUID custody,
                                                              PaymentDiagnostics.Operators operators) {
        return services.storage(actor, () -> store.transaction(tx ->
                Custody.find(tx::query, actor, custody, operators.allows(actor, tx::query))));
    }

    public CompletionStage<CustodyDiagnostics.Page> inspectCustody(ActorId actor,
            PaymentDiagnostics.Operators operators, CustodyDiagnostics.Filter filter, UUID after, int limit) {
        return services.storage(actor, () -> store.transaction(tx ->
                CustodyDiagnostics.page(tx::query, actor, operators, filter, after, limit)));
    }

    public CompletionStage<Optional<CustodyDiagnostics.Witness>> inspectCustodyWitness(ActorId actor,
            PaymentDiagnostics.Operators operators, UUID custody, CustodyDiagnostics.Slot slot) {
        var world = services.worldDirectory();
        return services.storage(actor, () -> store.transaction(tx ->
                CustodyDiagnostics.witness(tx::query, actor, operators, custody, slot, world, store.snapshot().worldId())));
    }

    public CompletionStage<Optional<CustodyDiagnostics.Audit>> inspectCustodyOperation(ActorId actor,
            PaymentDiagnostics.Operators operators, UUID custody, UUID operation) {
        return services.storage(actor, () -> store.transaction(tx ->
                CustodyDiagnostics.audit(tx::query, actor, operators, custody, operation)));
    }

    /** No gameplay redemption is enabled. Trusted integrations must prove custody and value backing. */
    public JournalService cashIssuance(CashIssuance.Action action, JournalEngine.AccessPolicy admission,
            CashIssuance.Authority authority, CashIssuance.EvidenceVerifier verifier,
            CashIssuance.Financial financial, JournalService.Publisher publisher) {
        var world = services.partyWorld();
        var items = ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream()
                .map(value -> "economy:" + value.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        CashIssuance.Authority catalogAuthority = (requested, intent, lots, reads) ->
                lots.stream().allMatch(lot -> items.contains(lot.item()))
                        && authority.allows(requested, intent, lots, reads);
        return trustedJournal(action.kind(), admission,
                CashIssuance.registration(action, world, catalogAuthority, verifier, financial), publisher);
    }

    /** Server placement/session admission and current owner/operator policy share one journal boundary. */
    public JournalService terminalPolicy(TerminalPolicy.Action action, JournalEngine.AccessPolicy admission,
            TerminalPolicy.Authority authority, TerminalPolicy.Operators operators,
            JournalService.Publisher publisher) {
        var catalog = terminalCatalog();
        return trustedJournal(action.kind(), admission,
                TerminalPolicy.registration(action, services.partyWorld(), catalog, authority, operators), publisher);
    }

    public JournalService kioskUpgrade(KioskUpgrades.Action action, JournalEngine.AccessPolicy admission,
            TerminalPolicy.Authority authority,TerminalPolicy.Operators operators){
        return trustedJournal(action.kind(),admission,KioskUpgrades.registration(action,services.partyWorld(),authority,operators),null);
    }
    public CompletionStage<Optional<KioskUpgrades.Upgrade>> kioskUpgrade(UUID generation){
        var world=services.partyWorld();return services.storage(()->store.transaction(tx->KioskUpgrades.find(tx::query,world,generation)));
    }

    public JournalService terminalBanking(JournalEngine.AccessPolicy admission, TerminalPolicy.Authority authority,
            TerminalPolicy.Operators operators) {
        return trustedJournal(TerminalBanking.BIND, admission,
                TerminalBanking.registration(services.partyWorld(), terminalCatalog(), authority, operators), null);
    }

    public CompletionStage<Optional<TerminalPolicy.Snapshot>> terminalSettings(ActorId actor,
            TerminalPolicy.Operators operators, TerminalPolicy.Anchor anchor) {
        return services.storage(actor, () -> store.transaction(tx ->
                TerminalPolicy.find(tx::query, store.snapshot().worldId(), anchor, actor,
                        operators.allows(actor, tx::query))));
    }

    public record TerminalInspection(TerminalPolicy.Snapshot terminal, boolean pendingCash) {}
    public CompletionStage<Optional<TerminalInspection>> terminalInspection(ActorId actor,
            TerminalPolicy.Operators operators, TerminalPolicy.Anchor anchor) {
        return services.storage(actor, () -> store.transaction(tx -> {
            if(!operators.allows(actor,tx::query))return Optional.empty();
            return TerminalPolicy.find(tx::query,store.snapshot().worldId(),anchor,actor,true)
                    .map(value->new TerminalInspection(value,TerminalBanking.hasPending(tx::query,value.world(),value.generation())));
        }));
    }

    private static Map<String, TerminalPolicy.Denomination> terminalCatalog() {
        return ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(value -> "economy:" + value.id(),
                        value -> new TerminalPolicy.Denomination(value.currency(), value.bill())));
    }

    private static Map<String, Deposits.Denomination> depositCatalog() {
        return ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(value -> "economy:" + value.id(),
                        value -> new Deposits.Denomination(value.currency(), value.minorUnits(), value.bill())));
    }

    private static Deposits.FxSnapshot depositFx(JournalEngine.ReadView reads) {
        var publication = ca.emilybelnavis.mcmods.economy.fx.FxService.latest(reads);
        if(publication!=null){long effective=ca.emilybelnavis.mcmods.economy.fx.FxService.effectiveMillis(reads,publication.updateId());long age=System.currentTimeMillis()-effective;if(age<0||age>=86_400_000L)return null;}
        return publication == null ? null : new Deposits.FxSnapshot(
                publication.day() + "/" + publication.updateId() + "/" + publication.policyId(), publication.conversionRates());
    }

    private Deposits.FxSnapshot depositFx(JournalEngine.ReadView reads,PlayerId actor,UUID account) {
        var fx=depositFx(reads);
        return fx==null?null:new Deposits.FxSnapshot(fx.revision(),fx.rates(),account==null?null:
                new ca.emilybelnavis.mcmods.economy.pricing.CashPricing.Context(pricing,store.snapshot().worldId(),actor,account,java.time.Instant.now()));
    }
    private Withdrawals.FxSnapshot withdrawalFx(JournalEngine.ReadView reads,PlayerId actor,UUID account) {
        var fx=depositFx(reads,actor,account);
        return fx==null?null:new Withdrawals.FxSnapshot(fx.revision(),fx.rates(),fx.pricing());
    }

    public CompletionStage<Deposits.Preview> previewDeposit(PlayerId actor, Deposits.Binding binding,
            List<Deposits.Slot> slots, java.util.function.BiPredicate<Deposits.Binding, JournalEngine.ReadView> admission) {
        var catalog = depositCatalog();
        var originals = List.copyOf(slots);
        return services.economicWrite(actor, () -> store.transaction(tx -> {
            if (!admission.test(binding, tx::query)) throw new IllegalArgumentException("DEPOSIT_ACCESS");
            return Deposits.preview(tx::query, store.snapshot().worldId(), actor, binding, originals, catalog,
                    reads -> depositFx(reads,actor,binding.account()));
        }));
    }

    public JournalService deposits(Deposits.Action action, JournalEngine.AccessPolicy admission,
            Deposits.Authority authority, Deposits.EvidenceVerifier evidence, JournalService.Publisher publisher) {
        var world=services.partyWorld();
        var handler=new JournalEngine.Registration(1,(intent,reads)->{
            UUID account=null;
            try {
                if(action==Deposits.Action.RESERVE)account=UUID.fromString(intent.request().fields().get("binding").split("\\|",-1)[0]);
                else account=Deposits.find(reads,intent.actor(),UUID.fromString(intent.request().fields().get("reservation")))
                        .map(r->r.preview().binding().account()).orElse(null);
            } catch(IllegalArgumentException|NullPointerException malformed) { return new JournalEngine.Reject(new Journal.Result(1,"DEPOSIT_REQUEST","")); }
            final UUID selected=account;
            return Deposits.registration(action,world,depositCatalog(),r->depositFx(r,(PlayerId)intent.actor(),selected),authority,evidence).handler().prepare(intent,reads);
        });
        return trustedJournal(action.kind(),admission,handler,publisher);
    }

    public JournalService depositReturns(DepositReturns.Action action, JournalEngine.AccessPolicy admission,
            DepositReturns.Authority authority, DepositReturns.EvidenceVerifier evidence, JournalService.Publisher publisher) {
        return trustedJournal(action.kind(), admission, DepositReturns.registration(action, services.partyWorld(),
                authority, evidence), publisher);
    }

    public CompletionStage<List<DepositReturns.Return>> depositReturns(PlayerId actor, UUID deposit) {
        return services.storage(actor, () -> store.transaction(tx -> DepositReturns.list(tx::query, actor, deposit)));
    }

    private static Map<String, WithdrawalPlanner.Denomination> withdrawalCatalog() {
        return ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(value -> "economy:" + value.id(),
                        value -> new WithdrawalPlanner.Denomination("economy:" + value.id(),
                                value.currency(), value.minorUnits(), value.bill())));
    }

    private static Withdrawals.FxSnapshot withdrawalFx(JournalEngine.ReadView reads) {
        var publication = depositFx(reads);
        return publication == null ? null : new Withdrawals.FxSnapshot(publication.revision(), publication.rates());
    }

    public CompletionStage<Withdrawals.Preview> previewWithdrawal(PlayerId actor, Withdrawals.Binding binding,
            Money cash, Map<String, Integer> billEdits,
            java.util.function.BiPredicate<Withdrawals.Binding, JournalEngine.ReadView> admission,
            java.util.function.BiPredicate<Withdrawals.Preview, JournalEngine.ReadView> capacity) {
        var catalog = withdrawalCatalog();
        var edits = billEdits == null ? null : Map.copyOf(billEdits);
        return services.economicWrite(actor, () -> store.transaction(tx -> {
            if (!admission.test(binding, tx::query)) throw new IllegalArgumentException("WITHDRAWAL_ACCESS");
            var preview = Withdrawals.preview(tx::query, store.snapshot().worldId(), actor, binding, cash,
                    edits, catalog, reads -> withdrawalFx(reads,actor,binding.account()));
            if (!capacity.test(preview, tx::query)) throw new IllegalArgumentException("WITHDRAWAL_CAPACITY");
            return preview;
        }));
    }

    public JournalService withdrawals(Withdrawals.Action action, JournalEngine.AccessPolicy admission,
            Withdrawals.Authority authority, Withdrawals.Capacity capacity, JournalService.Publisher publisher) {
        var world=services.partyWorld();
        var handler=new JournalEngine.Registration(1,(intent,reads)->{
            UUID account=null;
            try {
                if(action==Withdrawals.Action.RESERVE)account=UUID.fromString(intent.request().fields().get("binding").split("\\|",-1)[0]);
                else account=Withdrawals.find(reads,intent.actor(),UUID.fromString(intent.request().fields().get("reservation")))
                        .map(r->r.preview().binding().account()).orElse(null);
            } catch(IllegalArgumentException|NullPointerException malformed) { return new JournalEngine.Reject(new Journal.Result(1,"WITHDRAWAL_REQUEST","")); }
            final UUID selected=account;
            return Withdrawals.registration(action,world,withdrawalCatalog(),r->withdrawalFx(r,(PlayerId)intent.actor(),selected),authority,capacity,WithdrawalDelivery::prepare).handler().prepare(intent,reads);
        });
        return trustedJournal(action.kind(),admission,handler,publisher);
    }

    public CompletionStage<Optional<Withdrawals.Reservation>> withdrawal(PlayerId actor, UUID reservation) {
        return services.storage(actor, () -> store.transaction(tx -> Withdrawals.find(tx::query, actor, reservation)));
    }

    public CompletionStage<Optional<RegisterSales.Reservation>> registerSale(PlayerId actor, UUID id) {
        return services.storage(actor, () -> store.transaction(tx -> RegisterSales.find(tx::query, actor, id)));
    }
    public record RegisterPriceContext(TerminalPolicy.Snapshot terminal, RegisterSales.Price price, AccountIdentity seller) {}

    public CompletionStage<Optional<RegisterPriceContext>> registerPriceContext(PlayerId actor,
            TerminalPolicy.Anchor anchor, RegisterSales.Operators operators) {
        return services.storage(actor, () -> store.transaction(tx -> {
            var terminal = TerminalPolicy.find(tx::query, store.snapshot().worldId(), anchor, actor,
                    operators.allows(actor, tx::query)).orElse(null);
            if (terminal == null || !terminal.active() || terminal.kind() != TerminalPolicy.Kind.REGISTER
                    || !(terminal.owner() instanceof PlayerId owner)) return Optional.empty();
            var party = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(owner));
            var seller = AccountCatalog.primary(tx::query, party).orElse(null);
            if (seller == null) return Optional.empty();
            return Optional.of(new RegisterPriceContext(terminal,
                    RegisterSales.price(tx::query, store.snapshot().worldId(), anchor).orElse(null), seller));
        }));
    }

    public CompletionStage<RegisterSales.Quote> quoteSale(PlayerId buyer, TerminalPolicy.Anchor anchor,
            UUID generation, long terminalRevision, RegisterSales.SourceReader sources,
            java.util.function.BiPredicate<TerminalPolicy.Anchor, JournalEngine.ReadView> admission) {
        return quoteSale(buyer, anchor, generation, terminalRevision, sources, admission, false);
    }
    public CompletionStage<RegisterSales.Quote> quoteSale(PlayerId buyer, TerminalPolicy.Anchor anchor,
            UUID generation, long terminalRevision, RegisterSales.SourceReader sources,
            java.util.function.BiPredicate<TerminalPolicy.Anchor, JournalEngine.ReadView> admission, boolean creditFunding) {
        return services.economicWrite(buyer, () -> store.transaction(tx -> {
            if (!admission.test(anchor, tx::query)) throw new IllegalArgumentException("SALE_ACCESS");
            return RegisterSales.quote(tx::query, store.snapshot().worldId(), buyer, anchor, generation, terminalRevision, sources, creditFunding);
        }));
    }

    public JournalService registerSales(RegisterSales.Action action, JournalEngine.AccessPolicy admission,
            RegisterSales.Authority authority, RegisterSales.Operators operators, RegisterSales.Capacity capacity,
            RegisterSales.SourceReader sources, RegisterSales.EvidenceVerifier evidence, JournalService.Publisher publisher) {
        return registerSales(action, admission, authority, operators, capacity, sources, evidence, publisher,
                RegisterSaleSettlement::checkout);
    }
    public JournalService registerSales(RegisterSales.Action action, JournalEngine.AccessPolicy admission,
            RegisterSales.Authority authority, RegisterSales.Operators operators, RegisterSales.Capacity capacity,
            RegisterSales.SourceReader sources, RegisterSales.EvidenceVerifier evidence, JournalService.Publisher publisher,
            RegisterSales.SettlementComposer composer) {
        return trustedJournal(action.kind(), admission, RegisterSales.registration(action, services.partyWorld(),
                authority, operators, capacity, sources, evidence, composer,
                (intent, sale, uncertain, reads) -> RegisterSaleSettlement.returnOriginals(intent, sale, uncertain, reads, sources)), publisher);
    }

    /** Trusted composer keeps credit posting, reserved stock and custody in one journal transaction. */
    public RegisterSales.SettlementComposer creditCheckout(PartyAuthorization.Grant grant,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request,
            ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof) {
        requireOwner(grant);
        if (request.kind() != ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawKind.PURCHASE)
            throw new IllegalArgumentException("Purchase required");
        var registration = ca.emilybelnavis.mcmods.economy.credit.CreditSafety.production(
                ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.draw(grant.target(), request, this::creditInputs, proof, System::currentTimeMillis));
        return (intent, sale, reads) -> {
            if (!intent.actor().equals(request.owner()) || !sale.buyer().equals(request.owner())
                    || !request.destination().value().equals(sale.quote().price().sellerAccount())
                    || request.amountMinor() != sale.quote().price().amount().minorUnits()
                    || !intent.request().fields().getOrDefault("credit_request", "").equals(new java.util.TreeMap<>(request.request().fields()).toString()))
                throw new IllegalArgumentException("CREDIT_SALE_BINDING");
            var child = new Journal.Intent(intent.operationId(), intent.requestId(), intent.actor(),
                    ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DRAW, request.request(), null, null);
            var decision = registration.handler().prepare(child, reads);
            if (!(decision instanceof JournalEngine.Commit captured)) return decision;
            var custody = Custody.committedDelivery(intent, sale.id(), sale.buyer(), RegisterSaleSettlement.items(sale),
                    "register-stock:" + sale.quote().source().generation() + ":" + sale.id(), Journal.actorKey(sale.buyer()), reads);
            var records = new java.util.ArrayList<>(captured.records());
            records.addAll(custody.records());
            return new JournalEngine.Commit(records, captured.result(), tx -> {
                captured.projections().apply(tx);
                custody.projections().apply(tx);
            });
        };
    }

    private Journal.Intent ownedIntent(PartyAuthorization.Grant grant, OperationId operation, RequestId attempt,
                                       SameCurrency.Instruction instruction) {
        var player = requireOwner(grant);
        var kind = switch (instruction) {
            case SameCurrency.Payment pay when pay.source().equals(player) -> SameCurrency.PAY;
            case SameCurrency.Transfer transfer when transfer.owner().equals(player) -> SameCurrency.TRANSFER;
            default -> throw new IllegalArgumentException("Unauthorized owner instruction");
        };
        return new Journal.Intent(operation, attempt, player, kind, instruction.request(), null, null);
    }

    private JournalService ownedJournal(PartyAuthorization.Grant grant, Journal.Intent intent,
                                         JournalEngine.Registration handler) {
        var world = grant.target().world();
        var session = services.sessionId();
        return services.journal((actor, kind) -> kind.equals(intent.kind()) && grant.allows(world, session, actor, kind),
                handler == null ? Map.of() : Map.of(intent.kind(), handler), NO_PUBLICATION);
    }

    private PlayerId requireOwner(PartyAuthorization.Grant grant) {
        if (grant.target().owner() instanceof PartyRef.Player player
                && player.id().equals(grant.actor()) && grant.allowsOwnership(services.partyWorld(), services.sessionId())) {
            return player.id();
        }
        throw new IllegalArgumentException("Player ownership required");
    }

    private static SameCurrency.Revision revision(AccountIdentity account) {
        return new SameCurrency.Revision(account.id(), account.revision());
    }
}
