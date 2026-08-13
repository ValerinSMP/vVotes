package com.valerinsmp.vvotes.service;

import java.util.List;

public record VoteEventResult(VoteEventState state, String eventHash, List<String> grantIds,
                              List<VoteNotice> notices, String error) {
    static VoteEventResult of(VoteEventState state, String hash, List<String> grants) {
        return new VoteEventResult(state, hash, List.copyOf(grants), List.of(), "");
    }

    static VoteEventResult planned(String hash, List<String> grants, List<VoteNotice> notices) {
        return new VoteEventResult(VoteEventState.PLANNED, hash, List.copyOf(grants), List.copyOf(notices), "");
    }

    static VoteEventResult error(String hash, Exception exception) {
        return new VoteEventResult(VoteEventState.ERROR, hash, List.of(), List.of(), exception.getMessage());
    }
}
