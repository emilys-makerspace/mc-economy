package ca.emilybelnavis.mcmods.economy.simulation.model;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.Kind;
import java.io.*;
import java.math.*;
import java.util.*;

/** Canonical bounded v1 detached input/output facts; historical model meanings stay installed. */
public final class ModelFacts {
    private ModelFacts() {}
    public static final String INPUT="economy:a8/model-input", OUTPUT="economy:a8/model-output";
    public static RecordCatalog register(RecordCatalog catalog) {
        return catalog.with(new RecordCatalog.Key(INPUT,1),ModelFacts::decodeInput)
                .with(new RecordCatalog.Key(OUTPUT,1),ModelFacts::decodeOutput);
    }
    public static String encode(MacroModel.Input value) {
        return write(out->{
            uuid(out,value.windowId()); out.writeUTF(value.inputHash()); out.writeUTF(value.reference().name());
            var config=value.config(); uuid(out,config.id()); out.writeInt(config.revision());
            rational(out,config.dailyFloor()); rational(out,config.capacityFactor());
            for(var weight:config.primeWeights())rational(out,weight);
            for(var weight:config.fxWeights())rational(out,weight);
            out.writeInt(value.windows().size());
            for(var c:value.windows().keySet().stream().sorted().toList()) {
                out.writeUTF(c.name()); var w=value.windows().get(c);
                out.writeInt(w.days()); out.writeInt(w.baselineDays()); out.writeBoolean(w.available());
                amounts(out,w.totals()); amounts(out,w.baselineTotals()); integer(out,w.capacityMinorSum());
                decimal(out,value.anchors().get(c)); decimal(out,value.priorFx().get(c)); decimal(out,value.priorPrime().get(c));
            }
        });
    }
    public static MacroModel.Input decodeInput(String payload) {
        MacroModel.Input value=read(payload,in->{
            UUID id=uuid(in); String hash=in.readUTF(); var reference=currency(in);
            UUID configId=uuid(in); int revision=in.readInt(); var floor=rational(in); var factor=rational(in);
            var primeWeights=new ArrayList<Rational>(); for(int i=0;i<3;i++)primeWeights.add(rational(in));
            var fxWeights=new ArrayList<Rational>(); for(int i=0;i<2;i++)fxWeights.add(rational(in));
            var config=new MacroModel.Config(configId,revision,floor,factor,primeWeights,fxWeights);
            var windows=new EnumMap<EconomyCurrency,MacroModel.Window>(EconomyCurrency.class);
            var anchors=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
            var fx=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
            var prime=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
            int count=count(in,11);
            for(int i=0;i<count;i++) {
                var c=currency(in); var w=new MacroModel.Window(in.readInt(),in.readInt(),in.readBoolean(),amounts(in),amounts(in),integer(in));
                if(windows.put(c,w)!=null)throw new IllegalArgumentException("Duplicate model currency");
                anchors.put(c,decimal(in)); fx.put(c,decimal(in)); prime.put(c,decimal(in));
            }
            return new MacroModel.Input(id,hash,reference,windows,anchors,fx,prime,config);
        });
        if(!encode(value).equals(payload))throw new IllegalArgumentException("Noncanonical model input"); return value;
    }
    public static String encode(MacroModel.Output value) {
        return write(out->{
            out.writeBoolean(value.available()); out.writeUTF(value.reason()); rational(out,value.alpha()); out.writeUTF(value.hash());
            out.writeInt(value.signals().size());
            for(var c:value.signals().keySet().stream().sorted().toList()) {
                out.writeUTF(c.name()); var s=value.signals().get(c);
                rational(out,s.spending()); rational(out,s.credit()); rational(out,s.money()); rational(out,s.exchange());
                rational(out,s.primePressure()); rational(out,s.fxPressure());
            }
            decimals(out,value.primes()); decimals(out,value.fx()); out.writeInt(value.explanations().size());
            for(var explanation:value.explanations())out.writeUTF(explanation);
        });
    }
    public static MacroModel.Output decodeOutput(String payload) {
        MacroModel.Output value=read(payload,in->{
            boolean available=in.readBoolean(); String reason=in.readUTF(); Rational alpha=rational(in); String hash=in.readUTF();
            var signals=new EnumMap<EconomyCurrency,MacroModel.Signals>(EconomyCurrency.class);
            int count=count(in,11);
            for(int i=0;i<count;i++) {
                var c=currency(in); var s=new MacroModel.Signals(rational(in),rational(in),rational(in),rational(in),rational(in),rational(in));
                if(signals.put(c,s)!=null)throw new IllegalArgumentException("Duplicate model signal");
            }
            var primes=decimals(in); var fx=decimals(in); var explanations=new ArrayList<String>();
            int explanationsCount=count(in,32); for(int i=0;i<explanationsCount;i++)explanations.add(in.readUTF());
            return new MacroModel.Output(available,reason,signals,primes,fx,alpha,explanations,hash);
        });
        if(!encode(value).equals(payload))throw new IllegalArgumentException("Noncanonical model output"); return value;
    }
    private static void amounts(DataOutputStream out,Map<Kind,BigInteger> values)throws IOException {
        out.writeInt(values.size()); for(var k:values.keySet().stream().sorted().toList()){out.writeUTF(k.name());integer(out,values.get(k));}
    }
    private static Map<Kind,BigInteger> amounts(DataInputStream in)throws IOException {
        var result=new EnumMap<Kind,BigInteger>(Kind.class); int count=count(in,Kind.values().length);
        for(int i=0;i<count;i++){var k=Kind.valueOf(in.readUTF());if(result.put(k,integer(in))!=null)throw new IllegalArgumentException("Duplicate model amount");}return result;
    }
    private static void decimals(DataOutputStream out,Map<EconomyCurrency,BigDecimal> values)throws IOException {
        out.writeInt(values.size());for(var c:values.keySet().stream().sorted().toList()){out.writeUTF(c.name());decimal(out,values.get(c));}
    }
    private static Map<EconomyCurrency,BigDecimal> decimals(DataInputStream in)throws IOException {
        var result=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);int count=count(in,11);
        for(int i=0;i<count;i++){var c=currency(in);if(result.put(c,decimal(in))!=null)throw new IllegalArgumentException("Duplicate model rate");}return result;
    }
    private static void rational(DataOutputStream out,Rational value)throws IOException {integer(out,value.numerator());integer(out,value.denominator());}
    private static Rational rational(DataInputStream in)throws IOException {return new Rational(integer(in),integer(in));}
    private static void integer(DataOutputStream out,BigInteger value)throws IOException {out.writeUTF(value.toString());}
    private static BigInteger integer(DataInputStream in)throws IOException {
        String value=in.readUTF();if(value.length()>2048)throw new IllegalArgumentException("Model integer size");return new BigInteger(value);
    }
    private static void decimal(DataOutputStream out,BigDecimal value)throws IOException {out.writeUTF(value.toPlainString());}
    private static BigDecimal decimal(DataInputStream in)throws IOException {
        String text=in.readUTF();if(text.length()>256)throw new IllegalArgumentException("Model decimal size");return new BigDecimal(text);
    }
    private static EconomyCurrency currency(DataInputStream in)throws IOException {return EconomyCurrency.valueOf(in.readUTF());}
    private static void uuid(DataOutputStream out,UUID value)throws IOException {out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException {return new UUID(in.readLong(),in.readLong());}
    private static int count(DataInputStream in,int max)throws IOException {int count=in.readInt();if(count<0||count>max)throw new IllegalArgumentException("Model count");return count;}
    @FunctionalInterface private interface Writer {void write(DataOutputStream out)throws IOException;}
    @FunctionalInterface private interface Reader<T> {T read(DataInputStream in)throws IOException;}
    private static String write(Writer writer) {
        try {
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);writer.write(out);}
            String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if(payload.length()>16384)throw new IllegalArgumentException("Model fact size");return payload;
        }catch(IOException impossible){throw new AssertionError(impossible);}
    }
    private static <T>T read(String payload,Reader<T> reader) {
        if(payload==null||payload.length()>16384)throw new IllegalArgumentException("Model fact size");
        try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(payload)))) {
            if(in.readInt()!=1)throw new IllegalArgumentException("Model version");T result=reader.read(in);
            if(in.available()!=0)throw new IllegalArgumentException("Trailing model bytes");return result;
        }catch(IOException|IllegalArgumentException malformed){throw new IllegalArgumentException("Malformed model fact",malformed);}
    }
}
