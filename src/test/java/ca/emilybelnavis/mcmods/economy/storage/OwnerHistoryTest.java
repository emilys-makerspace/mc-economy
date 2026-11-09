package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnerHistoryTest {
    @TempDir Path root;
    private static final Journal.Kind KIND = new Journal.Kind("economy:ledger", "economy:transfer");

    @Test void frozenFilteredOwnerPagesRetainOriginalKeysAcrossNewCommitsAndReopen() throws Exception {
        Path world = Files.createDirectory(root.resolve("pages")).toRealPath();
        var actor = new PlayerId(UUID.randomUUID()); var stranger = new PlayerId(UUID.randomUUID());
        var account = new AccountId(UUID.randomUUID()); var target = new AccountId(UUID.randomUUID());
        PartyRef owner; OwnerHistory.Page first; long frozen;
        var store = new SqliteStorage();
        try {
            store.initialize(world); owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
            catalog(store, account, actor); catalog(store, target, stranger);
            var engine = engine(store, actor);
            post(engine, actor, List.of(leg(account, 100_000), new PostingBatch.Leg(Settlement.issuanceAccount(CAD), PostingBatch.Role.ISSUANCE, CAD, new Money(CAD, -100_000))));
            for (int i=1;i<=5;i++) post(engine, actor, List.of(leg(account,-100L*i),leg(target,100L*i)));
            frozen = store.lastCommittedSequence();
            var query = new OwnerHistory.Query(account.value(),CAD,0L,System.currentTimeMillis()+60_000,OwnerHistory.Type.TRANSFER,2);
            first = store.transaction(tx -> OwnerHistory.page(tx::query,owner,query,frozen,null));
            assertEquals(List.of(-500L,-400L),first.rows().stream().map(r->r.amount().minorUnits()).toList());
            assertNotNull(first.next());
            var newer = post(engine,actor,List.of(leg(account,-600),leg(target,600)));
            var next = store.transaction(tx -> OwnerHistory.page(tx::query,owner,query,frozen,first.next()));
            assertEquals(List.of(-300L,-200L),next.rows().stream().map(r->r.amount().minorUnits()).toList());
            assertTrue(next.rows().stream().noneMatch(r->r.transaction().equals(newer.transactionId().value())));
            var denied = store.transaction(tx -> OwnerHistory.page(tx::query,owner,
                    new OwnerHistory.Query(target.value(),null,null,null,OwnerHistory.Type.ALL,8),frozen,null));
            assertEquals(OwnerHistory.Availability.DENIED,denied.availability());
            assertTrue(store.transaction(tx->OwnerHistory.movement(tx::query,new PartyRef(owner.world(),new PartyRef.Player(stranger)),first.rows().getFirst().key(),frozen)).isEmpty());
            var emptyCurrency = store.transaction(tx -> OwnerHistory.page(tx::query,owner,
                    new OwnerHistory.Query(null,USD,null,null,OwnerHistory.Type.ALL,8),frozen,null));
            assertTrue(emptyCurrency.rows().isEmpty());
            store.transaction(tx -> { tx.execute("UPDATE account_catalog SET status='CLOSED',revision=2 WHERE account_id=?",List.of(account.value().toString())); return null; });
        } finally { store.close(); }
        var reopened = new SqliteStorage();
        try {
            reopened.initialize(world);
            var retained = reopened.transaction(tx->OwnerHistory.page(tx::query,owner,
                    new OwnerHistory.Query(account.value(),CAD,null,null,OwnerHistory.Type.TRANSFER,2),frozen,null));
            assertEquals(first.rows(),retained.rows());
            assertEquals(SchemaMigrations.CURRENT,reopened.snapshot().schemaVersion());
        } finally { reopened.close(); }
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+world.resolve("economy/economy.sqlite"))) {
            OwnerHistorySchema.validateFacts(connection);
            try(var query=connection.prepareStatement("EXPLAIN QUERY PLAN SELECT h.* FROM owner_history h INDEXED BY owner_history_account_currency_type JOIN account_catalog a ON a.account_id=h.account_id AND a.owner_id=h.owner_id WHERE h.world_id=? AND h.owner_id=? AND h.account_id=? AND h.currency='CAD' AND h.category='TRANSFER' AND h.sequence<=? ORDER BY h.sequence DESC,h.position DESC LIMIT 256")) {
                query.setString(1,owner.world().value().toString());query.setString(2,actor.value().toString());query.setString(3,account.value().toString());query.setLong(4,frozen);
                var plan=new ArrayList<String>();try(var rows=query.executeQuery()){while(rows.next())plan.add(rows.getString("detail"));}
                assertTrue(plan.stream().anyMatch(s->s.contains("owner_history_account_currency_type")),plan.toString());
                assertFalse(plan.stream().anyMatch(s->s.contains("TEMP B-TREE")),plan.toString());
            }
            try(var sql=connection.createStatement()){sql.executeUpdate("UPDATE owner_history SET minor=minor+100 WHERE sequence="+first.rows().getFirst().sequence()+" AND position="+first.rows().getFirst().position());}
            assertThrows(StorageException.class,()->OwnerHistorySchema.validateFacts(connection));
        }
        assertThrows(StorageException.class,()->new SqliteStorage().initialize(world));
    }

    @Test void schema52BootstrapResumesBoundedSourceBatchesAndSparseDateQueryStopsAtWorkLimit() throws Exception {
        Path world=Files.createDirectory(root.resolve("bootstrap")).toRealPath();
        var actor=new PlayerId(UUID.randomUUID());var stranger=new PlayerId(UUID.randomUUID());
        var account=new AccountId(UUID.randomUUID());var target=new AccountId(UUID.randomUUID());
        var store=new SqliteStorage(); PartyRef owner;
        try {
            store.initialize(world);owner=new PartyRef(store.snapshot().worldId(),new PartyRef.Player(actor));
            catalog(store,account,actor);catalog(store,target,stranger);
            var engine=engine(store,actor);
            post(engine,actor,List.of(leg(account,1_000_000),new PostingBatch.Leg(Settlement.issuanceAccount(CAD),PostingBatch.Role.ISSUANCE,CAD,new Money(CAD,-1_000_000))));
            var batch=new ArrayList<PostingBatch.Leg>();for(int i=0;i<32;i++){batch.add(leg(account,-100));batch.add(leg(target,100));}
            for(int i=0;i<129;i++)post(engine,actor,batch);
            var state=store.transaction(tx->OwnerHistory.begin(new OwnerHistory.Query(account.value(),null,System.currentTimeMillis()+86_400_000,null,OwnerHistory.Type.ALL,8),OwnerHistory.highWater(tx::query),null));
            int turns=0;
            while(!state.complete()) { var input=state;state=store.transaction(tx->OwnerHistory.scan(tx::query,owner,input));turns++; }
            assertEquals(16,turns);assertEquals(4096,state.examined());
            var page=OwnerHistory.finish(state);assertEquals(OwnerHistory.Availability.WORK_LIMIT,page.availability());assertTrue(page.rows().isEmpty());assertNull(page.next());
        } finally {store.close();}
        // Simulate an actual stopped V52 source, retaining every original journal fact and identity.
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+world.resolve("economy/economy.sqlite"));var sql=connection.createStatement()) {
            sql.execute("DROP TABLE owner_history");sql.execute("DROP TABLE owner_history_state");
            sql.execute("DROP TABLE village_branch_anchors");
            for(var statement:VillageBranchSchema.statements()){
                if(statement.startsWith("CREATE TABLE village_branch_anchors(")||statement.startsWith("CREATE INDEX village_anchor_location "))
                    sql.execute(statement);
            }
            sql.execute("DELETE FROM migration_history WHERE version>52");sql.execute("UPDATE foundation_metadata SET schema_version=52 WHERE singleton=1");sql.execute("PRAGMA user_version=52");
        }
        Files.writeString(world.resolve("level.dat"),"disposable complete-world fixture");UpgradeBackup.prepare(world);
        var partial=new SqliteStorage();
        try {
            partial.beginInitialization(world);
            boolean yielded=false;
            for(int step=0;step<1000;step++) {
                var progress=partial.advanceInitialization();
                if(progress.phase().equals("owner-history")&&!progress.ready()){assertEquals(16,progress.completed());yielded=true;break;}
                assertFalse(progress.ready(),"Bootstrap must yield before complete coverage");
            }
            assertTrue(yielded);
        } finally {partial.close();}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+world.resolve("economy/economy.sqlite"))) {
            assertThrows(StorageException.class,()->OwnerHistorySchema.validateFacts(connection));
        }
        var resumed=new SqliteStorage();
        try {
            resumed.initialize(world);
            var page=resumed.transaction(tx->OwnerHistory.page(tx::query,owner,OwnerHistory.Query.all(),OwnerHistory.highWater(tx::query),null));
            assertEquals(OwnerHistory.Availability.AVAILABLE,page.availability());assertEquals(8,page.rows().size());assertNotNull(page.next());
        } finally {resumed.close();}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+world.resolve("economy/economy.sqlite"))) {OwnerHistorySchema.validateFacts(connection);}
    }
    private static void catalog(SqliteStorage store,AccountId id,PlayerId owner){store.transaction(tx->{tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",List.of(id.value().toString(),owner.value().toString()));return null;});}
    private static JournalEngine engine(SqliteStorage store,PlayerId actor){return new JournalEngine(store,(who,kind)->who.equals(actor),Map.of(KIND,Settlement.registration((intent,legs,reads)->legs.stream().anyMatch(l->l.role()==PostingBatch.Role.ISSUANCE)?PostingBatch.Authority.ISSUE:PostingBatch.Authority.TRANSFER)));}
    private static PostingBatch.Leg leg(AccountId account,long amount){return new PostingBatch.Leg(account.value(),PostingBatch.Role.ACCOUNT,CAD,new Money(CAD,amount));}
    private static Journal.Outcome post(JournalEngine engine,PlayerId actor,List<PostingBatch.Leg> legs){
        var result=engine.submit(new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,KIND,Settlement.request(legs),null,null));
        assertEquals(Journal.Status.COMMITTED,result.status());return result;
    }
}
