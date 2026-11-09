package ca.emilybelnavis.mcmods.economy.card;

import com.google.gson.JsonParser;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.Custody;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardItemResourcesTest {
    private static final Path ASSETS=Path.of("src/main/resources/assets/economy");
    @Test void onlyApprovedDebitProductsResolveToExistingOriginalArtAndLocalization() throws Exception {
        var expected=new HashSet<String>();
        for(var bank:InstitutionCatalog.banks()) {
            if(bank.tier1())expected.add(CardDesigns.id(bank.id(),1));
            if(bank.tier2())expected.add(CardDesigns.id(bank.id(),2));
        }
        assertEquals(23,expected.size());assertEquals(expected,new HashSet<>(CardDesigns.all().stream().map(CardDesigns.Design::id).toList()));
        var lang=JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"))).getAsJsonObject();
        for(var entry:CardDesigns.all()) {
            assertFalse(entry.id().contains("credit"));
            var item=entry.id().substring("economy:".length());
            var model=JsonParser.parseString(Files.readString(ASSETS.resolve("models/item/"+item+".json"))).getAsJsonObject();
            var parent=model.get("parent").getAsString();
            assertEquals("economy:item/banking/"+item.substring("issued_".length()),parent);
            var original=ASSETS.resolve("models/"+parent.substring("economy:".length())+".json");assertTrue(Files.isRegularFile(original));
            var texture=JsonParser.parseString(Files.readString(original)).getAsJsonObject().getAsJsonObject("textures").get("layer0").getAsString();
            assertTrue(Files.isRegularFile(ASSETS.resolve("textures/"+texture.substring("economy:".length())+".png")));
            assertTrue(lang.has("item.economy."+item));
            assertFalse(Files.exists(Path.of("src/main/resources/data/economy/recipe/"+item+".json")));
        }
        assertThrows(IllegalArgumentException.class,()->CardDesigns.id(new InstitutionCatalog.InstitutionId("economy:bank/atlas_reserve"),1));
        assertThrows(IllegalArgumentException.class,()->CardDesigns.id(new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),3));
    }
    @Test void issuedCreditCardsHavePlayerFacingNames() throws Exception {
        var lang = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"))).getAsJsonObject();
        for (var design : CreditCardDesigns.all()) {
            var key = "item.economy." + design.id().substring("economy:".length());
            assertTrue(lang.has(key), key);
            assertFalse(lang.get(key).getAsString().isBlank(), key);
        }
    }

    @Test void bothProductsHaveOnlyUntieredItemsAndRetiredNamesResolveForSavedDeliveries() throws Exception {
        assertEquals(23, CardDesigns.all().size());
        assertEquals(36, CreditCardDesigns.all().size());
        assertEquals(CardDesigns.all(), CardDesigns.collectibles());
        var lang = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"))).getAsJsonObject();
        var aliases = new HashMap<>(CardDesigns.legacyAliases());
        aliases.putAll(CreditCardDesigns.legacyAliases());
        assertEquals(74, aliases.size());
        var world=new WorldId(UUID.randomUUID());var token=UUID.randomUUID();var owner=new PlayerId(UUID.randomUUID());
        for (var alias : aliases.entrySet()) {
            String oldItem=alias.getKey().substring("economy:".length());
            String currentItem=alias.getValue().substring("economy:".length());
            assertFalse(currentItem.matches(".*_t[12]$"));
            assertFalse(Files.exists(ASSETS.resolve("models/item/"+oldItem+".json")));
            assertTrue(Files.isRegularFile(ASSETS.resolve("models/item/"+currentItem+".json")));
            assertFalse(lang.get("item.economy."+currentItem).getAsString().contains("Tier"));
            var saved=delivery(world,token,token,owner,alias.getKey(),"issued-card:"+token,Journal.actorKey(owner));
            assertEquals(new CardInventory.Delivery(world,token,alias.getValue()),CardInventory.decode(saved));
        }
        for (var bank : InstitutionCatalog.banks()) {
            if (!bank.tier1() || !bank.tier2()) continue;
            assertEquals(CardDesigns.id(bank.id(),1),CardDesigns.id(bank.id(),2));
            for (var network : bank.networks())
                assertEquals(CreditCardDesigns.id(bank.id(),network.key(),1),CreditCardDesigns.id(bank.id(),network.key(),2));
        }
    }

    @Test void markerParsingIsCanonicalBoundedAndContainsNoPrivateAuthority() {
        var world=new WorldId(UUID.randomUUID());var token=UUID.fromString("abcdef12-abcd-abcd-abcd-abcdef123456");
        var marker=new CompoundTag();marker.putString("world",world.value().toString());marker.putString("token",token.toString());
        var data=new CompoundTag();data.put(CardStackIdentity.KEY,marker);
        var parsed=CardStackIdentity.parse(data,world).orElseThrow();assertEquals(token,parsed.token());
        assertEquals(parsed,CardStackIdentity.parse(data.copy(),world).orElseThrow(),"copies reference the same authoritative token");
        assertTrue(CardStackIdentity.parse(data,new WorldId(UUID.randomUUID())).isEmpty());
        marker.putString("owner","forged");assertTrue(CardStackIdentity.parse(data,world).isEmpty());marker.remove("owner");
        marker.putString("token",token.toString().toUpperCase(Locale.ROOT));assertTrue(CardStackIdentity.parse(data,world).isEmpty());
        marker.putString("token","1-1-1-1-1");assertTrue(CardStackIdentity.parse(data,world).isEmpty());
        marker.putString("token","x".repeat(10_000));assertTrue(CardStackIdentity.parse(data,world).isEmpty());
        assertTrue(CardStackIdentity.parse(new CompoundTag(),world).isEmpty());
    }
    @Test void custodyPayloadMustMatchProtectedCaseAndIssuanceOperation() {
        var world=new WorldId(UUID.randomUUID());var token=UUID.randomUUID();var owner=new PlayerId(UUID.randomUUID());
        String item="economy:issued_crownstone_debit";
        var valid=delivery(world,token,token,owner,item,"issued-card:"+token,Journal.actorKey(owner));
        assertEquals(new CardInventory.Delivery(world,token,item),CardInventory.decode(valid));
        assertThrows(IllegalArgumentException.class,()->CardInventory.decode(delivery(world,UUID.randomUUID(),token,owner,item,"issued-card:"+token,Journal.actorKey(owner))));
        assertThrows(IllegalArgumentException.class,()->CardInventory.decode(delivery(world,token,token,owner,item,"withdrawal:"+token,Journal.actorKey(owner))));
        assertThrows(IllegalArgumentException.class,()->CardInventory.decode(delivery(world,token,token,owner,item,"issued-card:"+token,"player:"+UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,()->CardInventory.decode(delivery(world,token,token,owner,"economy:issued_crownstone_credit_t1","issued-card:"+token,Journal.actorKey(owner))));
    }
    private Custody.Case delivery(WorldId world,UUID caseId,UUID token,PlayerId owner,String item,String source,String destination) {
        var original=new Journal.Intent(new OperationId(token),new RequestId(UUID.randomUUID()),owner,new Journal.Kind("economy:cards","economy:issue"),new Journal.CanonicalRequest(1,Map.of()),null,null);
        var terms=new Custody.Terms(owner,"issued-card-v1\n"+world.value()+"|"+token+"|"+item,source,destination,Custody.financialIntent(original));
        return new Custody.Case(caseId,terms,Custody.State.COMMITTED,token,token,null,"","","",Custody.Evidence.unknown(),"",1,null);
    }
}
