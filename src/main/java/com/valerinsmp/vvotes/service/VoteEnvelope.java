package com.valerinsmp.vvotes.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Primitive-only snapshot of the five fields exposed by VotifierPlus 1.4.3. */
public record VoteEnvelope(
        String eventHash,
        String normalizedName,
        String displayName,
        String normalizedService,
        String providerTimestamp,
        boolean hasEconomicIdentity,
        boolean testVote
) {
    private static final String FINGERPRINT_VERSION = "vvotes-provider-event-v1";
    private static final int MAX_NAME_LENGTH = 16;
    private static final int MAX_SERVICE_LENGTH = 128;
    private static final int MAX_TIMESTAMP_LENGTH = 128;
    private static final int MAX_ADDRESS_LENGTH = 256;

    public static VoteEnvelope capture(String serviceName, String username, String address,
                                       String timestamp, String sourceAddress) {
        String rawName = strip(username);
        String rawService = strip(serviceName);
        String rawTime = strip(timestamp);
        String rawAddress = strip(address);
        String rawSource = strip(sourceAddress);
        String fingerprintName = rawName.toLowerCase(Locale.ROOT);
        String fingerprintService = rawService.toLowerCase(Locale.ROOT);
        String hash = hashFields(FINGERPRINT_VERSION, fingerprintService, fingerprintName,
                rawAddress, rawTime, rawSource);
        boolean safeName = rawName.matches("[A-Za-z0-9_]{1," + MAX_NAME_LENGTH + "}");
        boolean valid = safeName && !rawService.isBlank() && rawService.length() <= MAX_SERVICE_LENGTH
                && !rawTime.isBlank() && rawTime.length() <= MAX_TIMESTAMP_LENGTH
                && rawAddress.length() <= MAX_ADDRESS_LENGTH && rawSource.length() <= MAX_ADDRESS_LENGTH;
        String storedName = bounded(rawName, MAX_NAME_LENGTH);
        String storedService = bounded(fingerprintService, MAX_SERVICE_LENGTH);
        String storedTime = bounded(rawTime, MAX_TIMESTAMP_LENGTH);
        return new VoteEnvelope(hash, storedName.toLowerCase(Locale.ROOT), storedName, storedService, storedTime,
                valid, "TestVote".equalsIgnoreCase(rawTime));
    }

    static VoteEnvelope manual(PlayerIdentity identity, long epoch, UUID nonce) {
        String timestamp = Long.toString(epoch);
        String hash = hashFields("vvotes-manual-event-v1", identity.uuid().toString(), timestamp, nonce.toString());
        return new VoteEnvelope(hash, identity.normalizedName(), identity.exactName(), "manual",
                timestamp, true, false);
    }

    public VoteEnvelope quarantined() {
        return new VoteEnvelope(eventHash, normalizedName, displayName, normalizedService,
                providerTimestamp, false, testVote);
    }

    static String hashFields(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = strip(field).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
