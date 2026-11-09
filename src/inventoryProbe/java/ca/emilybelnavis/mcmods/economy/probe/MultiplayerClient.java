package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.banking.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Opt-in authenticated socket driver. Local files coordinate; all banking actions use ordinary packets. */
public final class MultiplayerClient {
    private final Path root;
    private final String role;
    private final String endpoint;
    private boolean attempted, reconnect;
    private int lastActionTick = -100;
    private long serial;
    private UUID reported;
    private boolean failed;
    public static boolean enabled() {
        return Files.isRegularFile(Minecraft.getInstance().gameDirectory.toPath().resolve("a10-04-client.properties"));
    }
    public MultiplayerClient() {
        try {
            var client=Minecraft.getInstance();var game=client.gameDirectory.toPath().toRealPath();
            if(!game.toString().contains("/a10-04-client-"))throw new IllegalStateException("Fresh a10-04-client instance required");
            var p=new Properties();try(var in=Files.newInputStream(game.resolve("a10-04-client.properties"))){p.load(in);}
            root=MultiplayerProbe.guard(Path.of(p.getProperty("fixtureRoot")));role=p.getProperty("role");
            endpoint=p.getProperty("endpoint");
            if(!Set.of("127.0.0.1:25565","127.0.0.1:25566").contains(endpoint))throw new IllegalStateException("Explicit loopback endpoint required");
            if(!Set.of("A","B").contains(role))throw new IllegalStateException("Explicit A/B role required");
            NeoForge.EVENT_BUS.addListener(this::tick);
        }catch(Exception e){throw new IllegalStateException("Socket fixture client configuration",e);}
    }
    private void write(String name,String text)throws Exception {var target=root.resolve(name);var temp=root.resolve(name+".tmp");Files.writeString(temp,text);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private void tick(ClientTickEvent.Post event) {
        var client=Minecraft.getInstance();
        if(Files.exists(root.resolve("quit-"+role+".txt")) || Files.exists(root.resolve("multiplayer-passed.txt")) || Files.exists(root.resolve("multiplayer-failed.txt"))) { client.stop(); return; }
        if(failed)return;
        client.options.pauseOnLostFocus=false;
        try {
            if(client.player==null) {
                if(client.getOverlay()==null && client.screen instanceof TitleScreen && (!attempted || reconnect&&Files.exists(root.resolve("reconnect-A.txt")))) {
                    attempted=true;reconnect=false;
                    ConnectScreen.startConnecting(client.screen,client,ServerAddress.parseString(endpoint),new ServerData("Disposable A10-04",endpoint,ServerData.Type.OTHER),false,null);
                }
                return;
            }
            if(reported==null){write(role+"-online.txt",client.player.getUUID().toString());reported=client.player.getUUID();}
            if(client.player.containerMenu instanceof BankingMenu setup&&setup.worldSetup()) {
                if(!setup.busy()&&client.player.tickCount%20==0)BankingNetwork.action(setup,"setup","CAD");return;
            }
            var file=root.resolve(role+"-command.properties");if(!Files.isRegularFile(file))return;
            if(Files.size(file)>8192)throw new IllegalStateException("Fixture command bound");
            var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
            long id=Long.parseLong(p.getProperty("serial"));if(id<=serial)return;
            String kind=p.getProperty("kind");
            if(kind.equals("disconnect")) {
                serial=id;write(role+"-ack.txt",Long.toString(id));
                reconnect=true;lastActionTick=-100;client.level.disconnect();client.disconnect();client.setScreen(new TitleScreen());
                System.out.println("MULTIPLAYER_CLIENT_RECONNECT_REQUIRED role="+role);return;
            }
            if(kind.equals("close")) {
                client.player.closeContainer();serial=id;write(role+"-ack.txt",Long.toString(id));return;
            }
            if(!(client.player.containerMenu instanceof BankingMenu menu)||menu.worldSetup())return;
            long revision=Long.parseLong(p.getProperty("revision"));
            if(!kind.equals("raw")&&(menu.containerId!=Integer.parseInt(p.getProperty("menu"))||!menu.session().toString().equals(p.getProperty("session"))||menu.revision()!=revision))return;
            var action=new BankingNetwork.Action(Integer.parseInt(p.getProperty("menu")),UUID.fromString(p.getProperty("session")),revision,p.getProperty("action"),p.getProperty("value",""));
            if(client.player.tickCount-lastActionTick<4)return;
            lastActionTick=client.player.tickCount;
            PacketDistributor.sendToServer(action);
            if(Boolean.parseBoolean(p.getProperty("duplicate","false")))PacketDistributor.sendToServer(action);
            serial=id;write(role+"-ack.txt",Long.toString(id));
            System.out.println("MULTIPLAYER_CLIENT_SENT role="+role+" serial="+id+" action="+action.action()+" revision="+revision+" nano="+System.nanoTime());
        }catch(Throwable e){failed=true;e.printStackTrace();try{Files.writeString(root.resolve(role+"-client-failed.txt"),e.toString());}catch(Exception ignored){}}
    }
}
