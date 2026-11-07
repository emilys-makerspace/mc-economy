package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeFacts;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Additive v2 provenance validation; legacy fixed/global contracts keep their old meaning. */
final class LoanPrimeBindings {
    private LoanPrimeBindings() {}
    static void validate(Connection connection)throws SQLException {
        JournalEngine.ReadView reads=(sql,args,maximum)->query(connection,sql,args,maximum);
        try(var statement=connection.createStatement();var rows=statement.executeQuery(
                "SELECT r.sequence,r.position,r.record_version,r.record_type,r.payload,t.world_id,t.operation_id FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE (r.record_type='economy:a6/loan' AND r.record_version IN (1,2)) OR (r.record_type='economy:a6/loan-application' AND r.record_version=2) ORDER BY r.sequence,r.position")) {
            while(rows.next()) {
                if(rows.getString("record_type").equals(Loans.FACT)) {
                    var loan=Loans.decode(rows.getString("payload"),rows.getInt("record_version"));
                    if(!loan.world().value().toString().equals(rows.getString("world_id")))throw new IllegalArgumentException("Currency loan envelope world");
                    requireStableCapture(reads,loan,rows.getLong("sequence"),rows.getInt("position"));
                    if(loan.primePolicyId()!=null)require(reads,loan.world(),loan.currency(),loan.anchor(),loan.primePolicyId(),loan.primePolicyRevision(),loan.primeApr(),rows.getLong("sequence"));
                }else {
                    var decision=Loans.decodeApplicationDecision(rows.getString("payload"),2);
                    if(!decision.world().value().toString().equals(rows.getString("world_id")))throw new IllegalArgumentException("Currency loan application envelope world");
                    require(reads,decision.world(),decision.currency(),decision.effectiveAt(),decision.primePolicyId(),decision.primePolicyRevision(),decision.primeApr(),rows.getLong("sequence"));
                    var projections=reads.query("SELECT operation_id FROM loan_application_ids WHERE world_id=? AND application_id=?",List.of(decision.world().value().toString(),decision.application().toString()),1);
                    if(projections.size()!=1||!projections.getFirst().get("operation_id").equals(rows.getString("operation_id")))throw new IllegalArgumentException("Currency loan application source projection");
                    var originated=reads.query("SELECT record_version,payload FROM journal_records WHERE sequence=? AND record_type=? ORDER BY position LIMIT 2",List.of(Long.toString(rows.getLong("sequence")),Loans.FACT),2);
                    if(decision.approved()) {
                        if(originated.size()!=1)throw new IllegalArgumentException("Currency loan original snapshot missing");
                        var loan=Loans.decode(originated.getFirst().get("payload"),Integer.parseInt(originated.getFirst().get("record_version")));
                        if(loan.revision()!=1||!loan.world().equals(decision.world())||!loan.owner().equals(decision.owner())||!loan.id().equals(decision.loan())
                                ||loan.currency()!=decision.currency()||loan.originalPrincipalMinor()!=decision.requestedMinor()||loan.anchor()!=decision.effectiveAt()
                                ||!loan.primePolicyId().equals(decision.primePolicyId())||loan.primePolicyRevision()!=decision.primePolicyRevision()
                                ||loan.primeApr().compareTo(decision.primeApr())!=0||loan.apr().compareTo(decision.apr())!=0)
                            throw new IllegalArgumentException("Currency loan accepted prime capture");
                    }else if(!originated.isEmpty())throw new IllegalArgumentException("Denied currency application originated a loan");
                }
            }
        }catch(RuntimeException invalid){throw new SQLException("Invalid currency-prime loan provenance",invalid);}
        // Every projected v2 body must still match the last immutable loan snapshot.
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT world_id,loan_id,body FROM loans ORDER BY world_id,loan_id")) {
            while(rows.next()) {
                var loan=Loans.decode(rows.getString("body"));if(loan.primePolicyId()==null)continue;
                var latest=reads.query("SELECT record_version,payload FROM journal_records WHERE record_type='economy:a6/loan' AND substr(payload,instr(payload,'|')+1,48)=? ORDER BY sequence DESC,position DESC LIMIT 1",
                        List.of(Base64.getUrlEncoder().withoutPadding().encodeToString(loan.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))),1);
                if(latest.size()!=1||!latest.getFirst().get("record_version").equals("2")||!latest.getFirst().get("payload").equals(rows.getString("body")))throw new SQLException("Currency loan projection does not match source");
            }
        }catch(RuntimeException invalid){throw new SQLException("Invalid currency-prime loan projection",invalid);}
    }
    private static void requireStableCapture(JournalEngine.ReadView reads,Loans.Contract loan,long sequence,int position) {
        var previous=reads.query("SELECT record_version,payload FROM journal_records WHERE record_type='economy:a6/loan' AND substr(payload,instr(payload,'|')+1,48)=? AND (sequence,position)<(?,?) ORDER BY sequence DESC,position DESC LIMIT 1",
                List.of(Base64.getUrlEncoder().withoutPadding().encodeToString(loan.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),Long.toString(sequence),Integer.toString(position)),1);
        if(previous.isEmpty()) {
            if(loan.primePolicyId()!=null&&loan.revision()!=1)throw new IllegalArgumentException("Currency loan missing original capture");
            return;
        }
        var old=Loans.decode(previous.getFirst().get("payload"),Integer.parseInt(previous.getFirst().get("record_version")));
        if(old.primePolicyId()==null&&loan.primePolicyId()==null)return;
        if(!Objects.equals(old.primePolicyId(),loan.primePolicyId())||old.primePolicyRevision()!=loan.primePolicyRevision()
                ||old.primeVersion()!=loan.primeVersion()||old.primeApr().compareTo(loan.primeApr())!=0||old.apr().compareTo(loan.apr())!=0
                ||!old.world().equals(loan.world())||!old.owner().equals(loan.owner())||!old.lender().equals(loan.lender())||old.product()!=loan.product()
                ||old.currency()!=loan.currency()||old.anchor()!=loan.anchor()||!old.scheduleHash().equals(loan.scheduleHash()))
            throw new IllegalArgumentException("Fixed currency prime capture changed or lost");
    }
    private static void require(JournalEngine.ReadView reads,WorldId world,EconomyCurrency currency,long at,UUID id,long revision,BigDecimal apr,long sourceSequence) {
        var policy=CurrencyPrimes.current(reads,world,currency,at).orElseThrow(()->new IllegalArgumentException("Historical currency prime unavailable"));
        if(!policy.id().equals(id)||policy.revision()!=revision||policy.primeApr().compareTo(apr)!=0||policy.effectiveMillis()>at)
            throw new IllegalArgumentException("Historical currency prime mismatch");
        var source=reads.query("SELECT r.sequence FROM currency_prime_policies p JOIN journal_transactions t ON t.world_id=p.world_id AND t.operation_id=p.operation_id JOIN journal_records r ON r.sequence=t.sequence WHERE p.world_id=? AND p.id=? AND r.record_type=? AND r.record_version=1 AND r.payload=? LIMIT 2",
                List.of(world.value().toString(),id.toString(),CurrencyPrimeFacts.POLICY,CurrencyPrimeFacts.encode(policy)),2);
        if(source.size()!=1||Long.parseLong(source.getFirst().get("sequence"))>=sourceSequence)
            throw new IllegalArgumentException("Currency prime was not published before loan capture");
    }
    private static List<Map<String,String>> query(Connection connection,String sql,List<String> args,int maximum) {
        if(maximum<1||maximum>1024)throw new IllegalArgumentException("Loan validation query bound");
        try(var statement=connection.prepareStatement(sql)) {
            for(int i=0;i<args.size();i++)statement.setString(i+1,args.get(i));
            try(var rows=statement.executeQuery()) {
                var result=new ArrayList<Map<String,String>>();var metadata=rows.getMetaData();
                while(rows.next()){if(result.size()>=maximum)throw new IllegalArgumentException("Loan validation query bound");var row=new LinkedHashMap<String,String>();for(int i=1;i<=metadata.getColumnCount();i++)row.put(metadata.getColumnLabel(i),rows.getString(i));result.add(Collections.unmodifiableMap(row));}
                return List.copyOf(result);
            }
        }catch(SQLException invalid){throw new IllegalStateException("Loan prime evidence read failed",invalid);}
    }
}
