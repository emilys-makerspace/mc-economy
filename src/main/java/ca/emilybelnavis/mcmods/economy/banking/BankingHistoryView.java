package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Wire-only immutable history view. Opaque navigation handles stay in server-offered choices. */
public record BankingHistoryView(UUID generation, long highWater, OwnerHistory.Query query,
        List<OwnerHistory.Movement> rows, HistoryDetails.Detail detail, Rates rates, String availability) {
    public BankingHistoryView {
        Objects.requireNonNull(generation); Objects.requireNonNull(query); text(availability, 128);
        rows = List.copyOf(rows);
        if (highWater < 0 || rows.size() > 16 || detail != null && rates != null) throw new IllegalArgumentException("HISTORY_VIEW_BOUNDS");
    }
    public static final Set<String> ACTIONS = Set.of("history", "history_next", "history_prev", "history_refresh", "history_filters", "history_account", "history_acc_next", "history_currency", "history_type", "history_dates", "history_clear_dates", "history_size", "history_detail", "history_back", "history_rates", "rate_source", "rate_target", "rate_family", "rate_next", "rate_at");
    public int encodedSize() {
        var b = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(256, BankingNetwork.MAX_SNAPSHOT_BYTES), net.minecraft.core.RegistryAccess.EMPTY);
        try { write(b); return b.readableBytes(); }
        catch (IndexOutOfBoundsException tooLarge) { throw new IllegalArgumentException("HISTORY_PAYLOAD_BUDGET", tooLarge); }
        finally { b.release(); }
    }
    public enum Family { EFFECTIVE, DAILY }
    public record Rate(UUID publication, long effectiveAt, String numerator, String denominator, int version, String status) {
        public Rate { text(numerator,128); text(denominator,128); text(status,64); if(effectiveAt<0||version<0)throw new IllegalArgumentException("FX_HISTORY_ROW"); }
    }
    public record Rates(Family family, EconomyCurrency source, EconomyCurrency target, List<Rate> rows) {
        public Rates {Objects.requireNonNull(family);Objects.requireNonNull(source);Objects.requireNonNull(target);rows=List.copyOf(rows);if(source==target||rows.size()>8)throw new IllegalArgumentException("FX_HISTORY_BOUNDS");}
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeUUID(generation);b.writeLong(highWater);uuid(b,query.account());
        b.writeBoolean(query.currency()!=null);if(query.currency()!=null)b.writeUtf(query.currency().code(),3);
        optionalLong(b,query.fromInclusive());optionalLong(b,query.toExclusive());b.writeEnum(query.type());b.writeVarInt(query.limit());
        b.writeUtf(availability,128);b.writeVarInt(rows.size());for(var r:rows)movement(b,r);
        b.writeBoolean(detail!=null);if(detail!=null)detail(b,detail);
        b.writeBoolean(rates!=null);if(rates!=null){b.writeEnum(rates.family());b.writeUtf(rates.source().code(),3);b.writeUtf(rates.target().code(),3);b.writeVarInt(rates.rows().size());for(var r:rates.rows()){uuid(b,r.publication());b.writeLong(r.effectiveAt());b.writeUtf(r.numerator(),128);b.writeUtf(r.denominator(),128);b.writeVarInt(r.version());b.writeUtf(r.status(),64);}}
    }
    public static BankingHistoryView read(RegistryFriendlyByteBuf b) {
        UUID generation=b.readUUID();long high=b.readLong();UUID account=uuid(b);
        EconomyCurrency currency=b.readBoolean()?EconomyCurrency.fromCode(b.readUtf(3)):null;
        var query=new OwnerHistory.Query(account,currency,optionalLong(b),optionalLong(b),b.readEnum(OwnerHistory.Type.class),b.readVarInt());
        String availability=b.readUtf(128);int count=count(b,16);var rows=new ArrayList<OwnerHistory.Movement>(count);for(int i=0;i<count;i++)rows.add(movement(b));
        var detail=b.readBoolean()?detail(b):null;Rates rates=null;
        if(b.readBoolean()){var family=b.readEnum(Family.class);var source=EconomyCurrency.fromCode(b.readUtf(3));var target=EconomyCurrency.fromCode(b.readUtf(3));int size=count(b,8);var values=new ArrayList<Rate>(size);for(int i=0;i<size;i++)values.add(new Rate(uuid(b),b.readLong(),b.readUtf(128),b.readUtf(128),b.readVarInt(),b.readUtf(64)));rates=new Rates(family,source,target,values);}
        return new BankingHistoryView(generation,high,query,rows,detail,rates,availability);
    }
    private static void movement(RegistryFriendlyByteBuf b,OwnerHistory.Movement r){b.writeUUID(r.transaction());b.writeUUID(r.operation());b.writeUUID(r.account());b.writeLong(r.sequence());b.writeVarInt(r.position());b.writeLong(r.recordedAt());money(b,r.amount());b.writeLong(r.denominationRevision());b.writeVarInt(r.postingVersion());b.writeUtf(r.service(),128);b.writeUtf(r.operationType(),128);b.writeBoolean(r.internalMovement());}
    private static OwnerHistory.Movement movement(RegistryFriendlyByteBuf b){return new OwnerHistory.Movement(b.readUUID(),b.readUUID(),b.readUUID(),b.readLong(),b.readVarInt(),b.readLong(),money(b),b.readLong(),b.readVarInt(),b.readUtf(128),b.readUtf(128),b.readBoolean());}
    private static void detail(RegistryFriendlyByteBuf b,HistoryDetails.Detail d){
        b.writeUUID(d.transaction());b.writeUUID(d.operation());b.writeLong(d.sequence());b.writeLong(d.recordedAt());b.writeUtf(d.service(),128);b.writeUtf(d.operationType(),128);b.writeUtf(d.status(),32);
        b.writeVarInt(d.movements().size());for(var m:d.movements()){b.writeUUID(m.account());b.writeVarInt(m.position());money(b,m.amount());b.writeLong(m.denominationRevision());b.writeVarInt(m.postingVersion());}b.writeBoolean(d.movementsMore());
        b.writeEnum(d.fee().availability());b.writeBoolean(d.fee().amount()!=null);if(d.fee().amount()!=null)money(b,d.fee().amount());b.writeEnum(d.counterparty().visibility());b.writeUtf(d.counterparty().label(),128);
        b.writeVarInt(d.fx().size());for(var f:d.fx()){b.writeUUID(f.publication());b.writeUtf(f.family(),64);b.writeLong(f.effectiveAt());b.writeUtf(f.source(),3);b.writeUtf(f.target(),3);b.writeUtf(f.numerator(),128);b.writeUtf(f.denominator(),128);b.writeVarInt(f.version());b.writeEnum(f.availability());}
        b.writeVarInt(d.provenance().size());for(var p:d.provenance()){b.writeUtf(p.type(),128);b.writeVarInt(p.version());b.writeUtf(p.reference(),128);}
        b.writeBoolean(d.additionalDetail());
    }
    private static HistoryDetails.Detail detail(RegistryFriendlyByteBuf b){
        UUID tx=b.readUUID(),op=b.readUUID();long seq=b.readLong(),at=b.readLong();String service=b.readUtf(128),type=b.readUtf(128),status=b.readUtf(32);
        int size=count(b,16);var movements=new ArrayList<HistoryDetails.Movement>(size);for(int i=0;i<size;i++)movements.add(new HistoryDetails.Movement(b.readUUID(),b.readVarInt(),money(b),b.readLong(),b.readVarInt()));boolean more=b.readBoolean();
        var fee=new HistoryDetails.Fee(b.readEnum(HistoryDetails.Availability.class),b.readBoolean()?money(b):null);var counterparty=new HistoryDetails.Counterparty(b.readEnum(HistoryDetails.Visibility.class),b.readUtf(128));
        size=count(b,4);var fx=new ArrayList<HistoryDetails.CapturedFx>(size);for(int i=0;i<size;i++)fx.add(new HistoryDetails.CapturedFx(b.readUUID(),b.readUtf(64),b.readLong(),b.readUtf(3),b.readUtf(3),b.readUtf(128),b.readUtf(128),b.readVarInt(),b.readEnum(HistoryDetails.Availability.class)));
        size=count(b,8);var provenance=new ArrayList<HistoryDetails.Provenance>(size);for(int i=0;i<size;i++)provenance.add(new HistoryDetails.Provenance(b.readUtf(128),b.readVarInt(),b.readUtf(128)));
        return new HistoryDetails.Detail(tx,op,seq,at,service,type,status,movements,more,fee,counterparty,fx,provenance,b.readBoolean());
    }
    private static void money(RegistryFriendlyByteBuf b,Money m){b.writeUtf(m.currency().code(),3);b.writeLong(m.minorUnits());}
    private static Money money(RegistryFriendlyByteBuf b){return new Money(EconomyCurrency.fromCode(b.readUtf(3)),b.readLong());}
    private static void uuid(RegistryFriendlyByteBuf b,UUID id){b.writeBoolean(id!=null);if(id!=null)b.writeUUID(id);}
    private static UUID uuid(RegistryFriendlyByteBuf b){return b.readBoolean()?b.readUUID():null;}
    private static void optionalLong(RegistryFriendlyByteBuf b,Long n){b.writeBoolean(n!=null);if(n!=null)b.writeLong(n);}
    private static Long optionalLong(RegistryFriendlyByteBuf b){return b.readBoolean()?b.readLong():null;}
    private static int count(RegistryFriendlyByteBuf b,int max){int size=b.readVarInt();if(size<0||size>max)throw new IllegalArgumentException("HISTORY_COLLECTION_BOUNDS");return size;}
    private static void text(String value,int max){if(value==null||value.length()>max)throw new IllegalArgumentException("HISTORY_TEXT_BOUNDS");}
}
