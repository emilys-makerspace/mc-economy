package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import com.google.gson.*;
import java.math.BigInteger;
import java.util.*;

/** Pure component evaluation; no component codecs, entity creation or item callbacks execute here. */
public final class VariantValues {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> KEYS = Set.of("minecraft:custom_name", "minecraft:damage", "minecraft:max_damage",
            "minecraft:max_stack_size", "minecraft:enchantments", "minecraft:stored_enchantments", "minecraft:container",
            "minecraft:bees", "minecraft:bucket_entity_data", "minecraft:pot_decorations", "minecraft:potion_contents",
            "minecraft:ominous_bottle_amplifier", "minecraft:fireworks", "minecraft:firework_explosion",
            "minecraft:suspicious_stew_effects", "minecraft:written_book_content", "minecraft:writable_book_content",
            "minecraft:map_id", "minecraft:map_color", "minecraft:dyed_color", "minecraft:base_color",
            "minecraft:banner_patterns", "minecraft:enchantment_glint_override", "minecraft:hide_additional_tooltip",
            "minecraft:hide_tooltip", "minecraft:repair_cost");
    private VariantValues() {}

    static final class Failure extends RuntimeException {
        final RecipeValues.Status status;
        Failure(RecipeValues.Status status,String reason) {super(reason);this.status=status;}
    }
    record Value(Rational exact,RecipeValues.Explanation proof,RecipeValues.Coverage coverage,ValuationData.Source source) {}

    public static RecipeValues.Result evaluate(ValuationData.Manifest manifest,String item,String components,long quantity,
                                               EconomyCurrency currency,EffectiveFxSnapshot fx) {
        Objects.requireNonNull(manifest);Objects.requireNonNull(currency);ValuationData.itemId(item);
        if(quantity<1||quantity>1_000_000)throw new IllegalArgumentException("Quantity limit");
        String hash=manifest.hash();
        try {
            if(manifest.variants()==null)throw invalid("VARIANT_ADAPTER_NOT_INSTALLED");
            var object=ValuationConfig.object(ValuationConfig.parseIdentity(components));
            var context=new Context(manifest,hash,currency,fx);
            var value=context.item(item,object,0,0);
            long minor=published(value.exact());
            return new RecipeValues.Result(RecipeValues.Status.READY,"COMPONENT_REFERENCE_VALUE",value.exact(),new Money(currency,minor),
                    new Money(currency,Math.multiplyExact(minor,quantity)),fx.id(),hash,manifest.configHash(),value.source(),value.coverage(),value.proof());
        } catch(Failure failure) {
            return unavailable(manifest,hash,fx,failure.status,failure.getMessage());
        } catch(ValuationBudget.Limit limit) {
            return unavailable(manifest,hash,fx,RecipeValues.Status.LIMIT_EXCEEDED,limit.getMessage());
        } catch(ArithmeticException overflow) {
            return unavailable(manifest,hash,fx,RecipeValues.Status.OVERFLOW,"EXACT_OR_PUBLISHED_AMOUNT_OVERFLOW");
        } catch(IllegalArgumentException | NullPointerException malformed) {
            return unavailable(manifest,hash,fx,RecipeValues.Status.UNSUPPORTED_IDENTITY,"UNSUPPORTED_COMPONENT_DATA");
        }
    }
    private static RecipeValues.Result unavailable(ValuationData.Manifest manifest,String hash,EffectiveFxSnapshot fx,RecipeValues.Status status,String reason) {
        return new RecipeValues.Result(status,reason,null,null,null,fx==null?null:fx.id(),hash,manifest.configHash(),null,
                RecipeValues.Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS,null);
    }
    static long published(Rational value) {
        BaseValues.bounded(value);
        if(value.compareTo(Rational.ZERO)<0)throw new ArithmeticException("Negative variant value");
        return value.compareTo(Rational.ZERO)==0?1:BaseValues.publishedMinor(value);
    }
    static Failure invalid(String reason) {return new Failure(RecipeValues.Status.UNSUPPORTED_IDENTITY,reason);}
    static Value from(RecipeValues.Material value) {
        if(value.status()!=RecipeValues.Status.READY)throw new Failure(value.status(),value.reason());
        return new Value(value.exact(),value.explanation(),value.coverage(),value.source());
    }

    static final class Context {
        final ValuationData.Manifest manifest;
        final VariantGraph.Graph graph;
        final String hash;
        final EconomyCurrency currency;
        final EffectiveFxSnapshot fx;
        final ValuationBudget budget=new ValuationBudget();
        final RecipeValues.Materials materials;
        int contained;
        Context(ValuationData.Manifest manifest,String hash,EconomyCurrency currency,EffectiveFxSnapshot fx) {
            this.manifest=manifest;this.graph=manifest.variants();this.hash=hash;this.currency=currency;this.fx=fx;
            materials=new RecipeValues.Materials(manifest,hash,currency,fx,budget);
        }
        Value material(String item,int depth) {return from(materials.lookup(item,depth));}
        Value content(String id,String cad,int depth) {return from(materials.content(id,cad,depth));}
        Value brewing(String form,JsonElement contents,int depth) {
            var object=new JsonObject();object.add("minecraft:potion_contents",contents);
            return from(BrewingValues.material(manifest,form,canonical(object),currency,fx,budget,depth));
        }
        Value item(String item,JsonObject components,int nesting,int depth) {
            budget.spend();
            if(depth>=RecipeGraph.MAX_DEPTH)throw new Failure(RecipeValues.Status.LIMIT_EXCEEDED,"VARIANT_DEPTH_LIMIT");
            if(!manifest.inputs().items().contains(item))throw invalid("ITEM_NOT_IN_CAPTURED_REGISTRY");
            var catalog=manifest.catalog().entries().get(item);
            if(catalog!=null&&catalog.classification()==ValuationData.Classification.UNPRICED_OPERATOR)
                throw new Failure(RecipeValues.Status.UNPRICED_OPERATOR,"OPERATOR_ONLY_ITEM");
            var metadata=graph.items().get(item);
            if(metadata==null)throw new Failure(RecipeValues.Status.MOD_UNREGISTERED,"NO_REGISTERED_COMPONENT_ADAPTER");
            if(metadata.defaults().stream().anyMatch(key->!key.startsWith("minecraft:")||key.equals("minecraft:custom_data")))
                throw invalid("UNSUPPORTED_ITEM_PROTOTYPE");
            validateComponents(item,components,metadata);
            int holderLevel=nesting+(metadata.slots()>0?1:0);
            if(holderLevel>8)throw new Failure(RecipeValues.Status.LIMIT_EXCEEDED,"CONTAINER_NESTING_LIMIT");
            Value base=VariantRules.base(this,item,components,depth+1);
            var applied=enchantments(components.get("minecraft:enchantments"));
            var stored=enchantments(components.get("minecraft:stored_enchantments"));
            boolean book=item.equals("minecraft:enchanted_book");
            if(!book&&!stored.isEmpty())throw invalid("STORED_ENCHANTMENTS_REQUIRE_BOOK");
            if(book&&!applied.isEmpty())throw invalid("BOOK_APPLIED_ENCHANTMENTS_UNSUPPORTED");
            var selected=book?stored:applied;
            var additions=new ArrayList<Value>();additions.add(base);
            Rational combined=base.exact();int curses=0;
            var ids=new ArrayList<>(selected.keySet());
            for(int i=0;i<ids.size();i++) {
                String id=ids.get(i);var enchant=graph.enchantments().get(id);int level=selected.get(id);
                if(enchant==null||level<1||level>enchant.maxLevel()||(!enchant.curse()&&level>enchant.premiums().size()))
                    throw invalid("UNSUPPORTED_ENCHANTMENT_LEVEL");
                if(!book&&!enchant.supported().contains(item))throw invalid("ENCHANTMENT_NOT_APPLICABLE");
                for(int j=0;j<i;j++) {
                    var other=graph.enchantments().get(ids.get(j));
                    if(enchant.exclusive().contains(other.id())||other.exclusive().contains(enchant.id()))throw invalid("INCOMPATIBLE_ENCHANTMENTS");
                }
                if(enchant.curse()) {if(level!=1)throw invalid("UNSUPPORTED_CURSE_LEVEL");curses++;}
                else {
                    var premium=content("economy:premium/"+id.replace(':','/'),enchant.premiums().get(level-1),depth+1);
                    combined=BaseValues.bounded(combined.add(premium.exact()));additions.add(premium);
                }
            }
            Rational exact=BaseValues.bounded(combined.multiply(Rational.of(4-curses)).divide(Rational.of(4)));
            int damage=components.has("minecraft:damage")?integer(components.get("minecraft:damage"),0,metadata.maxDamage()):0;
            if(metadata.maxDamage()>0)exact=BaseValues.bounded(exact.multiply(Rational.of(metadata.maxDamage()-damage)).divide(Rational.of(metadata.maxDamage())));
            Value shell=proof(item,"VARIANT_OBJECT_MODIFIERS",canonical(components),exact,additions,List.of(),base.source());
            if(components.has("minecraft:container")) {
                var children=array(components.get("minecraft:container"),256);
                var occupied=new HashSet<Integer>();var values=new ArrayList<Value>();values.add(shell);
                long total=published(shell.exact());
                for(var raw:children) {
                    if(++contained>4_096)throw new Failure(RecipeValues.Status.LIMIT_EXCEEDED,"CONTAINED_STACK_LIMIT");
                    var slot=object(raw);keys(slot,Set.of("slot","item"));
                    int index=integer(slot.get("slot"),0,metadata.slots()-1);
                    if(!occupied.add(index))throw invalid("DUPLICATE_CONTAINER_SLOT");
                    var stack=object(slot.get("item"));keys(stack,Set.of("id","count","components"));
                    String child=string(stack.get("id"));var childMetadata=graph.items().get(child);
                    if(childMetadata==null)throw new Failure(RecipeValues.Status.MOD_UNREGISTERED,"UNSUPPORTED_CONTAINED_ITEM");
                    int count=integer(stack.get("count"),1,childMetadata.maxStack());
                    Value value=item(child,object(stack.get("components")),holderLevel,depth+1);
                    total=Math.addExact(total,Math.multiplyExact(published(value.exact()),count));values.add(value);
                }
                shell=proof(item,"CONTAINER_PUBLISHED_SHELL_AND_CONTENTS",canonical(components),Rational.of(total),values,List.of(),null);
            }
            return shell;
        }
        Value proof(String item,String kind,String identity,Rational exact,List<Value> children,List<String> chosen,ValuationData.Source source) {
            return proof(item,kind,identity,exact,children,chosen,source,1);
        }
        Value proof(String item,String kind,String identity,Rational exact,List<Value> children,List<String> chosen,ValuationData.Source source,int count) {
            BaseValues.bounded(exact);
            if(fx==null)throw new Failure(RecipeValues.Status.FX_UNAVAILABLE,"CAPTURED_FX_REQUIRED");
            var hashes=children.stream().map(v->v.proof().rootHash()).toList();
            var unhashed=new RecipeValues.Step("",item,kind,"economy:variants/"+kind.toLowerCase(Locale.ROOT),count,exact,Rational.ZERO,chosen,hashes,source);
            String digest=ValuationData.hash(JSON.toJson(List.of(VariantGraph.ADAPTER,hash,fx.id().toString(),currency.code(),identity,unhashed)));
            var root=new RecipeValues.Step(digest,item,kind,unhashed.recipe(),count,exact,Rational.ZERO,chosen,hashes,source);
            var steps=new ArrayList<RecipeValues.Step>();steps.add(root);boolean truncated=false,limited=false;
            for(var child:children) {
                truncated|=child.proof().truncated();limited|=child.coverage()!=RecipeValues.Coverage.COMPLETE;
                for(var step:child.proof().steps())if(steps.size()<256)steps.add(step);else truncated=true;
            }
            return new Value(exact,new RecipeValues.Explanation(digest,steps,truncated),limited?RecipeValues.Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS:RecipeValues.Coverage.COMPLETE,source);
        }
    }

    private static void validateComponents(String item,JsonObject components,VariantGraph.Item metadata) {
        if(!KEYS.containsAll(components.keySet()))throw invalid("UNKNOWN_COMPONENT");
        if(components.has("minecraft:max_damage")&&integer(components.get("minecraft:max_damage"),1,10_000_000)!=metadata.maxDamage())throw invalid("ALTERED_MAX_DAMAGE");
        if(components.has("minecraft:max_stack_size")&&integer(components.get("minecraft:max_stack_size"),1,99)!=metadata.maxStack())throw invalid("ALTERED_MAX_STACK_SIZE");
        if(components.has("minecraft:damage")&&metadata.maxDamage()==0)throw invalid("DAMAGE_ON_NON_DAMAGEABLE_ITEM");
        if(components.has("minecraft:stored_enchantments")&&!item.equals("minecraft:enchanted_book"))throw invalid("STORED_ENCHANTMENT_COMPONENT_APPLICABILITY");
        if(components.has("minecraft:container")&&metadata.slots()==0)throw invalid("UNSUPPORTED_CONTAINER_SHELL");
        only(item,components,"minecraft:bees",Set.of("minecraft:bee_nest","minecraft:beehive"));
        only(item,components,"minecraft:bucket_entity_data",VariantRules.ENTITY_BUCKETS);
        only(item,components,"minecraft:pot_decorations",Set.of("minecraft:decorated_pot"));
        var potionItems=new HashSet<>(BrewingGraph.FORMS);potionItems.add("minecraft:tipped_arrow");
        only(item,components,"minecraft:potion_contents",potionItems);
        only(item,components,"minecraft:ominous_bottle_amplifier",Set.of("minecraft:ominous_bottle"));
        only(item,components,"minecraft:fireworks",Set.of("minecraft:firework_rocket"));
        only(item,components,"minecraft:firework_explosion",Set.of("minecraft:firework_star"));
        only(item,components,"minecraft:suspicious_stew_effects",Set.of("minecraft:suspicious_stew"));
        only(item,components,"minecraft:written_book_content",Set.of("minecraft:written_book"));
        only(item,components,"minecraft:writable_book_content",Set.of("minecraft:writable_book"));
        only(item,components,"minecraft:map_id",Set.of("minecraft:filled_map"));only(item,components,"minecraft:map_color",Set.of("minecraft:filled_map"));
        if(components.has("minecraft:map_id"))integer(components.get("minecraft:map_id"),0,Integer.MAX_VALUE);
        if(components.has("minecraft:map_color"))integer(components.get("minecraft:map_color"),0,0xffffff);
        if(components.has("minecraft:custom_name"))name(components.get("minecraft:custom_name"),0);
        if(components.has("minecraft:enchantment_glint_override"))bool(components.get("minecraft:enchantment_glint_override"));
        if(components.has("minecraft:repair_cost"))integer(components.get("minecraft:repair_cost"),0,1_000_000);
        for(String key:List.of("minecraft:hide_additional_tooltip","minecraft:hide_tooltip"))if(components.has(key))keys(object(components.get(key)),Set.of());
        if(components.has("minecraft:dyed_color")) {
            if(!(item.startsWith("minecraft:leather_")||item.equals("minecraft:wolf_armor")))throw invalid("DYED_COLOR_APPLICABILITY");
            var value=components.get("minecraft:dyed_color");
            if(value.isJsonPrimitive())integer(value,0,0xffffff);else {var dye=object(value);allowed(dye,Set.of("rgb","show_in_tooltip"),Set.of("rgb"));integer(dye.get("rgb"),0,0xffffff);if(dye.has("show_in_tooltip"))bool(dye.get("show_in_tooltip"));}
        }
        if(components.has("minecraft:base_color")||components.has("minecraft:banner_patterns")) {
            if(!(item.endsWith("_banner")||item.equals("minecraft:shield")))throw invalid("BANNER_COMPONENT_APPLICABILITY");
            if(components.has("minecraft:base_color"))VariantRules.dye(string(components.get("minecraft:base_color")));
            if(components.has("minecraft:banner_patterns"))for(var raw:array(components.get("minecraft:banner_patterns"),16)) {
                var layer=object(raw);keys(layer,Set.of("pattern","color"));String pattern=string(layer.get("pattern"));
                if(!VariantRules.BANNER_PATTERNS.contains(pattern))throw invalid("UNKNOWN_BANNER_PATTERN");VariantRules.dye(string(layer.get("color")));
            }
        }
        if(components.has("minecraft:written_book_content"))book(object(components.get("minecraft:written_book_content")),true);
        if(components.has("minecraft:writable_book_content"))book(object(components.get("minecraft:writable_book_content")),false);
    }
    private static void only(String item,JsonObject components,String key,Set<String> items) {if(components.has(key)&&!items.contains(item))throw invalid("COMPONENT_NOT_APPLICABLE:"+key);}
    private static void book(JsonObject book,boolean written) {
        if(written) {keys(book,Set.of("title","author","generation","pages","resolved"));string(book.get("author"));integer(book.get("generation"),0,3);bool(book.get("resolved"));filterable(book.get("title"),false);}
        else keys(book,Set.of("pages"));
        for(var page:array(book.get("pages"),100))filterable(page,written);
    }
    private static void filterable(JsonElement value,boolean component) {
        if(value.isJsonPrimitive()){string(value);return;}
        var filter=object(value);allowed(filter,Set.of("raw","filtered"),Set.of("raw"));
        for(var entry:filter.entrySet())if(component)name(entry.getValue(),0);else string(entry.getValue());
    }
    private static void name(JsonElement value,int depth) {
        if(depth>8)throw invalid("NAME_DEPTH_LIMIT");
        if(value.isJsonPrimitive()){if(string(value).length()>16_384)throw invalid("NAME_SIZE_LIMIT");return;}
        var name=object(value);allowed(name,Set.of("text","color","bold","italic","underlined","strikethrough","obfuscated","extra"),Set.of("text"));
        if(string(name.get("text")).length()>16_384)throw invalid("NAME_SIZE_LIMIT");
        if(name.has("color")){String color=string(name.get("color"));if(!color.matches("#[0-9a-fA-F]{6}")&&!Set.of("black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white").contains(color))throw invalid("NAME_COLOR");}
        for(String flag:List.of("bold","italic","underlined","strikethrough","obfuscated"))if(name.has(flag))bool(name.get(flag));
        if(name.has("extra"))for(var child:array(name.get("extra"),64))name(child,depth+1);
    }
    static Map<String,Integer> enchantments(JsonElement value) {
        if(value==null)return Map.of();var object=object(value);JsonObject levels;
        if(object.has("levels")){allowed(object,Set.of("levels","show_in_tooltip"),Set.of("levels"));levels=object(object.get("levels"));if(object.has("show_in_tooltip"))bool(object.get("show_in_tooltip"));}
        else levels=object;
        if(levels.size()>42)throw invalid("ENCHANTMENT_COUNT_LIMIT");
        var result=new TreeMap<String,Integer>();for(var e:levels.entrySet()){ValuationData.itemId(e.getKey());result.put(e.getKey(),integer(e.getValue(),1,255));}return result;
    }
    static JsonObject object(JsonElement value){return ValuationConfig.object(value);}
    static JsonArray array(JsonElement value,int max){if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()>max)throw invalid("COMPONENT_ARRAY_BOUND");return value.getAsJsonArray();}
    static String string(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw invalid("COMPONENT_STRING_REQUIRED");return value.getAsString();}
    static boolean bool(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw invalid("COMPONENT_BOOLEAN_REQUIRED");return value.getAsBoolean();}
    static int integer(JsonElement value,int min,int max){
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid("COMPONENT_INTEGER_REQUIRED");
        final int number;try{number=value.getAsBigDecimal().intValueExact();}catch(ArithmeticException invalid){throw invalid("COMPONENT_INTEGER_BOUND");}
        if(number<min||number>max)throw invalid("COMPONENT_INTEGER_BOUND");return number;
    }
    static void keys(JsonObject object,Set<String> keys){if(!object.keySet().equals(keys))throw invalid("COMPONENT_FIELDS");}
    static void allowed(JsonObject object,Set<String> allowed,Set<String> required){if(!allowed.containsAll(object.keySet())||!object.keySet().containsAll(required))throw invalid("COMPONENT_FIELDS");}
    static String canonical(JsonElement value) {
        if(value.isJsonObject()){var object=new JsonObject();new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key,child)->object.add(key,JsonParser.parseString(canonical(child))));return JSON.toJson(object);}
        if(value.isJsonArray()){var array=new JsonArray();for(var child:value.getAsJsonArray())array.add(JsonParser.parseString(canonical(child)));return JSON.toJson(array);}return JSON.toJson(value);
    }
}
