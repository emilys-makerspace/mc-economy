package ca.emilybelnavis.mcmods.economy.trade.entity;

import ca.emilybelnavis.mcmods.economy.trade.catalog.TradeCatalog;
import ca.emilybelnavis.mcmods.economy.trade.stock.StockFacts;
import ca.emilybelnavis.mcmods.economy.trade.stock.StockStore;
import java.io.*;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;

/**
 * Original-operation native progression after durable dispatch, never financial admission.
 * The live attempt is deliberately not reconstructed after restart: an uncertain attempt is
 * retained for reconciliation instead of repeating XP/orbs, advancement or uses.
 */
public final class TradeProgression {
    private TradeProgression() {}
    private static final String RECEIPTS="economy_trade_progression_v1";
    private static final int MAX_RECEIPTS=16, MAX_SAVE_BYTES=1_048_576;
    public record StageResult(String reason,Attempt attempt) {
        public StageResult {Objects.requireNonNull(reason);if((attempt!=null)!=reason.equals("STAGED"))throw new IllegalArgumentException("Progression stage");}
    }
    public record ApplyResult(String reason,boolean applied) {}
    /** One server-session, one-use capability. It can only be created by staging a dispatched receipt. */
    public static final class Attempt {
        private final AbstractVillager trader;
        private final ServerPlayer actor;
        private final StockFacts.Progression intent;
        private final TradeCatalog.Offer offer;
        private final String nonce;
        private CompoundTag expected;
        private String phase="STARTED";
        private boolean consumed;
        private Attempt(AbstractVillager trader,ServerPlayer actor,StockFacts.Progression intent,TradeCatalog.Offer offer,String nonce) {
            this.trader=trader;this.actor=actor;this.intent=intent;this.offer=offer;this.nonce=nonce;
            expected=trader.saveWithoutId(new CompoundTag());bounded(expected);
        }
        public UUID operation(){return intent.originalOperation();}
        /** Detached expected state for a saver; it is not itself independent save evidence. */
        public CompoundTag expectedEntity(){return expected.copy();}
        public String phase(){return phase;}
    }
    /** Created only after comparison with independently read persisted entity NBT. */
    public static final class SaveProof {
        private final Attempt attempt;
        private final String phase;
        private final String evidence;
        private SaveProof(Attempt attempt,String phase,String evidence){this.attempt=attempt;this.phase=phase;this.evidence=evidence;}
        public String evidence(){return evidence;}
        public String phase(){return phase;}
    }

    /** The future settlement adapter must bind actor to the original financial intent. */
    public static StageResult stage(AbstractVillager trader,ServerPlayer actor,StockStore.FreshDispatch dispatch,TradeCatalog.Offer captured) {
        NativeCatalog.thread(trader);Objects.requireNonNull(actor);Objects.requireNonNull(dispatch);Objects.requireNonNull(captured);
        var intent=dispatch.claim();
        if(intent==null)return new StageResult("ORIGINAL_DISPATCH_ALREADY_CLAIMED",null);
        if(intent.stage()!=StockFacts.Stage.DISPATCHED)return new StageResult("ORIGINAL_NOT_DISPATCHED",null);
        if(!intent.trader().equals(trader.getUUID()) || intent.offerOrdinal()!=captured.ordinal()
                || !intent.offerIdentity().equals(captured.identity()) || intent.villagerXp()!=captured.villagerXp()
                || intent.executions()<1 || intent.executions()>1024)return new StageResult("ORIGINAL_CATALOG_MISMATCH",null);
        if(!available(trader,actor) || !NativeCatalog.matches(trader,captured))return new StageResult("TRADER_OR_CATALOG_UNAVAILABLE",null);
        var receipts=trader.getPersistentData().getCompound(RECEIPTS);String key=intent.originalOperation().toString();
        if(receipts.contains(key)) {
            var old=receipts.getCompound(key);
            if(!same(old,intent,actor.getUUID()))return new StageResult("ORIGINAL_MARKER_CONFLICT",null);
            return new StageResult(old.getString("phase").equals("APPLIED")?"ORIGINAL_APPLIED_REQUIRES_SAVE_WITNESS":"ORIGINAL_AMBIGUOUS",null);
        }
        if(receipts.size()>=MAX_RECEIPTS)return new StageResult("PROGRESSION_RECEIPT_BOUND",null);
        var nativeOffer=trader.getOffers().get(captured.ordinal());
        try {
            Math.addExact(nativeOffer.getUses(),intent.executions());
            Math.addExact(trader.getVillagerXp(),Math.multiplyExact(intent.villagerXp(),intent.executions()));
        }catch(ArithmeticException overflow){return new StageResult("PROGRESSION_INTEGER_BOUND",null);}
        var marker=new CompoundTag();marker.putUUID("operation",intent.originalOperation());marker.putUUID("trader",intent.trader());
        marker.putUUID("actor",actor.getUUID());marker.putLong("catalog",intent.catalogRevision());marker.putInt("ordinal",intent.offerOrdinal());
        marker.putString("offer",intent.offerIdentity());marker.putInt("executions",intent.executions());marker.putInt("xp",intent.villagerXp());
        String nonce=UUID.randomUUID().toString();marker.putString("nonce",nonce);marker.putString("phase","STARTED");
        receipts.put(key,marker);trader.getPersistentData().put(RECEIPTS,receipts);
        return new StageResult("STAGED",new Attempt(trader,actor,intent,captured,nonce));
    }

    /** Recover an independently saved APPLIED marker; this capability can never execute effects. */
    public static SaveProof witnessApplied(AbstractVillager trader,ServerPlayer actor,StockFacts.Progression intent,
                                           TradeCatalog.Offer captured,CompoundTag independentlyReadEntity,String reference) {
        NativeCatalog.thread(trader);Objects.requireNonNull(actor);Objects.requireNonNull(intent);Objects.requireNonNull(captured);
        var marker=trader.getPersistentData().getCompound(RECEIPTS).getCompound(intent.originalOperation().toString());
        if((intent.stage()!=StockFacts.Stage.DISPATCHED&&intent.stage()!=StockFacts.Stage.AMBIGUOUS) || !intent.trader().equals(trader.getUUID())
                || !intent.offerIdentity().equals(captured.identity()) || !same(marker,intent,actor.getUUID())
                || !marker.getString("phase").equals("APPLIED") || marker.getString("nonce").isEmpty())
            throw new IllegalArgumentException("Original applied marker unavailable");
        var attempt=new Attempt(trader,actor,intent,captured,marker.getString("nonce"));
        attempt.phase="APPLIED";attempt.consumed=true;
        return witness(attempt,independentlyReadEntity,reference);
    }

    /**
     * Caller is the trusted saver and must read actual persisted entity storage, not pass a
     * live saveWithoutId snapshot. This method compares that detached independent read with
     * the attempt's expected marker and native offer/progression state.
     */
    public static SaveProof witness(Attempt attempt,CompoundTag independentlyReadEntity,String reference) {
        Objects.requireNonNull(attempt);Objects.requireNonNull(independentlyReadEntity);
        NativeCatalog.thread(attempt.trader);
        if(reference==null || reference.isBlank() || reference.length()>512)throw new IllegalArgumentException("Persisted entity save reference");
        if(!independentlyReadEntity.hasUUID("UUID") || !independentlyReadEntity.getUUID("UUID").equals(attempt.intent.trader()))
            throw new IllegalArgumentException("Persisted entity identity");
        for(String field:List.of("Offers","Xp","VillagerData"))
            if(!Objects.equals(attempt.expected.get(field),independentlyReadEntity.get(field)))throw new IllegalArgumentException("Persisted progression state mismatch");
        var expectedMarker=marker(attempt.expected,attempt.operation());var actualMarker=marker(independentlyReadEntity,attempt.operation());
        if(!expectedMarker.equals(actualMarker) || !actualMarker.getString("phase").equals(attempt.phase)
                || !actualMarker.getString("nonce").equals(attempt.nonce))throw new IllegalArgumentException("Persisted original marker mismatch");
        String digest=digest(bounded(independentlyReadEntity));
        return new SaveProof(attempt,attempt.phase,"trade-progression-v1:"+attempt.operation()+":"+attempt.phase+":"+digest+":"+reference);
    }

    public static ApplyResult apply(Attempt attempt,SaveProof proof) {
        Objects.requireNonNull(attempt);Objects.requireNonNull(proof);NativeCatalog.thread(attempt.trader);
        if(proof.attempt!=attempt || !proof.phase.equals("STARTED") || attempt.consumed || !attempt.phase.equals("STARTED"))
            return new ApplyResult("ORIGINAL_NOT_REEXECUTED",false);
        var trader=attempt.trader;var actor=attempt.actor;
        if(!available(trader,actor) || !NativeCatalog.matches(trader,attempt.offer))return new ApplyResult("TRADER_OR_CATALOG_UNAVAILABLE",false);
        var current=trader.getPersistentData().getCompound(RECEIPTS).getCompound(attempt.operation().toString());
        if(!current.equals(marker(attempt.expected,attempt.operation())))return new ApplyResult("ORIGINAL_MARKER_CONFLICT",false);
        attempt.consumed=true;setPhase(attempt,"APPLYING");
        var oldPlayer=trader.getTradingPlayer();
        try {
            trader.setTradingPlayer(actor);
            TradeHooks.withProgression(trader,()->{
                var offer=trader.getOffers().get(attempt.offer.ordinal());
                for(int i=0;i<attempt.intent.executions();i++)trader.notifyTrade(offer);
            });
            setPhase(attempt,"APPLIED");
            return new ApplyResult("APPLIED_REQUIRES_SAVE_WITNESS",true);
        }catch(RuntimeException uncertain) {
            setPhase(attempt,"AMBIGUOUS");return new ApplyResult("ORIGINAL_AMBIGUOUS",false);
        }finally{trader.setTradingPlayer(oldPlayer);}
    }

    /** Remove only an independently witnessed, already durably acknowledged original. */
    public static void releaseAcknowledged(AbstractVillager trader,StockFacts.Progression intent,SaveProof proof) {
        NativeCatalog.thread(trader);Objects.requireNonNull(intent);Objects.requireNonNull(proof);
        if(intent.stage()!=StockFacts.Stage.ACKNOWLEDGED || !intent.originalOperation().equals(proof.attempt.operation())
                || !intent.trader().equals(trader.getUUID()) || !proof.phase.equals("APPLIED")
                || !intent.evidence().equals(proof.evidence()))throw new IllegalArgumentException("Original progression acknowledgment");
        var receipts=trader.getPersistentData().getCompound(RECEIPTS);receipts.remove(intent.originalOperation().toString());
        trader.getPersistentData().put(RECEIPTS,receipts);
    }
    private static boolean available(AbstractVillager trader,ServerPlayer actor) {
        return trader.isAlive() && actor.isAlive() && actor.server==trader.getServer() && actor.serverLevel()==trader.level()
                && actor.server.getPlayerList().getPlayer(actor.getUUID())==actor
                && (trader.getTradingPlayer()==null || trader.getTradingPlayer()==actor);
    }
    private static boolean same(CompoundTag marker,StockFacts.Progression intent,UUID actor) {
        return marker.hasUUID("operation") && marker.getUUID("operation").equals(intent.originalOperation())
                && marker.hasUUID("trader") && marker.getUUID("trader").equals(intent.trader())
                && marker.hasUUID("actor") && marker.getUUID("actor").equals(actor)
                && marker.getLong("catalog")==intent.catalogRevision() && marker.getInt("ordinal")==intent.offerOrdinal()
                && marker.getString("offer").equals(intent.offerIdentity()) && marker.getInt("executions")==intent.executions()
                && marker.getInt("xp")==intent.villagerXp();
    }
    private static CompoundTag marker(CompoundTag saved,UUID operation) {
        return saved.getCompound("NeoForgeData").getCompound(RECEIPTS).getCompound(operation.toString());
    }
    private static void setPhase(Attempt attempt,String phase) {
        var receipts=attempt.trader.getPersistentData().getCompound(RECEIPTS);var marker=receipts.getCompound(attempt.operation().toString());
        marker.putString("phase",phase);receipts.put(attempt.operation().toString(),marker);attempt.trader.getPersistentData().put(RECEIPTS,receipts);
        attempt.phase=phase;attempt.expected=attempt.trader.saveWithoutId(new CompoundTag());bounded(attempt.expected);
    }
    private static byte[] bounded(CompoundTag entity) {
        try {
            var bytes=new ByteArrayOutputStream();
            NbtIo.write(entity,new DataOutputStream(new FilterOutputStream(bytes){
                private int count;
                @Override public void write(int value)throws IOException{if(++count>MAX_SAVE_BYTES)throw new IOException("Entity save bound");out.write(value);}
                @Override public void write(byte[] value,int offset,int length)throws IOException{count=Math.addExact(count,length);if(count>MAX_SAVE_BYTES)throw new IOException("Entity save bound");out.write(value,offset,length);}
            }));return bytes.toByteArray();
        }catch(IOException failure){throw new IllegalArgumentException("Entity save bound",failure);}
    }
    private static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
