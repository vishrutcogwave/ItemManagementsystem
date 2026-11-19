import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

class MigrationUpgradeCheck {
    private static long scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new IllegalStateException("No results for " + sql);
            return result.getLong(1);
        }
    }

    public static void main(String[] args) throws Exception {
        var directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectory(directory);
        try (var postgres = EmbeddedPostgres.builder().setPort(0).setDataDirectory(directory.toFile()).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var previous = Flyway.configure().dataSource(dataSource).target("13").load();
            if (previous.migrate().migrationsExecuted != 13) throw new IllegalStateException("Expected V1 through V13");
            try (var db = dataSource.getConnection(); var statement = db.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO stores(store_code,store_name,owner_name,email,mobile,address,subscription_start_date,subscription_end_date)
                    VALUES ('QA1','QA Store 1','QA','one@example.test','9876543210','Test',current_date,current_date + 365),
                           ('QA2','QA Store 2','QA','two@example.test','9876543210','Test',current_date,current_date + 365);
                    INSERT INTO branches(store_id,branch_code,branch_name,address,contact_number)
                    SELECT id,'QA','QA Branch','Test','9876543210' FROM stores;
                    INSERT INTO sales(store_id,branch_id,sales_date,sales_number,paid_amount,payments)
                    SELECT store_id,id,current_date,'SAL-1109',10,'[{"method":"CASH","amount":10}]'::jsonb FROM branches;
                    INSERT INTO sales(store_id,branch_id,sales_date,sales_number)
                    SELECT store_id,id,current_date,CASE WHEN store_id=1 THEN '25' ELSE '30' END FROM branches;
                    """);
            }

            var current = Flyway.configure().dataSource(dataSource).load();
            if (current.migrate().migrationsExecuted != 1) throw new IllegalStateException("Expected only V14 on upgrade");
            current.validate();
            try (var db = dataSource.getConnection()) {
                if (scalar(db, "SELECT count(*) FROM sales WHERE sales_number='SAL-1109' AND paid_amount=10 AND payments->0->>'method'='CASH'") != 2) {
                    throw new IllegalStateException("Legacy sale references or payments changed");
                }
                if (scalar(db, "SELECT nextval('sale_document_seq')") != 31 ||
                    scalar(db, "SELECT nextval('purchase_document_seq')") != 1) {
                    throw new IllegalStateException("Incorrect document sequence start");
                }
                db.setAutoCommit(false);
                long rolledBack = scalar(db, "SELECT nextval('sale_document_seq')");
                db.rollback();
                if (scalar(db, "SELECT nextval('sale_document_seq')") <= rolledBack) {
                    throw new IllegalStateException("Number allocated in a rollback was reused");
                }
            }

            var executor = Executors.newFixedThreadPool(8);
            try {
                var allocations = IntStream.range(0, 32).mapToObj(index -> (Callable<Long>) () -> {
                    try (var db = dataSource.getConnection()) {
                        return scalar(db, "SELECT nextval('sale_document_seq')");
                    }
                }).toList();
                var unique = new HashSet<Long>();
                for (var result : executor.invokeAll(allocations)) unique.add(result.get());
                if (unique.size() != allocations.size()) throw new IllegalStateException("Concurrent number reuse");
            } finally {
                executor.shutdown();
            }

            if (current.migrate().migrationsExecuted != 0) throw new IllegalStateException("Startup reapplied migrations");
            System.out.println("PASS V13 to V14 upgrade preserved legacy bills and payments, seeded sequences, handled rollback and 32 concurrent allocations");
        }
    }
}
