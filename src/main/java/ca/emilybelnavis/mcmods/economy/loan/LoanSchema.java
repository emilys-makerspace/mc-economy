package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.*;

/** V28: captured unsecured fixed-rate contracts, projected schedules and immutable due snapshots. */
public final class LoanSchema {
    private LoanSchema() {}
    private static final List<String> NAMES=List.of("loans","loan_owner","loan_outstanding_owner","loan_outstanding_lender","loan_month","loan_schedule","loan_installments","loan_installment_owner","loan_application_ids");
    public static List<String> statements(){return List.of(
        "CREATE TABLE loans(world_id TEXT NOT NULL,loan_id TEXT NOT NULL,owner_id TEXT NOT NULL,lender TEXT NOT NULL,product TEXT NOT NULL,currency TEXT NOT NULL,status TEXT NOT NULL,debt_minor INTEGER NOT NULL CHECK(debt_minor>=0),next_month INTEGER NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),body TEXT NOT NULL,PRIMARY KEY(world_id,loan_id))",
        "CREATE INDEX loan_owner ON loans(world_id,owner_id,loan_id)",
        "CREATE INDEX loan_outstanding_owner ON loans(world_id,owner_id,currency,loan_id) WHERE status<>'SETTLED'",
        "CREATE INDEX loan_outstanding_lender ON loans(world_id,lender,currency,loan_id) WHERE debt_minor>0",
        "CREATE INDEX loan_month ON loans(status,next_month,loan_id)",
        "CREATE TABLE loan_schedule(world_id TEXT NOT NULL,loan_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal>0 AND ordinal<=104),due_at INTEGER NOT NULL,payment_minor INTEGER NOT NULL CHECK(payment_minor>=0),principal_minor INTEGER NOT NULL CHECK(principal_minor>=0),interest_minor INTEGER NOT NULL CHECK(interest_minor>=0),balance_minor INTEGER NOT NULL CHECK(balance_minor>=0),PRIMARY KEY(world_id,loan_id,ordinal))",
        "CREATE TABLE loan_installments(world_id TEXT NOT NULL,loan_id TEXT NOT NULL,installment_id TEXT NOT NULL,owner_id TEXT NOT NULL,ordinal INTEGER NOT NULL,due_at INTEGER NOT NULL,original_minor INTEGER NOT NULL CHECK(original_minor>=0),body TEXT NOT NULL,PRIMARY KEY(world_id,installment_id),UNIQUE(world_id,loan_id,ordinal))",
        "CREATE INDEX loan_installment_owner ON loan_installments(world_id,owner_id,loan_id,ordinal)",
        "CREATE TABLE loan_application_ids(world_id TEXT NOT NULL,application_id TEXT NOT NULL,operation_id TEXT NOT NULL,PRIMARY KEY(world_id,application_id),UNIQUE(world_id,operation_id))");}
    public static void validate(Connection connection)throws SQLException {
        for(int i=0;i<NAMES.size();i++)try(var q=connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")){
            q.setString(1,NAMES.get(i));try(var r=q.executeQuery()){if(!r.next()||!statements().get(i).equals(r.getString(1)))throw new StorageException(StorageException.Kind.SCHEMA,"Missing or changed loan schema");}
        }
        LoanPrimeBindings.validate(connection);
    }
}
