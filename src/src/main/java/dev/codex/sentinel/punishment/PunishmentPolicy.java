package dev.codex.sentinel.punishment;

public record PunishmentPolicy(boolean monitoringOnly, boolean kickEnabled, double kickThreshold,
                               boolean kickRequiresConfirmation, boolean banEnabled, double banThreshold,
                               boolean banRequiresConfirmation) {
    public enum Action { NONE, KICK, BAN }
    public static boolean permitsPermanentBan(boolean currentCheckConfirmed, int independentConfirmedChecks) {
        return currentCheckConfirmed && independentConfirmedChecks >= 2;
    }
    public Action decide(double vl, boolean confirmed) {
        if (monitoringOnly) return Action.NONE;
        if (banEnabled && vl >= banThreshold && (!banRequiresConfirmation || confirmed)) return Action.BAN;
        if (kickEnabled && vl >= kickThreshold && (!kickRequiresConfirmation || confirmed)) return Action.KICK;
        return Action.NONE;
    }
}
