package com.valerinsmp.vvotes.service;

import java.util.UUID;

public record GrantClaim(
        String grantId,
        String batchKey,
        int sequence,
        String kind,
        String commandSnapshot,
        String executorMode,
        UUID targetUuid,
        String targetName,
        String claimToken,
        String state,
        String error
) {}
