package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.foundation.PartyAuthorization;
import ca.emilybelnavis.mcmods.economy.valuation.ValuationIdentity;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Server-session reads only. Reference prices grant no payment, custody or qualification authority. */
public interface ItemValuationIntegration {
    int VERSION=1;
    int ADAPTER_VERSION=1;
    enum Status { READY, UNPRICED_OPERATOR, MOD_UNREGISTERED, MISSING_INPUT, UNSUPPORTED_IDENTITY,
        UNSUPPORTED_RECIPE, CYCLE, LIMIT_EXCEEDED, OVERFLOW, FX_UNAVAILABLE, SNAPSHOT_UNAVAILABLE, NOT_READY }
    enum Coverage { COMPLETE, SUPPORTED_PATH_WITH_EXCLUSIONS }
    record Identity(String itemId,String canonicalComponents,int adapterVersion) {
        public Identity { ValuationIdentity.itemId(itemId);canonicalComponents=ValuationIdentity.canonical(canonicalComponents);
            if(adapterVersion!=ADAPTER_VERSION)throw new IllegalArgumentException("Unsupported identity adapter"); }
    }
    sealed interface SnapshotSelector permits Current,Retained {}
    record Current() implements SnapshotSelector {}
    record Retained(UUID valuationSnapshot,UUID fxPublication) implements SnapshotSelector {
        public Retained {Objects.requireNonNull(valuationSnapshot);Objects.requireNonNull(fxPublication);}
    }
    record Request(Identity identity,long quantity,EconomyCurrency currency,SnapshotSelector selector) {
        public Request {Objects.requireNonNull(identity);Objects.requireNonNull(currency);Objects.requireNonNull(selector);
            if(quantity<1||quantity>1_000_000)throw new IllegalArgumentException("Quantity bound");}
    }
    record Exact(BigInteger numerator,BigInteger denominator) {
        public Exact {Objects.requireNonNull(numerator);Objects.requireNonNull(denominator);
            if(denominator.signum()<=0||numerator.bitLength()>4096||denominator.bitLength()>4096)throw new IllegalArgumentException("Exact bound");
            var gcd=numerator.gcd(denominator);numerator=numerator.divide(gcd);denominator=denominator.divide(gcd);}
    }
    record SnapshotRef(UUID valuationSnapshot,long revision,String contentHash,String configHash,String recipeHash,
                       String providerHash,int manifestVersion,String algorithm,int adapterVersion,UUID fxPublication,Instant fxEffectiveFrom) {}
    record Step(String hash,String item,String kind,String recipe,int outputCount,Exact exactUnitMinor,
                Exact processingValue,List<String> chosenItems,List<String> inputHashes,String sourceKind,String provider,String providerRevision) {
        public Step {chosenItems=List.copyOf(chosenItems);inputHashes=List.copyOf(inputHashes);}
    }
    record Explanation(String rootHash,List<Step> steps,boolean truncated) {
        public Explanation {steps=List.copyOf(steps);if(steps.size()>256)throw new IllegalArgumentException("Proof bound");}
    }
    record Result(Status status,String reasonCode,Coverage coverage,Identity identity,long quantity,
                  Optional<Exact> exactUnitMinor,Optional<Money> unitMoney,Optional<Money> totalMoney,
                  Instant capturedAt,Optional<Instant> effectiveFrom,Optional<SnapshotRef> snapshot,Optional<Explanation> explanation) {
        public Result {Objects.requireNonNull(status);Objects.requireNonNull(reasonCode);Objects.requireNonNull(coverage);Objects.requireNonNull(identity);Objects.requireNonNull(capturedAt);
            Objects.requireNonNull(exactUnitMinor);Objects.requireNonNull(unitMoney);Objects.requireNonNull(totalMoney);Objects.requireNonNull(effectiveFrom);Objects.requireNonNull(snapshot);Objects.requireNonNull(explanation);
            boolean ready=status==Status.READY;
            if(ready&&!(exactUnitMinor.isPresent()&&unitMoney.isPresent()&&totalMoney.isPresent()&&effectiveFrom.isPresent()&&snapshot.isPresent()&&explanation.isPresent()))throw new IllegalArgumentException("Incomplete ready value");
            if(!ready&&(exactUnitMinor.isPresent()||unitMoney.isPresent()||totalMoney.isPresent()||explanation.isPresent()))throw new IllegalArgumentException("Partial unavailable value");}
    }
    record SnapshotMetadata(UUID id,long revision,Instant publishedAt,String contentHash,int bytes) {}
    /** Cached read with an unchanged storage epoch and effective-FX time horizon; a miss starts bounded async work and returns PENDING. */
    Result currentCached(PartyAuthorization.Grant grant,Request request);
    CompletionStage<Result> evaluate(PartyAuthorization.Grant grant,Request request);
    CompletionStage<List<SnapshotMetadata>> history(PartyAuthorization.Grant grant,Optional<UUID> beforeSnapshot,int limit);
    default int version(){return VERSION;}

    record Base(String item,EconomyCurrency currency,String amount,long referenceCount) {
        public Base {new ca.emilybelnavis.mcmods.economy.valuation.ValuationConfig.Entry(item,currency,amount,referenceCount);}
    }
    record ExactSelector(Identity identity,Base value) {
        public ExactSelector {Objects.requireNonNull(identity);Objects.requireNonNull(value);if(!identity.itemId().equals(value.item()))throw new IllegalArgumentException("Selector item mismatch");}
    }
    record Material(String item,int numerator,int denominator) {
        public Material {ValuationIdentity.itemId(item);if(numerator<1||denominator<1||numerator>1_000_000||denominator>1_000_000)throw new IllegalArgumentException("Material factor");}
    }
    record MaterialRule(List<Material> materials,String contentCad) {
        public MaterialRule {materials=List.copyOf(materials);if(materials.isEmpty()||materials.size()>9)throw new IllegalArgumentException("Material rule bound");
            new ca.emilybelnavis.mcmods.economy.valuation.VariantGraph.Rule("SUM",materials.stream().map(m->new ca.emilybelnavis.mcmods.economy.valuation.VariantGraph.Term(m.item(),m.numerator(),m.denominator())).toList(),contentCad);}
    }
    record Contribution(String providerId,String revision,Set<String> ownedItemIds,List<Base> bases,List<ExactSelector> exactSelectors,
                        Set<String> derivationOptIns,Map<String,MaterialRule> variantRules,Set<String> trustedAdapterVersions) {
        public Contribution {ValuationIdentity.itemId(providerId);Objects.requireNonNull(revision);
            if(revision.isBlank()||revision.length()>128)throw new IllegalArgumentException("Revision bound");
            ownedItemIds=Set.copyOf(ownedItemIds);bases=List.copyOf(bases);exactSelectors=List.copyOf(exactSelectors);
            derivationOptIns=Set.copyOf(derivationOptIns);variantRules=Map.copyOf(variantRules);trustedAdapterVersions=Set.copyOf(trustedAdapterVersions);
            long componentBytes=0;
            for(var selector:exactSelectors)componentBytes+=selector.identity().canonicalComponents().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(componentBytes>1_048_576)throw new IllegalArgumentException("Contribution identity byte bound");
            if(ownedItemIds.size()>4096||bases.size()>4096||exactSelectors.size()>4096||derivationOptIns.size()>4096||variantRules.size()>4096||trustedAdapterVersions.size()>16)throw new IllegalArgumentException("Contribution bound");}
    }
    /** Opaque session handle authenticated from the calling loaded mod's module and namespace. */
    interface Provider {
        CompletionStage<SnapshotMetadata> register(Contribution contribution);
        CompletionStage<SnapshotMetadata> remove(String providerId);
    }
}
