package dev.codex.sentinel.storage;

import dev.codex.sentinel.check.CheckResult;

import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Single-writer SQLite store. Writes are queued, batched into transactions and never block the server thread. */
public final class SqliteViolationStore implements ViolationStore {
    private static final int BATCH = 200;

    @FunctionalInterface private interface Task { void run() throws SQLException; }

    private final BlockingQueue<Task> queue;
    private final Thread worker;
    private final Logger logger;
    private final Connection connection;
    private final PreparedStatement insertViolation;
    private final PreparedStatement insertAudit;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    public SqliteViolationStore(Path file, int capacity, Logger logger, int retentionDays) throws SQLException {
        this.logger = logger;
        this.queue = new ArrayBlockingQueue<>(Math.max(100, capacity));
        try { Class.forName("org.sqlite.JDBC"); }   // DriverManager will not find a plugin-classloader driver on its own
        catch (ClassNotFoundException e) { throw new SQLException("SQLite JDBC driver not found", e); }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("PRAGMA journal_mode=WAL");
            s.executeUpdate("PRAGMA synchronous=NORMAL");
            s.executeUpdate("PRAGMA busy_timeout=5000");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS violations(id INTEGER PRIMARY KEY, ts TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, check_name TEXT NOT NULL, category TEXT NOT NULL, weight REAL NOT NULL, confirmed INTEGER NOT NULL, evidence TEXT NOT NULL, vl_before REAL NOT NULL, vl_after REAL NOT NULL, ping INTEGER NOT NULL, world TEXT, action TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, ts TEXT NOT NULL, actor TEXT, target TEXT, action TEXT NOT NULL, detail TEXT NOT NULL)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_violations_uuid_ts ON violations(uuid, ts)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_violations_ts ON violations(ts)");
        }
        insertViolation = connection.prepareStatement("INSERT INTO violations(ts,uuid,name,check_name,category,weight,confirmed,evidence,vl_before,vl_after,ping,world,action) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)");
        insertAudit = connection.prepareStatement("INSERT INTO audit(ts,actor,target,action,detail) VALUES(?,?,?,?,?)");
        worker = new Thread(this::run, "SentinelAC-Storage");
        worker.setDaemon(true);
        worker.start();
        if (retentionDays > 0) {
            String cutoff = Instant.now().minus(Duration.ofDays(retentionDays)).toString();
            submit(() -> { // audit rows are intentionally kept: they are the accountability trail
                try (PreparedStatement p = connection.prepareStatement("DELETE FROM violations WHERE ts < ?")) {
                    p.setString(1, cutoff);
                    int n = p.executeUpdate();
                    if (n > 0) logger.info("SentinelAC pruned " + n + " violation rows older than " + retentionDays + " days");
                }
            });
        }
    }

    private void run() {
        List<Task> batch = new ArrayList<>(BATCH);
        try {
            while (running || !queue.isEmpty()) {
                Task first;
                try { first = queue.poll(250, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                flush(batch);
                batch.clear();
            }
        } finally {
            try { insertViolation.close(); insertAudit.close(); connection.close(); }
            catch (SQLException e) { logger.warning("Unable to close SentinelAC database: " + e.getMessage()); }
        }
    }

    /** One transaction per batch; a bad row is logged and skipped without losing its neighbours. */
    private void flush(List<Task> batch) {
        try {
            connection.setAutoCommit(false);
            for (Task t : batch) {
                try { t.run(); }
                catch (SQLException e) { logger.warning("SentinelAC storage operation failed: " + e.getMessage()); }
            }
            connection.commit();
        } catch (SQLException e) {
            logger.warning("SentinelAC storage batch failed (" + batch.size() + " records lost): " + e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) { }
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    private void submit(Task task) {
        if (!running) return;
        if (!queue.offer(task)) {
            long n = dropped.incrementAndGet();
            if (n % 100 == 1) logger.warning("SentinelAC storage queue full; " + n + " records dropped so far");
        }
    }

    @Override public void record(UUID id, String name, CheckResult r, double before, double after, int ping, String world, String action) {
        final String ts = Instant.now().toString();
        submit(() -> {
            insertViolation.setString(1, ts); insertViolation.setString(2, id.toString()); insertViolation.setString(3, name);
            insertViolation.setString(4, r.check()); insertViolation.setString(5, r.category()); insertViolation.setDouble(6, r.weight());
            insertViolation.setInt(7, r.confirmed() ? 1 : 0); insertViolation.setString(8, r.evidence());
            insertViolation.setDouble(9, before); insertViolation.setDouble(10, after); insertViolation.setInt(11, ping);
            insertViolation.setString(12, world); insertViolation.setString(13, action);
            insertViolation.executeUpdate();
        });
    }

    @Override public void audit(UUID actor, UUID target, String action, String detail) {
        final String ts = Instant.now().toString();
        submit(() -> {
            insertAudit.setString(1, ts); insertAudit.setString(2, actor == null ? null : actor.toString());
            insertAudit.setString(3, target == null ? null : target.toString());
            insertAudit.setString(4, action); insertAudit.setString(5, detail);
            insertAudit.executeUpdate();
        });
    }

    /** Stops accepting writes, drains what is queued, and closes the database. Never interrupts JDBC. */
    @Override public void close() {
        running = false;
        try {
            worker.join(5000);
            if (worker.isAlive()) logger.warning("SentinelAC storage worker did not finish within 5s");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
