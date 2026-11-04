package dev.codex.sentinel.storage;

import dev.codex.sentinel.check.CheckResult;
import java.util.UUID;

public interface ViolationStore extends AutoCloseable {
    void record(UUID playerId, String playerName, CheckResult result, double before, double after, int ping, String world, String action);
    void audit(UUID actor, UUID target, String action, String detail);
    @Override void close();
}
