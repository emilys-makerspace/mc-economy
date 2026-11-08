package ca.emilybelnavis.mcmods.economy.trade.custody;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.cash.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.catalog.TradeCatalog;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Server-thread exact inventory custody; no menus, drops, financial authority or automatic retry. */
public final class TraderInventory {
    private TraderInventory() {}
    private static final String INPUTS="economy_npc_inputs_v1",OUTPUTS="economy_npc_outputs_v1";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<ServerPlayer,Map<String,Proof>> PROOFS=Collections.synchronizedMap(new WeakHashMap<>());
    public record Slot(int index,int count,String exactFullStack) {
        public Slot {if(index<0||index>=36||count<1||count>64||exactFullStack==null||exactFullStack.isEmpty()||exactFullStack.length()>2048)throw new IllegalArgumentException("Exact NPC slot");}
    }
    public record Snapshot(WorldId world,PlayerId owner,UUID original,List<Slot> slots) {
        public Snapshot {
            Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(original);slots=List.copyOf(slots);
            if(slots.isEmpty()||slots.size()>24)throw new IllegalArgumentException("NPC input count");
            int previous=-1;for(var slot:slots){if(slot.index()<=previous)throw new IllegalArgumentException("NPC input order");previous=slot.index();}
        }
        public String encode(){String value=JSON.toJson(this);if(value.length()>4096)throw new IllegalArgumentException("NPC custody payload bound");return value;}
    }
    public static Snapshot decode(String payload) {
        if(payload==null||payload.length()>4096)throw new IllegalArgumentException("NPC custody payload bound");
        try{var value=JSON.fromJson(payload,Snapshot.class);if(value==null||!value.encode().equals(payload))throw new IllegalArgumentException("Noncanonical NPC inputs");return value;}
        catch(JsonParseException malformed){throw new IllegalArgumentException("NPC input codec",malformed);}
    }
    public static Snapshot snapshot(ServerPlayer player,WorldId world,UUID original,Map<Integer,Integer> selectedCounts) {
        thread(player);var slots=new ArrayList<Slot>();
        for(var selection:new TreeMap<>(selectedCounts).entrySet()) {
            int index=selection.getKey(),count=selection.getValue();if(index<0||index>=36)throw new IllegalArgumentException("NPC input slot");
            var stack=player.getInventory().getItem(index);if(stack.isEmpty()||count<1||count>stack.getCount())throw new IllegalArgumentException("NPC input quantity");
            validateCash(stack,world);slots.add(new Slot(index,count,BankingInventory.encode(player,stack)));
        }
        var result=new Snapshot(world,new PlayerId(player.getUUID()),original,slots);result.encode();return result;
    }
    /** Cash indices are snapshot ordinals, preserving the <=24-slot cash composition contract. */
    public static List<Deposits.Slot> cashSlots(ServerPlayer player,Snapshot snapshot) {
        thread(player);owner(player,snapshot.owner());var cash=new ArrayList<Deposits.Slot>();
        for(int ordinal=0;ordinal<snapshot.slots().size();ordinal++) {
            var slot=snapshot.slots().get(ordinal);var full=BankingInventory.decode(player,slot.exactFullStack());
            if(full.getCount()<slot.count())throw new IllegalArgumentException("NPC selected quantity");
            var stack=full.copyWithCount(slot.count());var denomination=CashCatalog.find(stack);if(denomination.isEmpty())continue;
            validateCash(stack,snapshot.world());boolean unmarked=!stack.has(CashStackIdentity.CASH_LOT.get());
            String encoded=BankingInventory.encode(player,stack);CashIssuance.Claim claim;
            if(unmarked) {
                UUID lot=UUID.nameUUIDFromBytes(("economy:npc-unmarked-v1|"+snapshot.world().value()+"|"+snapshot.owner().value()+"|"+snapshot.original()+"|"+slot.index()+"|"+encoded).getBytes(StandardCharsets.UTF_8));
                claim=new CashIssuance.Claim(snapshot.world(),lot,"economy:"+denomination.get().id(),stack.getCount());
            }else claim=CashStackIdentity.claim(stack,snapshot.world()).orElseThrow();
            cash.add(new Deposits.Slot(ordinal,claim,encoded,unmarked));
        }
        return List.copyOf(cash);
    }
    /** Exact selected noncash inputs must satisfy all native goods/secondary predicates, without extras. */
    public static boolean matchesRequirements(ServerPlayer player,Snapshot snapshot,String requirements) {
        thread(player);owner(player,snapshot.owner());
        try {
            if(requirements==null||requirements.length()>4096)return false;
            var array=JsonParser.parseString(requirements).getAsJsonArray();if(array.size()>2)return false;
            var remaining=new ArrayList<ItemStack>();
            for(var slot:snapshot.slots()) {
                var full=BankingInventory.decode(player,slot.exactFullStack());if(full.getCount()<slot.count())return false;
                var selected=full.copyWithCount(slot.count());validateCash(selected,snapshot.world());
                if(CashCatalog.find(selected).isEmpty())remaining.add(selected);
            }
            var entries=new ArrayList<JsonObject>();for(var entry:array)entries.add(entry.getAsJsonObject());
            entries.sort(Comparator.comparing(entry->JSON.fromJson(entry.get("goods"),TradeCatalog.Goods.class).match()));
            for(var entry:entries) {
                if(!entry.keySet().equals(Set.of("goods","count")))return false;
                var goods=JSON.fromJson(entry.get("goods"),TradeCatalog.Goods.class);long needed=entry.get("count").getAsLong();
                if(needed<1||needed>65_536)return false;
                for(var stack:remaining)if(!stack.isEmpty()&&matches(player,stack,goods)) {
                    int taken=(int)Math.min(needed,stack.getCount());stack.shrink(taken);needed-=taken;if(needed==0)break;
                }
                if(needed!=0)return false;
            }
            return remaining.stream().allMatch(ItemStack::isEmpty);
        }catch(RuntimeException unsupported){return false;}
    }
    /** Complete-batch capacity against the post-removal inventory, before consuming any input. */
    public static boolean fitsCombined(ServerPlayer player,Snapshot inputs,List<ItemStack> allOutputs) {
        thread(player);var inventory=copyInventory(player);
        if(inputs!=null) {
            owner(player,inputs.owner());
            for(var slot:inputs.slots()) {
                var stack=inventory.get(slot.index());if(stack.isEmpty()||!BankingInventory.encode(player,stack).equals(slot.exactFullStack())||stack.getCount()<slot.count())return false;
                stack.shrink(slot.count());
            }
        }
        return fits(inventory,allOutputs);
    }
    /** Durable RESERVED custody precedes this operation; HELD must be independently saved next. */
    public static String reserve(ServerPlayer player,Custody.Case custody,Snapshot snapshot,List<ItemStack> allOutputs) {
        thread(player);owner(player,snapshot.owner());boundInput(custody,snapshot);
        var originals=player.getPersistentData().getCompound(INPUTS);String key=custody.id().toString();
        if(originals.contains(key))return originals.getCompound(key).getString("phase").equals("HELD")?"ORIGINAL_HELD_REQUIRES_SAVE":"ORIGINAL_AMBIGUOUS";
        if(originals.size()>=16)return "INPUT_RECEIPT_BOUND";
        if(!fitsCombined(player,snapshot,allOutputs))return "CAPACITY_OR_INPUT_CHANGED";
        var marker=baseMarker(custody,snapshot.world());marker.putString("phase","RESERVING");marker.putString("snapshot",snapshot.encode());
        originals.put(key,marker);player.getPersistentData().put(INPUTS,originals);
        try {
            for(var slot:snapshot.slots()) {
                var stack=player.getInventory().getItem(slot.index());
                if(!BankingInventory.encode(player,stack).equals(slot.exactFullStack()))throw new IllegalStateException("NPC original changed");
                player.getInventory().removeItem(slot.index(),slot.count());
            }
            marker.putString("phase","HELD");originals.put(key,marker);player.getPersistentData().put(INPUTS,originals);player.getInventory().setChanged();return "HELD_REQUIRES_SAVE";
        }catch(RuntimeException ambiguous){marker.putString("phase","AMBIGUOUS");originals.put(key,marker);player.getPersistentData().put(INPUTS,originals);return "ORIGINAL_AMBIGUOUS";}
    }
    public static boolean held(ServerPlayer player,Custody.Case custody,Snapshot snapshot) {
        thread(player);owner(player,snapshot.owner());
        var marker=player.getPersistentData().getCompound(INPUTS).getCompound(custody.id().toString());
        return same(marker,custody,snapshot.world())&&marker.getString("phase").equals("HELD")&&marker.getString("snapshot").equals(snapshot.encode());
    }
    /** Retire only exact originals whose financial outcome is already COMMITTED. */
    public static void consumeCommitted(ServerPlayer player,Custody.Case custody) {
        thread(player);owner(player,custody.terms().owner());
        if(custody.state()!=Custody.State.COMMITTED)throw new IllegalArgumentException("Committed NPC input required");
        var originals=player.getPersistentData().getCompound(INPUTS);var marker=originals.getCompound(custody.id().toString());
        if(marker.getString("phase").equals("CONSUMED"))return;
        var snapshot=decode(marker.getString("snapshot"));
        if(!held(player,custody,snapshot)||!custody.terms().items().equals(snapshot.encode()))throw new IllegalArgumentException("Exact held NPC input required");
        marker.putString("phase","CONSUMED");originals.put(custody.id().toString(),marker);player.getPersistentData().put(INPUTS,originals);
    }
    /**
     * Transfer known saved, never financially committed originals into an explicit return
     * claim. The worker owns cancellation and cash-lock release; no item is inserted here.
     */
    public static String stageInputReturn(ServerPlayer player,Custody.Case input,Custody.Case returned,
                                           Snapshot snapshot,Proof savedHeld) {
        thread(player);owner(player,snapshot.owner());Objects.requireNonNull(savedHeld);
        requireReturnLink(input,returned,snapshot);
        if(!savedHeld.custody.equals(input.id())||!savedHeld.world.equals(snapshot.world())||!savedHeld.phase.equals("HELD")
                ||(savedHeld.evidence.kind()!=Custody.EvidenceKind.RESERVATION_SAVED&&savedHeld.evidence.kind()!=Custody.EvidenceKind.UNDELIVERED)
                ||!same(savedHeld.marker,input,snapshot.world()))
            throw new IllegalArgumentException("Saved known-undelivered original return required");
        var originals=player.getPersistentData().getCompound(INPUTS);var marker=originals.getCompound(input.id().toString());
        if(marker.getString("phase").equals("RETURN_CLAIM")&&marker.hasUUID("return")&&marker.getUUID("return").equals(returned.id()))return "RETURN_ALREADY_STAGED";
        if(!marker.equals(savedHeld.marker)||!marker.getString("snapshot").equals(snapshot.encode()))throw new IllegalArgumentException("Held original return changed");
        marker.putString("phase","RETURN_CLAIM");marker.putUUID("return",returned.id());originals.put(input.id().toString(),marker);player.getPersistentData().put(INPUTS,originals);
        return "RETURN_STAGED_REQUIRES_DELIVERY_SAVE";
    }

    private static void requireReturnLink(Custody.Case input,Custody.Case returned,Snapshot snapshot) {
        if(input.state()!=Custody.State.QUARANTINED||input.financialOperation()!=null
                ||!input.terms().owner().equals(snapshot.owner())||!input.terms().items().equals(snapshot.encode())
                ||!input.terms().financialIntent().startsWith(snapshot.original()+"\n")
                ||returned.state()!=Custody.State.CLAIM||!returned.reservationOperation().equals(input.id())
                ||returned.financialOperation()==null||returned.financialOperation().equals(snapshot.original())
                ||!returned.terms().owner().equals(snapshot.owner())||!returned.terms().items().equals(snapshot.encode())
                ||!returned.terms().source().equals(input.terms().source())||!returned.terms().destination().equals(input.terms().source()))
            throw new IllegalArgumentException("Exact original return link required");
    }

    /** Recover a saved return or no-effect claim before starting a new delivery attempt. */
    public static void prepareDelivery(ServerPlayer player,Custody.Case current,Custody.Case originalInput,WorldId world) {
        thread(player);owner(player,current.terms().owner());
        if(current.state()!=Custody.State.COMMITTED&&current.state()!=Custody.State.CLAIM)return;

        var outputs=player.getPersistentData().getCompound(OUTPUTS);
        String key=current.id().toString();
        if(outputs.contains(key)) {
            var marker=outputs.getCompound(key);
            // CLAIM was written after independently verifying this exact attempt had no effect.
            if(current.state()!=Custody.State.CLAIM||current.deliveryAttempt()==null||!marker.hasUUID("attempt")
                    ||!same(marker,current,world)||!marker.getString("phase").equals("UNDELIVERED")
                    ||!savedUndelivered(current,world,current.id()))
                throw new IllegalArgumentException("Existing NPC delivery attempt requires reconciliation");
            outputs.remove(key);
            player.getPersistentData().put(OUTPUTS,outputs);
        }
        if(outputs.size()>=16)throw new IllegalArgumentException("NPC output receipt limit");

        if(current.state()==Custody.State.CLAIM&&current.terms().items().startsWith("{")) {
            var snapshot=decode(current.terms().items());
            var marker=player.getPersistentData().getCompound(INPUTS).getCompound(current.reservationOperation().toString());
            if(marker.getString("phase").equals("RETURN_CLAIM"))return;
            // A close or restart can interrupt staging after the return claim commits.
            if(originalInput==null||!snapshot.world().equals(world)||current.deliveryAttempt()!=null
                    ||!savedUndelivered(current,world,originalInput.id()))
                throw new IllegalArgumentException("Saved known-undelivered original return required");
            requireReturnLink(originalInput,current,snapshot);
            if(!held(player,originalInput,snapshot))throw new IllegalArgumentException("Exact held originals required");
            stageInputReturn(player,originalInput,current,snapshot,heldReturnProof(player,originalInput,world));
        }
    }

    private static boolean savedUndelivered(Custody.Case claim,WorldId world,UUID savedCase) {
        var evidence=claim.evidence();
        return evidence.kind()==Custody.EvidenceKind.UNDELIVERED
                &&evidence.location().equals(claim.terms().destination())
                &&evidence.saveToken().startsWith("bank-v1:"+world.value()+":"+savedCase+":");
    }

    /** Opaque capability minted only by a genuinely new authoritative delivery transition. */
    public static final class FreshDelivery {
        private Custody.Case delivery;
        private final WorldId world;
        private FreshDelivery(Custody.Case delivery,WorldId world){this.delivery=delivery;this.world=world;}
        private synchronized Custody.Case claim(){var value=delivery;delivery=null;return value;}
    }
    /** Worker-only: original outcome lookup stays in JournalEngine; recovered DELIVERING returns null. */
    public static FreshDelivery beginDelivery(FoundationStore store,Journal.Intent intent,Custody.Authority authority,Custody.Capacity capacity) {
        Custody.Action action=intent.kind().equals(Custody.Action.BEGIN_DELIVERY.kind())?Custody.Action.BEGIN_DELIVERY:
                intent.kind().equals(Custody.Action.COLLECT.kind())?Custody.Action.COLLECT:null;
        if(action==null)throw new IllegalArgumentException("NPC delivery action");
        UUID id=UUID.fromString(intent.request().fields().get("custody"));
        var before=store.transaction(tx->Custody.find(tx::query,intent.actor(),id,false).orElse(null));
        if(before==null||before.state()!=(action==Custody.Action.BEGIN_DELIVERY?Custody.State.COMMITTED:Custody.State.CLAIM))return null;
        var ordinary=Custody.registration(action,authority,(c,e,r)->false,null,capacity);
        boolean[] prepared={false};
        var registration=new JournalEngine.Registration(ordinary.canonicalVersion(),(candidate,reads)->{
            var decision=ordinary.handler().prepare(candidate,reads);
            if(decision instanceof JournalEngine.Commit)prepared[0]=true;
            return decision;
        });
        var engine=new JournalEngine(store,(actor,kind)->actor.equals(intent.actor())&&kind.equals(action.kind()),Map.of(action.kind(),registration));
        var outcome=engine.submit(intent);if(outcome.status()!=Journal.Status.COMMITTED||!prepared[0])return null;
        var after=store.transaction(tx->Custody.find(tx::query,intent.actor(),id,false).orElseThrow());
        if(after.state()!=Custody.State.DELIVERING||!Objects.equals(after.deliveryAttempt(),intent.operationId().value())||after.revision()!=before.revision()+1)return null;
        return new FreshDelivery(after,store.snapshot().worldId());
    }
    public static final class Attempt {
        private final ServerPlayer player;private final Custody.Case delivery;private final WorldId world;private final List<ItemStack> outputs;
        private final CompoundTag preInventory;private final UUID generation;private boolean used;
        private Attempt(ServerPlayer player,Custody.Case delivery,WorldId world,List<ItemStack> outputs,UUID generation){this.player=player;this.delivery=delivery;this.world=world;this.outputs=outputs.stream().map(ItemStack::copy).toList();this.preInventory=inventoryTag(player);this.generation=generation;}
        public UUID custody(){return delivery.id();}
    }
    public record Started(String reason,Attempt attempt){}
    public static Started markDeliveryStart(ServerPlayer player,FreshDelivery fresh,WorldId world) {
        thread(player);Objects.requireNonNull(fresh);if(!fresh.world.equals(world))throw new IllegalArgumentException("NPC delivery world");
        var delivery=fresh.claim();if(delivery==null)return new Started("ORIGINAL_ATTEMPT_ALREADY_CLAIMED",null);
        owner(player,delivery.terms().owner());var markers=player.getPersistentData().getCompound(OUTPUTS);String key=delivery.id().toString();
        if(markers.contains(key))return new Started("ORIGINAL_ATTEMPT_AMBIGUOUS",null);
        if(markers.size()>=16)return new Started("OUTPUT_RECEIPT_BOUND",null);
        var outputs=outputStacks(player,delivery,world);var marker=baseMarker(delivery,world);marker.putUUID("attempt",delivery.deliveryAttempt());
        UUID generation=UUID.randomUUID();marker.putUUID("generation",generation);
        if(!BankingInventory.fits(player,outputs)) {
            marker.putString("phase","UNDELIVERED");markers.put(key,marker);player.getPersistentData().put(OUTPUTS,markers);return new Started("KNOWN_UNDELIVERED_REQUIRES_SAVE",null);
        }
        marker.putString("phase","STARTED");markers.put(key,marker);player.getPersistentData().put(OUTPUTS,markers);
        return new Started("STARTED_REQUIRES_SAVE",new Attempt(player,delivery,world,outputs,generation));
    }
    /** Actual A5 disk save/read verification, including integrated-owner level.dat. */
    public static Proof saveProof(ServerPlayer player,Custody.Case custody,WorldId world,boolean input) {
        return saveProof(player,custody,world,input,false);
    }
    /** Prove that these saved HELD originals remain undelivered for an explicit return claim. */
    public static Proof heldReturnProof(ServerPlayer player,Custody.Case input,WorldId world) {
        if(input.financialOperation()!=null)throw new IllegalArgumentException("Financially committed inputs cannot be returned");
        return saveProof(player,input,world,true,true);
    }
    private static Proof saveProof(ServerPlayer player,Custody.Case custody,WorldId world,boolean input,boolean returning) {
        thread(player);owner(player,custody.terms().owner());
        var marker=player.getPersistentData().getCompound(input?INPUTS:OUTPUTS).getCompound(custody.id().toString());
        if(!same(marker,custody,world))throw new IllegalArgumentException("NPC saved original binding");
        String phase=marker.getString("phase");Custody.EvidenceKind kind;
        if(returning&&!phase.equals("HELD"))throw new IllegalArgumentException("Known held originals required");
        if(input && phase.equals("HELD"))kind=returning?Custody.EvidenceKind.UNDELIVERED:Custody.EvidenceKind.RESERVATION_SAVED;
        else if(input && phase.equals("CONSUMED"))kind=Custody.EvidenceKind.UNKNOWN;
        else if(!input && phase.equals("DELIVERED"))kind=Custody.EvidenceKind.DELIVERY_SAVED;
        else if(!input && phase.equals("UNDELIVERED"))kind=Custody.EvidenceKind.UNDELIVERED;
        else if(!input && phase.equals("STARTED"))kind=Custody.EvidenceKind.UNKNOWN;
        else throw new IllegalArgumentException("NPC original is ambiguous");
        var saved=BankingSaves.player(player,world,custody.id());
        var evidence=new Custody.Evidence(kind,saved.reference(),input?custody.terms().source():custody.terms().destination(),saved.token());
        var proof=new Proof(custody.id(),world,phase,evidence,marker.copy());
        var retained=PROOFS.computeIfAbsent(player,ignored->new HashMap<>());
        retained.values().removeIf(old->old.custody.equals(custody.id())&&old.phase.equals(phase));
        if(retained.size()>=64)throw new IllegalStateException("NPC save proof bound");
        retained.put(evidence.saveToken(),proof);return proof;
    }
    public static final class Proof {
        private final UUID custody;private final WorldId world;private final String phase;private final Custody.Evidence evidence;private final CompoundTag marker;
        private Proof(UUID custody,WorldId world,String phase,Custody.Evidence evidence,CompoundTag marker){this.custody=custody;this.world=world;this.phase=phase;this.evidence=evidence;this.marker=marker;}
        public Custody.Evidence evidence(){return evidence;}public String phase(){return phase;}
    }
    public static Custody.EvidenceVerifier verifier(ServerPlayer player) {
        // Capture only immutable proofs on the server thread; the returned verifier may run on the worker.
        thread(player);var proofs=Map.copyOf(PROOFS.getOrDefault(player,Map.of()));
        return (current,evidence,reads)->{var proof=proofs.get(evidence.saveToken());return proof!=null&&proof.custody.equals(current.id())&&proof.evidence.equals(evidence)&&same(proof.marker,current,proof.world);};
    }
    /** Retire a saved consumed-input marker only after the original financial commit. */
    public static void clearConsumed(ServerPlayer player,Custody.Case committed,Proof proof) {
        thread(player);owner(player,committed.terms().owner());
        if(committed.state()!=Custody.State.COMMITTED||committed.financialOperation()==null||!proof.phase.equals("CONSUMED"))
            throw new IllegalArgumentException("Saved committed NPC originals required");
        clear(player,committed,proof,INPUTS);
    }
    public static void clearDelivered(ServerPlayer player,Custody.Case delivered,Proof proof) {
        thread(player);owner(player,delivered.terms().owner());
        if(delivered.state()!=Custody.State.DELIVERED||!proof.phase.equals("DELIVERED")||!delivered.evidence().equals(proof.evidence))
            throw new IllegalArgumentException("Saved terminal NPC delivery required");
        if(delivered.terms().items().startsWith("{")) {
            var snapshot=decode(delivered.terms().items());returnOriginals(player,delivered,snapshot);
        }
        clear(player,delivered,proof,OUTPUTS);
        if(delivered.terms().items().startsWith("{")) {
            var originals=player.getPersistentData().getCompound(INPUTS);originals.remove(delivered.reservationOperation().toString());player.getPersistentData().put(INPUTS,originals);
        }
    }
    /** A saved no-effect claim can begin a fresh collection attempt; uncertainty cannot. */
    public static void clearUndeliveredClaim(ServerPlayer player,Custody.Case claim,Proof proof) {
        thread(player);owner(player,claim.terms().owner());
        if(claim.state()!=Custody.State.CLAIM||!proof.phase.equals("UNDELIVERED")||!claim.evidence().equals(proof.evidence))
            throw new IllegalArgumentException("Saved known-undelivered NPC claim required");
        clear(player,claim,proof,OUTPUTS);
    }
    /**
     * Only a fresh live attempt with an independently saved STARTED marker can insert outputs.
     * Fetch current asynchronously from the owning worker after the save; the server thread
     * never queries SQLite. Claim, quarantine, compensation or a replaced attempt rejects.
     */
    public static String deliverOnce(Attempt attempt,Proof started,Custody.Case current) {
        Objects.requireNonNull(attempt);Objects.requireNonNull(started);Objects.requireNonNull(current);var player=attempt.player;thread(player);
        if(!current.equals(attempt.delivery)||current.state()!=Custody.State.DELIVERING
                ||!Objects.equals(current.deliveryAttempt(),attempt.delivery.deliveryAttempt()))return "ORIGINAL_CUSTODY_CHANGED";
        if(attempt.used||!started.custody.equals(attempt.delivery.id())||!started.world.equals(attempt.world)||!started.phase.equals("STARTED")
                ||!started.marker.hasUUID("generation")||!started.marker.getUUID("generation").equals(attempt.generation))return "ORIGINAL_NOT_REEXECUTED";
        var markers=player.getPersistentData().getCompound(OUTPUTS);var marker=markers.getCompound(attempt.delivery.id().toString());
        if(!marker.equals(started.marker))return "ORIGINAL_MARKER_CONFLICT";
        attempt.used=true;
        if(!inventoryTag(player).equals(attempt.preInventory)||!BankingInventory.fits(player,attempt.outputs)) {
            marker.putString("phase","UNDELIVERED");markers.put(attempt.delivery.id().toString(),marker);player.getPersistentData().put(OUTPUTS,markers);return "KNOWN_UNDELIVERED_REQUIRES_SAVE";
        }
        marker.putString("phase","APPLYING");markers.put(attempt.delivery.id().toString(),marker);player.getPersistentData().put(OUTPUTS,markers);
        try {
            for(var output:attempt.outputs)insertMain(player,output.copy());
            marker.putString("phase","DELIVERED");markers.put(attempt.delivery.id().toString(),marker);player.getPersistentData().put(OUTPUTS,markers);player.getInventory().setChanged();return "DELIVERED_REQUIRES_SAVE";
        }catch(RuntimeException uncertain){marker.putString("phase","AMBIGUOUS");markers.put(attempt.delivery.id().toString(),marker);player.getPersistentData().put(OUTPUTS,markers);return "ORIGINAL_AMBIGUOUS";}
    }
    /** Decode only a committed exact-goods or original-cash custody descriptor. */
    public static List<ItemStack> outputStacks(ServerPlayer player,Custody.Case delivery,WorldId world) {
        thread(player);owner(player,delivery.terms().owner());if(delivery.financialOperation()==null)throw new IllegalArgumentException("Committed NPC outputs required");
        String payload=delivery.terms().items();var outputs=new ArrayList<ItemStack>();
        if(payload.startsWith("{")) {
            var snapshot=decode(payload);if(!snapshot.world().equals(world))throw new IllegalArgumentException("Original return world");
            returnOriginals(player,delivery,snapshot);
            for(var slot:snapshot.slots()) {
                var original=BankingInventory.decode(player,slot.exactFullStack());if(original.getCount()<slot.count())throw new IllegalArgumentException("Original return quantity");
                var stack=original.copyWithCount(slot.count());validateCash(stack,world);outputs.add(stack);
            }
        }else if(payload.startsWith("withdrawal-cash-v1|")) {
            for(var claim:WithdrawalDelivery.claims(delivery)) {
                if(!claim.world().equals(world))throw new IllegalArgumentException("Foreign cash output world");
                var denomination=CashCatalog.byId(claim.item()).orElseThrow();outputs.add(CashStackIdentity.marked(new ItemStack(denomination.item().get(),claim.count()),world,claim.lot()));
            }
        }else {
            var array=JsonParser.parseString(payload).getAsJsonArray();if(array.isEmpty()||array.size()>24)throw new IllegalArgumentException("NPC goods output count");
            for(var element:array) {
                var object=element.getAsJsonObject();if(!object.keySet().equals(Set.of("goods","count")))throw new IllegalArgumentException("NPC goods output shape");
                var goods=JSON.fromJson(object.get("goods"),TradeCatalog.Goods.class);long count=object.get("count").getAsLong();
                if(count<1||count>2304||goods.match()!=TradeCatalog.Match.EXACT_COMPONENTS)throw new IllegalArgumentException("Exact output goods required");
                var components=JsonParser.parseString(goods.components()).getAsJsonObject();
                if(!components.keySet().equals(Set.of("values","patch")))throw new IllegalArgumentException("Exact output component manifest");
                var ops=player.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                var patch=DataComponentPatch.CODEC.parse(ops,components.get("patch")).getOrThrow();
                var item=BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(goods.item())).orElseThrow();
                var stack=new ItemStack(item,1);stack.applyComponents(patch);
                var encoded=DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow();
                if(!encoded.equals(components.get("values")))throw new IllegalArgumentException("Output full components changed");
                int max=Math.min(64,stack.getMaxStackSize());
                while(count>0){int amount=(int)Math.min(count,max);outputs.add(stack.copyWithCount(amount));count-=amount;}
            }
        }
        if(outputs.size()>36)throw new IllegalArgumentException("Combined NPC output stack bound");return List.copyOf(outputs);
    }
    private static void boundInput(Custody.Case custody,Snapshot snapshot) {
        if(custody.state()!=Custody.State.RESERVED||!custody.terms().owner().equals(snapshot.owner())||!custody.terms().items().equals(snapshot.encode())
                ||!custody.terms().financialIntent().startsWith(snapshot.original()+"\n"))throw new IllegalArgumentException("Reserved exact NPC input required");
    }
    private static boolean matches(ServerPlayer player,ItemStack stack,TradeCatalog.Goods goods) {
        if(!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(goods.item()))return false;
        var components=JsonParser.parseString(goods.components()).getAsJsonObject();var ops=player.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        if(goods.match()==TradeCatalog.Match.REQUIRED_COMPONENTS) {
            if(!components.keySet().equals(Set.of("required")))return false;
            var predicate=DataComponentPredicate.CODEC.parse(ops,components.get("required")).getOrThrow();return predicate.test(stack);
        }
        if(!components.keySet().equals(Set.of("values","patch")))return false;
        for(var component:stack.getComponents())if(component.type().isTransient())return false;
        for(var component:stack.getComponentsPatch().entrySet())if(component.getKey().isTransient())return false;
        return DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow().equals(components.get("values"))
                &&DataComponentPatch.CODEC.encodeStart(ops,stack.getComponentsPatch()).getOrThrow().equals(components.get("patch"));
    }
    private static void clear(ServerPlayer player,Custody.Case custody,Proof proof,String key) {
        Objects.requireNonNull(proof);var markers=player.getPersistentData().getCompound(key);var marker=markers.getCompound(custody.id().toString());
        if(!proof.custody.equals(custody.id())||!same(proof.marker,custody,proof.world)||!marker.equals(proof.marker))
            throw new IllegalArgumentException("Saved NPC marker changed");
        markers.remove(custody.id().toString());player.getPersistentData().put(key,markers);
        var retained=PROOFS.get(player);if(retained!=null)retained.values().removeIf(value->value.custody.equals(custody.id()));
    }
    private static void returnOriginals(ServerPlayer player,Custody.Case returned,Snapshot snapshot) {
        owner(player,snapshot.owner());
        var marker=player.getPersistentData().getCompound(INPUTS).getCompound(returned.reservationOperation().toString());
        if(!marker.getString("phase").equals("RETURN_CLAIM")||!marker.hasUUID("return")||!marker.getUUID("return").equals(returned.id())
                ||!marker.hasUUID("custody")||!marker.getUUID("custody").equals(returned.reservationOperation())
                ||!marker.getString("items").equals(returned.terms().items())
                ||!marker.getString("snapshot").equals(snapshot.encode())||!marker.hasUUID("world")||!marker.getUUID("world").equals(snapshot.world().value())
                ||!marker.getString("financial").startsWith(snapshot.original()+"\n")||!returned.terms().owner().equals(snapshot.owner()))
            throw new IllegalArgumentException("Exact held original return link");
    }
    private static CompoundTag baseMarker(Custody.Case custody,WorldId world) {
        var marker=new CompoundTag();marker.putUUID("world",world.value());marker.putUUID("custody",custody.id());marker.putString("financial",custody.terms().financialIntent());marker.putString("items",custody.terms().items());return marker;
    }
    private static boolean same(CompoundTag marker,Custody.Case custody,WorldId world) {
        return marker.hasUUID("world")&&marker.getUUID("world").equals(world.value())&&marker.hasUUID("custody")&&marker.getUUID("custody").equals(custody.id())
                &&(!marker.hasUUID("attempt")||Objects.equals(custody.deliveryAttempt(),marker.getUUID("attempt")))
                &&marker.getString("financial").equals(custody.terms().financialIntent())&&marker.getString("items").equals(custody.terms().items());
    }
    private static void validateCash(ItemStack stack,WorldId world) {
        if(stack.has(CashStackIdentity.CASH_LOT.get()) && (CashCatalog.find(stack).isEmpty()||CashStackIdentity.claim(stack,world).isEmpty()))
            throw new IllegalArgumentException("Foreign-world or malformed cash marker");
    }
    private static void owner(ServerPlayer player,ActorId owner){if(!new PlayerId(player.getUUID()).equals(owner))throw new IllegalArgumentException("NPC custody owner");}
    private static void thread(ServerPlayer player){if(!player.server.isSameThread()||player.server.getPlayerList().getPlayer(player.getUUID())!=player||!player.isAlive())throw new IllegalStateException("Active server player required");}
    private static ArrayList<ItemStack> copyInventory(ServerPlayer player){var items=new ArrayList<ItemStack>();for(int i=0;i<36;i++)items.add(player.getInventory().getItem(i).copy());return items;}
    private static CompoundTag inventoryTag(ServerPlayer player){var tag=new CompoundTag();tag.put("Inventory",player.saveWithoutId(new CompoundTag()).getList("Inventory",Tag.TAG_COMPOUND).copy());return tag;}
    private static boolean fits(List<ItemStack> inventory,List<ItemStack> outputs){if(outputs.size()>36)return false;for(var stack:outputs){if(stack.isEmpty()||stack.getCount()>64)return false;var remaining=stack.copy();insert(inventory,remaining);if(!remaining.isEmpty())return false;}return true;}
    private static void insertMain(ServerPlayer player,ItemStack offered){var items=copyInventory(player);insert(items,offered);if(!offered.isEmpty())throw new IllegalStateException("NPC delivery capacity changed");for(int i=0;i<36;i++)player.getInventory().setItem(i,items.get(i));}
    private static void insert(List<ItemStack> inventory,ItemStack offered){
        for(var stack:inventory)if(!stack.isEmpty()&&ItemStack.isSameItemSameComponents(stack,offered)){int n=Math.min(offered.getCount(),Math.max(0,Math.min(64,stack.getMaxStackSize())-stack.getCount()));stack.grow(n);offered.shrink(n);if(offered.isEmpty())return;}
        for(int i=0;i<36;i++)if(inventory.get(i).isEmpty()){int n=Math.min(offered.getCount(),Math.min(64,offered.getMaxStackSize()));inventory.set(i,offered.copyWithCount(n));offered.shrink(n);if(offered.isEmpty())return;}
    }
}
