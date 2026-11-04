package dev.codex.sentinel.check;

public record CheckResult(String check, String category, double weight, boolean confirmed, String evidence) { }
