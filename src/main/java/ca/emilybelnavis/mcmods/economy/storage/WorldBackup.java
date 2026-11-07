package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Offline complete-world backup/restore. No retention automation, online copying or replacement. */
public final class WorldBackup {
    private WorldBackup() {}
    public record Restored(Path destination,String worldId,long lastJournal,long economicCheckpoint) {}
    public static Path create(Path stoppedWorld,FoundationRuntime.Configuration configuration) throws IOException {
        return UpgradeBackup.prepare(stoppedWorld,true,configuration);
    }
    public static Restored restore(Path backup,Path destination,FoundationRuntime.Configuration configuration) throws IOException {
        backup=backup.toAbsolutePath().normalize(); destination=destination.toAbsolutePath().normalize();
        StorageFiles.rejectLinks(backup);StorageFiles.rejectLinks(destination);
        if(Files.exists(destination) || !Files.isDirectory(destination.getParent())) throw new IOException("Restore requires a new separate destination with an existing parent");
        if(destination.startsWith(backup) || backup.startsWith(destination)) throw new IOException("Restore destination overlaps backup");
        var receipt=UpgradeBackup.load(backup.resolve("receipt.properties"));
        UpgradeBackup.validateBackup(backup,receipt);
        var metadata=UpgradeBackup.load(backup.resolve("foundation-manifest.properties"));
        if(!"economy-world-backup-v1".equals(metadata.getProperty("format")) || !"true".equals(metadata.getProperty("clean-shutdown"))
                || !"0.1.0".equals(metadata.getProperty("mod-version")) || !Set.of("4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45").contains(metadata.getProperty("schema-version", "")) || !"1".equals(metadata.getProperty("journal-version"))) throw new IOException("Incompatible or unclean backup manifest");
        Instant.parse(metadata.getProperty("backup-utc"));
        OfflineInspector.validate(backup.resolve("world"),configuration);
        var actual=OfflineInspector.metadata(backup.resolve("world"));
        if(!Integer.toString(actual.evidence().schema()).equals(metadata.getProperty("schema-version")) || !actual.databaseClean() || !actual.evidence().worldId().equals(metadata.getProperty("world-id"))
                || actual.evidence().lastJournal()!=Long.parseLong(metadata.getProperty("last-journal-sequence"))
                || actual.evidence().economicSequence()!=Long.parseLong(metadata.getProperty("economic-checkpoint"))) throw new IOException("Backup manifest/checkpoint mismatch");
        Path reservation=destination.resolveSibling("."+destination.getFileName()+".restore.lock");StorageFiles.rejectLinks(reservation);
        try(var lock=WorldOwnership.acquire(reservation)) {
            if(Files.exists(destination)) throw new IOException("Restore destination already exists");
            Path staging=Files.createDirectory(destination.resolveSibling("."+destination.getFileName()+".restoring-"+UUID.randomUUID()));
            Path source=backup.resolve("world");
            try(var paths=Files.walk(source)) {
                for(var iterator=paths.iterator();iterator.hasNext();) {
                    Path path=iterator.next();StorageFiles.rejectLinks(path);
                    if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Restore interrupted");
                    Path target=staging.resolve(source.relativize(path));
                    if(Files.isDirectory(path)) Files.createDirectories(target);
                    else if(Files.isRegularFile(path)) {Files.copy(path,target);try(var channel=java.nio.channels.FileChannel.open(target,StandardOpenOption.WRITE)){channel.force(true);}}
                    else throw new IOException("Unsupported backup file");
                }
            }
            var inventory=UpgradeBackup.load(backup.resolve("manifest.properties"));
            for(String name:inventory.stringPropertyNames()) {
                Path file=staging.resolve(name).normalize();
                if(!file.startsWith(staging) || !StorageFiles.hash(file).equals(inventory.getProperty(name))) throw new IOException("Restore checksum mismatch");
            }
            OfflineInspector.validate(staging,configuration);
            UpgradeBackup.validateBackup(backup,receipt);
            // The copied clean witness referred to the old runtime lock; bind it to this new offline destination.
            WorldShutdown.writeClean(staging,actual.evidence());
            try(var paths=Files.walk(staging)) {for(Path directory:paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList())StorageFiles.forceDirectory(directory);}
            if(Files.exists(destination)) throw new IOException("Destination appeared during restore; staging preserved");
            Files.move(staging,destination); // Deliberately no REPLACE_EXISTING.
            StorageFiles.forceDirectory(destination.getParent());
            return new Restored(destination,actual.evidence().worldId(),actual.evidence().lastJournal(),actual.evidence().economicSequence());
        }
    }
    public static void main(String[] args) throws IOException {
        if(args.length==2 && args[0].equals("backup")) System.out.println("Validated complete-world backup: "+create(Path.of(args[1]),FoundationRuntime.Configuration.production(java.time.Clock.systemUTC())));
        else if(args.length==3 && args[0].equals("restore")) System.out.println("Restored separate world: "+restore(Path.of(args[1]),Path.of(args[2]),FoundationRuntime.Configuration.production(java.time.Clock.systemUTC())));
        else throw new IllegalArgumentException("Use backup <stopped-world> or restore <backup-directory> <new-destination>");
    }
}
