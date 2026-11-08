package ca.emilybelnavis.mcmods.economy.probe;

import com.mojang.authlib.GameProfile;
import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.journal.CustodySaveWitness;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Runs only in the explicitly opted-in disposable development server. */
@Mod("economy_inventory_probe")
public final class InventoryPersistenceProbe {
    private static final UUID PLAYER = UUID.fromString("25f242cd-5f01-4d6c-bd06-20ef7193ba27");
    private static final UUID CUSTODY = UUID.fromString("15235352-e0ad-4608-af08-06fc65377d53");
    private boolean ran;

    public InventoryPersistenceProbe(net.neoforged.bus.api.IEventBus bus) {
        if(Boolean.getBoolean("economy.valuationApiProbe"))ValuationApiClient.register(bus);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ServerTickEvent.Post event) {
        if (ran || Boolean.getBoolean("economy.referenceTraderProbe") || Boolean.getBoolean("economy.valuationApiProbe") || Boolean.getBoolean("economy.variantProbe") || Boolean.getBoolean("economy.brewingProbe") || Boolean.getBoolean("economy.recipeProbe") || Boolean.getBoolean("economy.valuationProbe") || System.getProperty("economy.financeWorld") != null) return;
        if (Boolean.getBoolean("economy.villageBankAcceptance")) {
            var services = Economy.serverServices(event.getServer());
            if (services == null || services.state() != ServerServices.State.READY) return;
            try {
                ran = VillageBankAcceptanceProbe.tick(event.getServer());
            } catch (Throwable failure) {
                failure.printStackTrace();
                VillageBankAcceptanceProbe.failure = failure.toString();
                ran = true;
            }
            return;
        }
        if(Boolean.getBoolean("economy.simulationProductionProbe")) {
            try{ran=SimulationProductionProbe.tick(event.getServer());}catch(Throwable e){e.printStackTrace();SimulationProductionProbe.failure=e.toString();ran=true;}return;
        }
        if(Boolean.getBoolean("economy.traderCustodyProbe")) {
            var services=Economy.serverServices(event.getServer());if(services==null||services.state()!=ServerServices.State.READY)return;
            try{ran=TraderCustodyProbe.tick(event.getServer());}catch(Throwable e){e.printStackTrace();TraderCustodyProbe.failure=e.toString();ran=true;}
            return;
        }
        if(Boolean.getBoolean("economy.traderHookProbe")) {
            var services=Economy.serverServices(event.getServer());if(services==null||services.state()!=ServerServices.State.READY)return;
            try{ran=TraderHookProbe.tick(event.getServer());}catch(Throwable e){e.printStackTrace();TraderHookProbe.failure=e.toString();ran=true;}
            return;
        }
        if(Boolean.getBoolean("economy.bankerProbe")){
            var services=Economy.serverServices(event.getServer());if(services==null||services.state()!=ServerServices.State.READY)return;
            try{ran=BankerProbe.tick(event.getServer());}catch(Throwable e){e.printStackTrace();BankerProbe.failure=e.toString();ran=true;}
            return;
        }
        if(Boolean.getBoolean("economy.bankStructureProbe")){
            var services=Economy.serverServices(event.getServer());if(services==null||services.state()!=ServerServices.State.READY)return;
            try{ran=BankStructureProbe.tick(event.getServer());}catch(Throwable e){e.printStackTrace();BankStructureProbe.failure=e.toString();ran=true;}
            return;
        }
        if(Boolean.getBoolean("economy.bankLocateProbe")){
            var services=Economy.serverServices(event.getServer());if(services==null||services.state()!=ServerServices.State.READY)return;
            try{ran=BankLocateProbe.tick(event.getServer());}catch(Throwable e){e.printStackTrace();BankLocateProbe.failure=e.toString();ran=true;}
            return;
        }
        if (Boolean.getBoolean("economy.villageGenerationProbe")) {
            var server=event.getServer();var services=Economy.serverServices(server);
            if(services==null||services.state()!=ServerServices.State.READY)return;
            try{if(VillageGenerationProbe.tick(server))ran=true;}catch(Throwable failure){ran=true;VillageGenerationProbe.fail(failure);}
            return;
        }
        if (Boolean.getBoolean("economy.kioskUpgradeProbe")) {
            var server=event.getServer();var services=Economy.serverServices(server);
            if(services==null||services.state()!=ServerServices.State.READY)return;
            try { if(KioskUpgradeProbe.tick(server))ran=true; } catch(Throwable failure) { ran=true;KioskUpgradeProbe.fail(failure); }
            return;
        }
        var server = event.getServer();
        var services = Economy.serverServices(server);
        if (services == null || services.state() != ServerServices.State.READY) return;
        if (Boolean.getBoolean("economy.branchReleaseProbe")) {
            try {
                if (BranchReleaseProbe.tick(server)) { ran = true; server.halt(false); }
            } catch (Exception failure) {
                failure.printStackTrace();
                try {
                    var trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
                    Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("branch-release-failed.txt"), trace.toString());
                } catch (Exception ignored) { }
                ran = true; server.halt(false);
            }
            return;
        }
        if (Boolean.getBoolean("economy.financeProbe")) {
            try {
                if (FinanceAcceptanceProbe.tick(server)) { ran = true; server.halt(false); }
            } catch (Exception failure) {
                failure.printStackTrace();
                try {
                    var trace = new java.io.StringWriter();
                    failure.printStackTrace(new java.io.PrintWriter(trace));
                    Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("finance-probe-failed.txt"), trace.toString());
                } catch (Exception ignored) { }
                ran = true; server.halt(false);
            }
            return;
        }
        if (Boolean.getBoolean("economy.collectionProbe")) {
            try {
                if (CollectionAcceptanceProbe.tick(server)) { ran=true;server.halt(false); }
            } catch(Exception failure) {
                failure.printStackTrace();
                try { var trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("collection-probe-failed.txt"),trace.toString()); }catch(Exception ignored) {}
                ran=true;server.halt(false);
            }
            return;
        }
        if (Boolean.getBoolean("economy.bankingRecovery")) {
            try {
                if (BankingRecoveryProbe.tick(server)) { ran = true; server.halt(false); }
            } catch (Exception failure) {
                failure.printStackTrace();
                try { Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("banking-recovery-failed.txt"), failure.toString()); }
                catch (Exception ignored) {}
                ran = true; server.halt(false);
            }
            return;
        }
        if (Boolean.getBoolean("economy.bankingProbe")) {
            try {
                BankingRecoveryProbe.maybeCrash(server);
                if (BankingAcceptanceProbe.tick(server)) { ran = true; server.halt(false); }
            } catch (Exception failure) {
                failure.printStackTrace();
                try {
                    var trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
                    Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("banking-probe-failed.txt"), trace.toString());
                }
                catch (Exception ignored) {}
                ran = true; server.halt(false);
            }
            return;
        }
        if (Boolean.getBoolean("economy.terminalGeometryProbe")) {
            try {
                if (TerminalGeometryProbe.tick(server)) { ran = true; server.halt(false); }
            } catch (Exception failure) {
                failure.printStackTrace();
                try { Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("terminal-probe-failed.txt"), failure.toString()); }
                catch (Exception ignored) {}
                ran = true; server.halt(false);
            }
            return;
        }
        ran = true;
        try {
            run(server);
        } catch (Exception failure) {
            failure.printStackTrace();
            try {
                Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("inventory-probe-failed.txt"),
                        failure.toString());
            } catch (Exception ignored) {}
        } finally {
            server.halt(false);
        }
    }

    private void run(MinecraftServer server) throws Exception {
        CashCatalogProbe.verify(server);
        DepositInventoryProbe.run(server);
        WithdrawalInventoryProbe.run(server);
        RegisterInventoryProbe.run(server);
        var root = server.getWorldPath(LevelResource.ROOT).toRealPath();
        var report = root.resolve("inventory-probe.properties");
        var field = MinecraftServer.class.getDeclaredField("playerDataStorage");
        field.setAccessible(true);
        var storage = (PlayerDataStorage) field.get(server);
        var profile = new GameProfile(PLAYER, "CustodyFixture");
        var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        var relative = "playerdata/" + PLAYER + ".dat";
        var file = root.resolve(relative);
        if (!Files.exists(report)) {
            var properties = new Properties();
            var worldId = UUID.randomUUID();
            properties.setProperty("world", worldId.toString());
            save(storage, player, worldId, "reserved", 4);
            var reserved = Files.readAllBytes(file);
            Files.write(root.resolve("inventory-reserved.dat"), reserved);
            properties.setProperty("reserved", CustodySaveWitness.digest(reserved));
            save(storage, player, worldId, "delivered", 7);
            var delivered = Files.readAllBytes(file);
            Files.write(root.resolve("inventory-delivered.dat"), delivered);
            properties.setProperty("delivered", CustodySaveWitness.digest(delivered));
            checkPlayer(server, storage, profile, worldId, "delivered", 7);
            CustodyChunkInventoryProbe.prepare(server);
            try (var output = Files.newOutputStream(report)) { properties.store(output, "Persisted first-process evidence"); }
            System.out.println("CUSTODY_INVENTORY_PHASE1_PASS actual PlayerDataStorage save/load and components");
        } else {
            var properties = new Properties();
            try (var input = Files.newInputStream(report)) { properties.load(input); }
            var worldId = new WorldId(UUID.fromString(properties.getProperty("world")));
            checkPlayer(server, storage, profile, worldId.value(), "delivered", 7);
            var reserved = new CustodySaveWitness.Expected(worldId, CUSTODY, generation("reserved"), relative, properties.getProperty("reserved"));
            var delivered = new CustodySaveWitness.Expected(worldId, CUSTODY, generation("delivered"), relative, properties.getProperty("delivered"));
            require(CustodySaveWitness.matches(root, delivered), "paired delivered snapshot");
            require(!CustodySaveWitness.matches(root, reserved), "older expected reservation with newer saved inventory");
            Files.copy(root.resolve("inventory-reserved.dat"), file, StandardCopyOption.REPLACE_EXISTING);
            checkPlayer(server, storage, profile, worldId.value(), "reserved", 4);
            require(CustodySaveWitness.matches(root, reserved), "paired reserved snapshot");
            require(!CustodySaveWitness.matches(root, delivered), "newer expected delivery with older saved inventory");
            Files.copy(root.resolve("inventory-delivered.dat"), file, StandardCopyOption.REPLACE_EXISTING);
            checkPlayer(server, storage, profile, worldId.value(), "delivered", 7);
            CustodyChunkInventoryProbe.verifyRestartAndMove(server);
            CustodyMinecraftInventoryProbe.run(Files.createDirectory(root.resolve("container-restore-" + UUID.randomUUID())),
                    server.registryAccess());
            Files.writeString(root.resolve("inventory-probe-passed.txt"),
                    "PASS real player inventory save/load across server restart, paired snapshots and both skew directions");
            System.out.println("CUSTODY_INVENTORY_PHASE2_PASS restart paired snapshots both skew directions real SQLite container restores");
        }
    }

    private static void save(PlayerDataStorage storage, ServerPlayer player, UUID world, String phase, int count) {
        var stack = new ItemStack(Items.DIAMOND, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Custody witness " + phase));
        player.getInventory().setItem(0, stack);
        player.getInventory().setItem(1, ca.emilybelnavis.mcmods.economy.cash.CashStackIdentity.marked(
                new ItemStack(ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().getFirst().item().get(), count),
                new WorldId(world), CUSTODY));
        var marker = player.getPersistentData();
        marker.putUUID("custody", CUSTODY);
        marker.putUUID("world", world);
        marker.putUUID("generation", generation(phase));
        marker.putString("phase", phase);
        storage.save(player);
    }

    private static void checkPlayer(MinecraftServer server, PlayerDataStorage storage, GameProfile profile, UUID world,
                                    String phase, int count) {
        var reloaded = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        require(storage.load(reloaded).isPresent(), "player data load");
        var stack = reloaded.getInventory().getItem(0);
        require(stack.is(Items.DIAMOND) && stack.getCount() == count, "saved exact item/count");
        require(Component.literal("Custody witness " + phase).equals(stack.get(DataComponents.CUSTOM_NAME)),
                "saved custom component");
        var cash = reloaded.getInventory().getItem(1);
        require(cash.getCount() == count && ca.emilybelnavis.mcmods.economy.cash.CashStackIdentity.mark(cash, new WorldId(world))
                .orElseThrow().lot().equals(CUSTODY), "cash lot survives actual player persistence");
        require(CUSTODY.equals(reloaded.getPersistentData().getUUID("custody"))
                && world.equals(reloaded.getPersistentData().getUUID("world"))
                && generation(phase).equals(reloaded.getPersistentData().getUUID("generation"))
                && phase.equals(reloaded.getPersistentData().getString("phase")), "saved custody marker");
    }

    private static UUID generation(String phase) {
        return UUID.nameUUIDFromBytes(("custody-probe:" + phase).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Inventory fixture failed: " + message);
    }
}
