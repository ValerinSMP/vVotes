package com.valerinsmp.vvotes.service;

public record MonthlyDrawResult(Status status, String monthKey, String winnerName, double topVotes,
                                int candidatesCount, String error) {
    public enum Status { SUCCESS, NO_PARTICIPANTS, ALREADY_DRAWN, DISABLED, INVALID_MONTH, ERROR }

    public static MonthlyDrawResult success(String monthKey, String winnerName, double topVotes, int candidatesCount) {
        return new MonthlyDrawResult(Status.SUCCESS, monthKey, winnerName, topVotes, candidatesCount, "");
    }
    public static MonthlyDrawResult noParticipants(String monthKey, double topVotes) {
        return new MonthlyDrawResult(Status.NO_PARTICIPANTS, monthKey, "", topVotes, 0, "");
    }
    public static MonthlyDrawResult alreadyDrawn(String monthKey) {
        return new MonthlyDrawResult(Status.ALREADY_DRAWN, monthKey, "", 0, 0, "");
    }
    public static MonthlyDrawResult disabled() {
        return new MonthlyDrawResult(Status.DISABLED, "", "", 0, 0, "");
    }
    public static MonthlyDrawResult invalidMonth(String monthKey) {
        return new MonthlyDrawResult(Status.INVALID_MONTH, monthKey, "", 0, 0, "");
    }
    public static MonthlyDrawResult error(String monthKey, String error) {
        return new MonthlyDrawResult(Status.ERROR, monthKey, "", 0, 0, error);
    }
}
