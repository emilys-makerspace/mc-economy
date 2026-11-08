package ca.emilybelnavis.mcmods.economy.probe;

import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Never opens an existing world or accepts a dedicated-server EULA. */
public final class TraderHookClient {
    private boolean opened,stopped;private final long started=System.nanoTime();
    public TraderHookClient(){NeoForge.EVENT_BUS.addListener(this::tick);}
    private void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("economy.traderHookProbe")||stopped)return;
        var client=Minecraft.getInstance();client.options.pauseOnLostFocus=false;
        try {
            if(!opened&&client.getOverlay()==null&&client.screen!=null&&client.level==null) {
                opened=true;String name="economy-trader-hooks-"+UUID.randomUUID();
                if(Files.exists(client.gameDirectory.toPath().resolve("saves").resolve(name)))throw new IllegalStateException("Existing fixture refused");
                var settings=new LevelSettings(name,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
                client.createWorldOpenFlows().createFreshLevel(name,settings,new WorldOptions(704L,true,false),WorldPresets::createNormalWorldDimensions,client.screen);return;
            }
            String failure=TraderHookProbe.failure,result=TraderHookProbe.result;
            if(failure==null&&result==null&&System.nanoTime()-started>240_000_000_000L)failure="Trader hook fixture deadline";
            if(failure!=null||result!=null) {
                stopped=true;Files.writeString(client.gameDirectory.toPath().resolve(failure==null?"trader-hook-passed.txt":"trader-hook-failed.txt"),failure==null?result:failure);
                client.execute(client::stop);
            }
        }catch(Throwable e){stopped=true;try{Files.writeString(client.gameDirectory.toPath().resolve("trader-hook-failed.txt"),e.toString());}catch(Exception ignored){}client.execute(client::stop);}
    }
}
