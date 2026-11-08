package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.valuation.VariantValues.*;

/** Explicit vanilla component-to-material mappings, never special recipe execution. */
final class VariantRules {
    static final Set<String> ENTITY_BUCKETS=Set.of("minecraft:cod_bucket","minecraft:salmon_bucket","minecraft:pufferfish_bucket",
            "minecraft:tropical_fish_bucket","minecraft:axolotl_bucket","minecraft:tadpole_bucket");
    private static final Set<String> DYES=Set.of("white","orange","magenta","light_blue","yellow","lime","pink","gray",
            "light_gray","cyan","purple","blue","brown","green","red","black");
    static final Set<String> BANNER_PATTERNS=patterns();
    private static final Set<String> BUCKET_KEYS=Set.of("BucketVariantTag","Variant","Age","HuntingCooldown","Health","NoAI","Silent","NoGravity","Glowing","Invulnerable");
    private static final Set<String> BEE_KEYS=Set.of("id","Health","Age","ForcedAge","InLove","LoveCause","HasNectar","HasStung",
            "AngerTime","AngryAt","flower_pos","PersistenceRequired","NoAI","Silent","Invulnerable","Glowing","CustomName",
            "CustomNameVisible","Tags","Attributes","AbsorptionAmount","active_effects","TicksFrozen");
    private VariantRules() {}

    static Value base(Context context,String item,JsonObject components,int depth) {
        if(BrewingGraph.FORMS.contains(item)) {
            if(!components.has("minecraft:potion_contents"))throw invalid("POTION_CONTENT_IDENTITY_REQUIRED");
            return context.brewing(item,components.get("minecraft:potion_contents"),depth);
        }
        if(item.equals("minecraft:tipped_arrow")) {
            if(!components.has("minecraft:potion_contents"))throw invalid("TIPPED_ARROW_CONTENT_IDENTITY_REQUIRED");
            var arrow=context.material("minecraft:arrow",depth+1);
            var potion=context.brewing("minecraft:lingering_potion",components.get("minecraft:potion_contents"),depth+1);
            Rational exact=BaseValues.bounded(arrow.exact().multiply(Rational.of(8)).add(potion.exact()).divide(Rational.of(8)));
            return context.proof(item,"TIPPED_ARROW_MATERIALS",canonical(components),exact,List.of(arrow,potion),List.of("minecraft:arrow","minecraft:lingering_potion"),null,8);
        }
        if(item.equals("minecraft:firework_star")) {
            if(!components.has("minecraft:firework_explosion"))throw invalid("FIREWORK_EXPLOSION_REQUIRED");
            return explosion(context,object(components.get("minecraft:firework_explosion")),depth);
        }
        if(item.equals("minecraft:firework_rocket"))return rocket(context,components,depth);
        if(item.equals("minecraft:suspicious_stew"))return stew(context,components,depth);
        if(item.equals("minecraft:ominous_bottle")) {
            int amplifier=components.has("minecraft:ominous_bottle_amplifier")?integer(components.get("minecraft:ominous_bottle_amplifier"),0,4):0;
            var bottle=context.material("minecraft:glass_bottle",depth+1);
            var content=context.content("economy:ominous_content",Integer.toString(25*(amplifier+1)),depth+1);
            return context.proof(item,"OMINOUS_LEVEL_CONTENT",canonical(components),BaseValues.bounded(bottle.exact().add(content.exact())),List.of(bottle,content),List.of("minecraft:glass_bottle","economy:ominous_content"),null);
        }
        if(ENTITY_BUCKETS.contains(item)&&components.has("minecraft:bucket_entity_data"))bucket(item,object(components.get("minecraft:bucket_entity_data")));
        Value base;
        if(item.equals("minecraft:decorated_pot")&&components.has("minecraft:pot_decorations")) {
            var decorations=array(components.get("minecraft:pot_decorations"),4);
            if(decorations.size()!=4)throw invalid("POT_REQUIRES_FOUR_MATERIALS");
            var children=new ArrayList<Value>();Rational total=Rational.ZERO;
            for(var entry:decorations) {
                String id=string(entry);
                if(!(id.equals("minecraft:brick")||id.startsWith("minecraft:")&&id.endsWith("_pottery_sherd")))throw invalid("UNSUPPORTED_POT_DECORATION");
                Value material=context.material(id,depth+1);children.add(material);total=BaseValues.bounded(total.add(material.exact()));
            }
            // Preserve an explicit unmodified-shell principal while applying the actual pattern material difference.
            if(context.manifest.sources().containsKey(item)) {
                base=context.material(item,depth);
                var brick=context.material("minecraft:brick",depth+1);children.add(base);children.add(brick);
                total=BaseValues.bounded(base.exact().add(total).subtract(brick.exact().multiply(Rational.of(4))));
                if(total.compareTo(Rational.ZERO)<0)throw invalid("POT_OVERRIDE_BELOW_PATTERN_DIFFERENCE");
            }
            base=context.proof(item,"POT_PATTERN_MATERIALS",canonical(components),total,children,List.of(),null);
        } else base=context.material(item,depth);
        if(components.has("minecraft:bees")) {
            var bees=array(components.get("minecraft:bees"),3);
            for(var raw:bees)bee(object(raw));
            if(!bees.isEmpty()) {
                Value content=context.content("economy:bee_content",Integer.toString(2*bees.size()),depth+1);
                base=context.proof(item,"BEE_SHELL_AND_CONTENTS",canonical(components),BaseValues.bounded(base.exact().add(content.exact())),
                        List.of(base,content),List.of("economy:bee_content"),null);
            }
        }
        return base;
    }

    private static Value rocket(Context context,JsonObject components,int depth) {
        int flight=1;JsonArray explosions=new JsonArray();
        if(components.has("minecraft:fireworks")) {
            var value=object(components.get("minecraft:fireworks"));allowed(value,Set.of("flight_duration","explosions"),Set.of("flight_duration"));
            flight=integer(value.get("flight_duration"),1,3);
            if(value.has("explosions"))explosions=array(value.get("explosions"),7);
        }
        if(1+flight+explosions.size()>9)throw invalid("FIREWORK_ROCKET_INPUT_SLOTS");
        var paper=context.material("minecraft:paper",depth+1);var powder=context.material("minecraft:gunpowder",depth+1);
        var children=new ArrayList<Value>();children.add(paper);children.add(powder);
        Rational total=BaseValues.bounded(paper.exact().add(powder.exact().multiply(Rational.of(flight))));
        for(var raw:explosions) {var star=explosion(context,object(raw),depth+1);children.add(star);total=BaseValues.bounded(total.add(star.exact()));}
        return context.proof("minecraft:firework_rocket","FIREWORK_ROCKET_THREE_OUTPUTS",canonical(components),BaseValues.bounded(total.divide(Rational.of(3))),children,List.of(),null,3);
    }
    private static Value explosion(Context context,JsonObject explosion,int depth) {
        allowed(explosion,Set.of("shape","colors","fade_colors","has_trail","has_twinkle"),Set.of("shape","colors"));
        String shape=string(explosion.get("shape"));
        var colors=array(explosion.get("colors"),8);if(colors.isEmpty())throw invalid("FIREWORK_COLOR_REQUIRED");
        var fade=explosion.has("fade_colors")?array(explosion.get("fade_colors"),8):new JsonArray();
        boolean trail=explosion.has("has_trail")&&bool(explosion.get("has_trail"));
        boolean twinkle=explosion.has("has_twinkle")&&bool(explosion.get("has_twinkle"));
        int shapeSlots=shape.equals("small_ball")?0:1;
        if(1+colors.size()+shapeSlots+(trail?1:0)+(twinkle?1:0)>9||1+fade.size()>9)throw invalid("FIREWORK_STAR_INPUT_SLOTS");
        var materials=new ArrayList<Value>();materials.add(context.material("minecraft:gunpowder",depth+1));
        switch(shape) {
            case "small_ball" -> {}
            case "large_ball" -> materials.add(context.material("minecraft:fire_charge",depth+1));
            case "burst" -> materials.add(context.material("minecraft:feather",depth+1));
            case "star" -> materials.add(context.material("minecraft:gold_nugget",depth+1));
            case "creeper" -> materials.add(cheapestHead(context,depth+1));
            default -> throw invalid("UNKNOWN_FIREWORK_SHAPE");
        }
        if(trail)materials.add(context.material("minecraft:diamond",depth+1));
        if(twinkle)materials.add(context.material("minecraft:glowstone_dust",depth+1));
        for(var raw:colors)materials.add(color(context,raw,depth+1));
        for(var raw:fade)materials.add(color(context,raw,depth+1));
        Rational total=Rational.ZERO;for(var material:materials)total=BaseValues.bounded(total.add(material.exact()));
        return context.proof("minecraft:firework_star","FIREWORK_COMPONENT_MATERIALS",canonical(explosion),total,materials,List.of(),null);
    }
    private static Value color(Context context,JsonElement value,int depth) {
        int rgb=integer(value,0,0xffffff);String item=context.graph.fireworkColors().get(rgb);
        if(item==null)throw invalid("UNKNOWN_FIREWORK_COLOR");return context.material(item,depth);
    }
    private static Value cheapestHead(Context context,int depth) {
        Value best=null;
        for(String id:List.of("minecraft:creeper_head","minecraft:dragon_head","minecraft:piglin_head","minecraft:skeleton_skull","minecraft:wither_skeleton_skull","minecraft:zombie_head")) {
            try {Value value=context.material(id,depth);if(best==null||value.exact().compareTo(best.exact())<0)best=value;}
            catch(Failure unavailable) {if(unavailable.status==RecipeValues.Status.LIMIT_EXCEEDED||unavailable.status==RecipeValues.Status.OVERFLOW)throw unavailable;}
        }
        if(best==null)throw new Failure(RecipeValues.Status.MISSING_INPUT,"FIREWORK_HEAD_MATERIAL_UNAVAILABLE");return best;
    }
    private static Value stew(Context context,JsonObject components,int depth) {
        if(!components.has("minecraft:suspicious_stew_effects"))throw invalid("STEW_EFFECT_IDENTITY_REQUIRED");
        var entries=array(components.get("minecraft:suspicious_stew_effects"),16);var effects=new ArrayList<VariantGraph.StewEffect>();
        for(var raw:entries) {
            var entry=object(raw);keys(entry,Set.of("id","duration"));effects.add(new VariantGraph.StewEffect(string(entry.get("id")),integer(entry.get("duration"),1,1_000_000)));
        }
        Value flower=null;
        for(var entry:context.graph.flowers().entrySet())if(entry.getValue().equals(effects)) {
            try {Value value=context.material(entry.getKey(),depth+1);if(flower==null||value.exact().compareTo(flower.exact())<0)flower=value;}
            catch(Failure unavailable) {if(unavailable.status==RecipeValues.Status.LIMIT_EXCEEDED||unavailable.status==RecipeValues.Status.OVERFLOW)throw unavailable;}
        }
        if(flower==null)throw invalid("UNSUPPORTED_STEW_EFFECTS");
        var bowl=context.material("minecraft:bowl",depth+1);var red=context.material("minecraft:red_mushroom",depth+1);var brown=context.material("minecraft:brown_mushroom",depth+1);
        Rational total=BaseValues.bounded(bowl.exact().add(red.exact()).add(brown.exact()).add(flower.exact()));
        return context.proof("minecraft:suspicious_stew","STEW_EFFECT_FLOWER_MATERIALS",canonical(components),total,List.of(bowl,red,brown,flower),List.of(),null);
    }
    private static void bucket(String item,JsonObject data) {
        if(!BUCKET_KEYS.containsAll(data.keySet()))throw invalid("UNKNOWN_BUCKET_ENTITY_DATA");
        if(data.has("BucketVariantTag")) {
            if(!item.equals("minecraft:tropical_fish_bucket"))throw invalid("BUCKET_PATTERN_APPLICABILITY");integer(data.get("BucketVariantTag"),0,Integer.MAX_VALUE);
        }
        if(data.has("Variant")) {if(!item.equals("minecraft:axolotl_bucket"))throw invalid("BUCKET_VARIANT_APPLICABILITY");integer(data.get("Variant"),0,4);}
        if(data.has("Age"))integer(data.get("Age"),-1_000_000,1_000_000);
        if(data.has("HuntingCooldown"))integer(data.get("HuntingCooldown"),0,1_000_000);
        if(data.has("Health"))decimal(data.get("Health"),new BigDecimal("0.000001"),new BigDecimal("1024"));
        for(String key:List.of("NoAI","Silent","NoGravity","Glowing","Invulnerable"))if(data.has(key))nbtBoolean(data.get(key));
    }
    private static void bee(JsonObject occupant) {
        keys(occupant,Set.of("entity_data","ticks_in_hive","min_ticks_in_hive"));
        integer(occupant.get("ticks_in_hive"),0,Integer.MAX_VALUE);integer(occupant.get("min_ticks_in_hive"),0,Integer.MAX_VALUE);
        var entity=object(occupant.get("entity_data"));
        if(!BEE_KEYS.containsAll(entity.keySet())||!entity.has("id")||!string(entity.get("id")).equals("minecraft:bee"))throw invalid("UNSUPPORTED_BEE_IDENTITY");
        for(var entry:entity.entrySet()) {
            String key=entry.getKey();JsonElement value=entry.getValue();
            switch(key) {
                case "id" -> {}
                case "Health" -> decimal(value,new BigDecimal("0.000001"),new BigDecimal("1024"));
                case "AbsorptionAmount" -> decimal(value,BigDecimal.ZERO,new BigDecimal("1024"));
                case "Age","ForcedAge","InLove","AngerTime","TicksFrozen" -> integer(value,-1_000_000,Integer.MAX_VALUE);
                case "HasNectar","HasStung","PersistenceRequired","NoAI","Silent","Invulnerable","Glowing","CustomNameVisible" -> nbtBoolean(value);
                case "CustomName" -> {if(string(value).length()>16_384)throw invalid("BEE_NAME_BOUND");}
                case "LoveCause","AngryAt" -> uuid(value);
                case "flower_pos" -> {var pos=object(value);keys(pos,Set.of("X","Y","Z"));integer(pos.get("X"),-30_000_000,30_000_000);integer(pos.get("Y"),-2048,2048);integer(pos.get("Z"),-30_000_000,30_000_000);}
                case "Tags" -> {for(var tag:array(value,32))if(string(tag).length()>256)throw invalid("BEE_TAG_BOUND");}
                case "active_effects" -> {if(!array(value,0).isEmpty())throw invalid("UNSUPPORTED_BEE_EFFECTS");}
                case "Attributes" -> attributes(value);
                default -> throw invalid("UNSUPPORTED_BEE_DATA");
            }
        }
    }
    private static void attributes(JsonElement value) {
        for(var raw:array(value,16)) {
            var attribute=object(raw);allowed(attribute,Set.of("Name","Base","Modifiers"),Set.of("Name","Base"));
            if(!Set.of("minecraft:generic.max_health","minecraft:generic.follow_range","minecraft:generic.movement_speed",
                    "minecraft:generic.flying_speed","minecraft:generic.attack_damage","minecraft:generic.armor","minecraft:generic.armor_toughness",
                    "minecraft:generic.knockback_resistance").contains(string(attribute.get("Name"))))throw invalid("UNKNOWN_BEE_ATTRIBUTE");
            decimal(attribute.get("Base"),BigDecimal.ZERO,new BigDecimal("1024"));
            if(attribute.has("Modifiers")&&!array(attribute.get("Modifiers"),0).isEmpty())throw invalid("UNSUPPORTED_BEE_ATTRIBUTE_MODIFIERS");
        }
    }
    private static void uuid(JsonElement value) {var array=array(value,4);if(array.size()!=4)throw invalid("ENTITY_UUID_BOUND");for(var part:array)integer(part,Integer.MIN_VALUE,Integer.MAX_VALUE);}
    private static void nbtBoolean(JsonElement value) {if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean())bool(value);else integer(value,0,1);}
    private static void decimal(JsonElement value,BigDecimal min,BigDecimal max) {
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid("ENTITY_NUMBER_REQUIRED");
        BigDecimal number=value.getAsBigDecimal();if(number.compareTo(min)<0||number.compareTo(max)>0)throw invalid("ENTITY_NUMBER_BOUND");
    }
    static void dye(String dye) {if(!DYES.contains(dye))throw invalid("UNKNOWN_DYE_COLOR");}
    private static Set<String> patterns() {
        var result=new HashSet<String>();
        for(String name:List.of("base","square_bottom_left","square_bottom_right","square_top_left","square_top_right","stripe_bottom","stripe_top",
                "stripe_left","stripe_right","stripe_center","stripe_middle","stripe_downright","stripe_downleft","small_stripes","cross","straight_cross",
                "triangle_bottom","triangle_top","triangles_bottom","triangles_top","diagonal_left","diagonal_up_right","diagonal_up_left","diagonal_right",
                "circle","rhombus","half_vertical","half_horizontal","half_vertical_right","half_horizontal_bottom","border","curly_border","gradient",
                "gradient_up","bricks","globe","creeper","skull","flower","mojang","piglin","flow","guster"))result.add("minecraft:"+name);
        return Set.copyOf(result);
    }
}
