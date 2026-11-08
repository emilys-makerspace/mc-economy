package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Actual access-transformer and brewing snapshot witness; fresh disposable client only. */
public final class BrewingClient {
    private boolean opened,stopped;
    private final long start=System.nanoTime();
    private volatile String result,failure;
    private int phase,ticks;
    private UUID original;
    private CompletableFuture<Boolean> query;
    public BrewingClient() { NeoForge.EVENT_BUS.addListener(this::client); NeoForge.EVENT_BUS.addListener(this::server); }
    private void client(ClientTickEvent.Post event) {
        if(stopped)return;
        var client=Minecraft.getInstance();client.options.pauseOnLostFocus=false;ticks++;
        try {
            if(!opened&&client.getOverlay()==null&&client.screen!=null&&client.level==null) {
                opened=true;String name="economy-brewing-"+UUID.randomUUID();
                require(!Files.exists(client.gameDirectory.toPath().resolve("saves").resolve(name)),"fresh world");
                client.createWorldOpenFlows().createFreshLevel(name,new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,
                        true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(90302L,false,false),
                        WorldPresets::createNormalWorldDimensions,client.screen);
            }
            if(client.player!=null&&client.player.containerMenu instanceof BankingMenu menu&&menu.worldSetup()&&!menu.busy()&&ticks%20==0)
                BankingNetwork.action(menu,"setup","CAD");
            if(result==null&&failure==null&&System.nanoTime()-start>240_000_000_000L)failure="Brewing fixture deadline phase="+phase;
            if(result!=null||failure!=null) {
                stopped=true;Files.writeString(client.gameDirectory.toPath().resolve(failure==null?"brewing-passed.txt":"brewing-failed.txt"),
                        failure==null?result:failure);client.execute(client::stop);
            }
        }catch(Throwable error){error.printStackTrace();failure=error.toString();stopped=true;client.execute(client::stop);}
    }
    private void server(ServerTickEvent.Post event) {
        if(result!=null||failure!=null)return;
        var server=event.getServer();var services=Economy.serverServices(server);
        if(services==null||services.state()!=ServerServices.State.READY||server.getPlayerList().getPlayers().isEmpty())return;
        try {
            Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            require(world.getFileName().toString().matches("economy-brewing-[0-9a-f-]{36}"),"guarded disposable world");
            if(query!=null) {
                if(!query.isDone())return;
                boolean ready=query.join();query=null;if(!ready)return;phase++;
            }
            var status=ValuationRuntime.status(server);
            if(phase==0&&status.ready()) {
                original=status.snapshot();
                query=services.tradeStorage(store->{
                    var fx=store.transaction(tx->FxService.at(tx::query,System.currentTimeMillis()));if(fx==null)return false;
                    var manifest=ValuationData.decode(ValuationStore.active(store).payload());var graph=manifest.brewing();
                    require(manifest.version()==3&&graph!=null&&graph.vanillaCompatible(),"actual compatible brewing capture");
                    require(graph.potions().size()==46&&graph.containers().size()==3&&graph.mixes().size()==281,"complete pinned definitions and expanded mixes");
                    check(manifest,fx,"potion","water",EconomyCurrency.CAD,10);
                    check(manifest,fx,"potion","awkward",EconomyCurrency.CAD,50);
                    check(manifest,fx,"splash_potion","awkward",EconomyCurrency.CAD,73);
                    var lingering=check(manifest,fx,"lingering_potion","awkward",EconomyCurrency.CAD,917);
                    require(lingering.explanation().steps().stream().filter(s->s.kind().equals("WATER_FREE_CONTENT_BOTTLE_ONCE")).count()==1,"potion bottle counted once");
                    require(lingering.explanation().steps().stream().filter(s->s.item().equals("minecraft:glass_bottle")).count()==2,"dragon-breath input includes its own shared bottle");
                    require(lingering.explanation().steps().stream().anyMatch(s->s.kind().contains("GLASS_BOTTLE_NO_SALVAGE")),"remainder retained without salvage");
                    check(manifest,fx,"lingering_potion","awkward",EconomyCurrency.USD,657);
                    for(String potion:List.of("swiftness","long_swiftness","strong_swiftness")) {
                        var value=RecipeValues.evaluate(manifest,"minecraft:potion",BrewingGraph.components("minecraft:"+potion),1,EconomyCurrency.CAD,fx);
                        require(value.status()==RecipeValues.Status.READY,"strength/duration path "+potion+" "+value.reason());
                    }
                    require(graph.potions().get("minecraft:long_swiftness").effects().getFirst().duration()>
                            graph.potions().get("minecraft:swiftness").effects().getFirst().duration(),"duration identity");
                    require(graph.potions().get("minecraft:strong_swiftness").effects().getFirst().amplifier()>
                            graph.potions().get("minecraft:swiftness").effects().getFirst().amplifier(),"strength identity");
                    System.out.println("BREWING_CAPTURE_COUNTS definitions="+graph.potions().size()+" expanded_mixes="+graph.mixes().size());
                    return true;
                }).toCompletableFuture();
            } else if(phase==1) {
                Path config=FMLPaths.CONFIGDIR.get().resolve("economy-valuation.json").toAbsolutePath().normalize();
                require(config.startsWith(Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize())&&!Files.exists(config),"fresh disposable config");
                Files.writeString(config,"{\"bases\":[],\"overrides\":[{\"item\":\"minecraft:nether_wart\",\"currency\":\"CAD\",\"amount\":\"2\",\"referenceCount\":1}]}");
                server.reloadResources(server.getPackRepository().getSelectedIds()).join();
                require(!ValuationRuntime.status(server).ready(),"pending reload admission");phase=2;
            } else if(phase==2&&status.ready()) {
                require(!original.equals(status.snapshot()),"changed material yields new immutable brewing snapshot");
                query=services.tradeStorage(store->{
                    var fx=store.transaction(tx->FxService.at(tx::query,System.currentTimeMillis()));
                    var newer=ValuationData.decode(ValuationStore.active(store).payload());
                    var older=ValuationData.decode(ValuationStore.load(store,original).payload());
                    check(newer,fx,"lingering_potion","awkward",EconomyCurrency.CAD,950);
                    check(older,fx,"lingering_potion","awkward",EconomyCurrency.CAD,917);return true;
                }).toCompletableFuture();
            } else if(phase==3) {
                result="PASS actual pinned accessor:46 definitions/281 expanded mixes/3forms; water10 awkward50 splash73 lingering917 cents; USD657; bottle once, no-salvage remainder, strength/duration states; material reload950 and original917 retained; normal shutdown.";
                System.out.println("BREWING_PROBE_PASS "+result);
            }
        }catch(Throwable error){error.printStackTrace();failure=error.toString();}
    }
    private static RecipeValues.Result check(ValuationData.Manifest manifest,ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot fx,
            String form,String potion,EconomyCurrency currency,long expected) {
        var value=RecipeValues.evaluate(manifest,"minecraft:"+form,BrewingGraph.components("minecraft:"+potion),1,currency,fx);
        require(value.status()==RecipeValues.Status.READY&&value.unitMoney().minorUnits()==expected,
                form+"/"+potion+" expected="+expected+" status="+value.status()+" value="+value.unitMoney()+" reason="+value.reason());return value;
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
