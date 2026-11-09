package ca.emilybelnavis.mcmods.economy.village.generation;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

/** Explicit pinned resource manifest. Unknown/replaced/composite elements fail closed for banking only. */
public final class VillageClassifier {
    private static final JsonObject MANIFEST=load();
    public static final String REVISION=MANIFEST.get("revision").getAsString()+":standard-banks-v3";
    public record Result(int buildings,boolean abandoned,boolean supported) {}
    private VillageClassifier(){}
    private static JsonObject load(){
        try(var input=VillageClassifier.class.getResourceAsStream("/data/economy/worldgen/village_inventory.json")){
            if(input==null)throw new IOException("Missing pinned village manifest");
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    public static boolean resourcesReady(ResourceManager manager){
        try{
            for(var entry:MANIFEST.getAsJsonObject("resources").entrySet()){
                var resource=manager.getResource(ResourceLocation.parse(entry.getKey()));if(resource.isEmpty())return false;
                try(var input=resource.get().open()){
                    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                    if(!hash.equals(entry.getValue().getAsString()))return false;
                }
            }
            try(var manifest=VillageClassifier.class.getResourceAsStream("/data/economy/worldgen/bank_templates.json")){
                if(manifest==null)return false;
                var templates=JsonParser.parseReader(new InputStreamReader(manifest,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("templates");
                for(var value:templates){
                    var entry=value.getAsJsonObject();var id=ResourceLocation.parse(entry.get("resource").getAsString());
                    var resource=manager.getResource(ResourceLocation.fromNamespaceAndPath(id.getNamespace(),"structure/"+id.getPath()+".nbt"));
                    if(resource.isEmpty())return false;
                    try(var input=resource.get().open()){
                        var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                        if(!hash.equals(entry.get("sha256").getAsString()))return false;
                    }
                }
            }
            return true;
        }catch(Exception changed){return false;}
    }
    public static Result classify(String style,List<StructurePiece> pieces,Structure.GenerationContext context){
        var mapping=MANIFEST.getAsJsonObject("styles").getAsJsonObject(style);
        Set<String> normal=set(mapping,"normalStarts"),abandoned=set(mapping,"abandonedStarts"),buildings=set(mapping,"buildings"),templates=set(mapping,"templates"),features=set(mapping,"features");
        int count=0;boolean first=true;
        for(var piece:pieces){
            if(!(piece instanceof PoolElementStructurePiece pool))return new Result(0,false,false);
            var encoded=StructurePoolElement.CODEC.encodeStart(context.registryAccess().createSerializationContext(JsonOps.INSTANCE),pool.getElement()).result();
            if(encoded.isEmpty()||!encoded.get().isJsonObject())return new Result(0,false,false);
            var e=encoded.get().getAsJsonObject();String kind=e.get("element_type").getAsString();
            if(kind.equals("minecraft:empty_pool_element")){if(first)return new Result(0,false,false);continue;}
            if(kind.equals("minecraft:feature_pool_element")){
                if(first||!e.get("feature").isJsonPrimitive()||!features.contains(e.get("feature").getAsString()))return new Result(0,false,false);
                continue;
            }
            if(!Set.of("minecraft:single_pool_element","minecraft:legacy_single_pool_element").contains(kind)||!e.get("location").isJsonPrimitive())return new Result(0,false,false);
            String id=e.get("location").getAsString();
            if(first){first=false;if(abandoned.contains(id))return new Result(0,true,true);if(!normal.contains(id))return new Result(0,false,false);}
            if(!templates.contains(id))return new Result(0,false,false);
            if(buildings.contains(id))count++;
        }
        return new Result(count,false,!first);
    }
    private static Set<String> set(JsonObject object,String name){var result=new HashSet<String>();for(var value:object.getAsJsonArray(name))result.add(value.getAsString());return result;}
}
