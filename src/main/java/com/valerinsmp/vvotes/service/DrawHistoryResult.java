package com.valerinsmp.vvotes.service;

public record DrawHistoryResult(Status status, String monthKey, String winnerName, String winnerUuid,
                                double topVotes, int candidatesCount, String executedBy,
                                long executedEpoch, String error) {
    public enum Status { FOUND, NOT_FOUND, INVALID_MONTH, ERROR }

    public static DrawHistoryResult found(String monthKey, String winnerName, String winnerUuid,
                                         double topVotes, int candidatesCount, String executedBy, long executedEpoch) {
        return new DrawHistoryResult(Status.FOUND, monthKey, winnerName, winnerUuid, topVotes, candidatesCount, executedBy, executedEpoch, "");
    }
    public static DrawHistoryResult notFound(String monthKey) {
        return new DrawHistoryResult(Status.NOT_FOUND, monthKey, "", "", 0, 0, "", 0, "");
    }
    public static DrawHistoryResult invalidMonth(String monthKey) {
        return new DrawHistoryResult(Status.INVALID_MONTH, monthKey, "", "", 0, 0, "", 0, "");
    }
    public static DrawHistoryResult error(String monthKey, String error) {
        return new DrawHistoryResult(Status.ERROR, monthKey, "", "", 0, 0, "", 0, error);
    }
}
