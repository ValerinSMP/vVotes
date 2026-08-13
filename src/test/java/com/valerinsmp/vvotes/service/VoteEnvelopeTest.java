package com.valerinsmp.vvotes.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoteEnvelopeTest {
    @Test
    void fingerprintIsVersionedDeterministicAndUsesEveryOfficialField() {
        VoteEnvelope baseline = VoteEnvelope.capture(" PlanetMinecraft ", " Steve ", "203.0.113.1", "1700000000", "198.51.100.2");
        VoteEnvelope normalized = VoteEnvelope.capture("planetminecraft", "steve", "203.0.113.1", "1700000000", "198.51.100.2");

        assertEquals(baseline.eventHash(), normalized.eventHash());
        assertEquals("steve", baseline.normalizedName());
        assertEquals("planetminecraft", baseline.normalizedService());

        assertNotEquals(baseline.eventHash(), VoteEnvelope.capture("other", "Steve", "203.0.113.1", "1700000000", "198.51.100.2").eventHash());
        assertNotEquals(baseline.eventHash(), VoteEnvelope.capture("PlanetMinecraft", "Alex", "203.0.113.1", "1700000000", "198.51.100.2").eventHash());
        assertNotEquals(baseline.eventHash(), VoteEnvelope.capture("PlanetMinecraft", "Steve", "203.0.113.9", "1700000000", "198.51.100.2").eventHash());
        assertNotEquals(baseline.eventHash(), VoteEnvelope.capture("PlanetMinecraft", "Steve", "203.0.113.1", "1700000001", "198.51.100.2").eventHash());
        assertNotEquals(baseline.eventHash(), VoteEnvelope.capture("PlanetMinecraft", "Steve", "203.0.113.1", "1700000000", "198.51.100.9").eventHash());
    }

    @Test
    void missingTimestampCannotBecomeEconomicIdentity() {
        VoteEnvelope envelope = VoteEnvelope.capture("site", "Steve", "203.0.113.1", "  ", "198.51.100.2");

        assertFalse(envelope.hasEconomicIdentity());
        assertTrue(envelope.testVote() == false);
    }

    @Test
    void providerTestVoteIsExplicitButNotProductiveUniquenessEvidence() {
        VoteEnvelope envelope = VoteEnvelope.capture("site", "Steve", "203.0.113.1", "TestVote", "198.51.100.2");

        assertTrue(envelope.hasEconomicIdentity());
        assertTrue(envelope.testVote());
        assertFalse(VoteService.applyProviderPolicy(envelope, false).hasEconomicIdentity());
        assertTrue(VoteService.applyProviderPolicy(envelope, true).hasEconomicIdentity());
        assertEquals(envelope.eventHash(), VoteService.applyProviderPolicy(envelope, false).eventHash());
    }

    @Test
    void oversizedOfficialFieldsAreBoundedAndCannotCreateEconomicState() {
        VoteEnvelope envelope = VoteEnvelope.capture("s".repeat(129), "InvalidNameThatIsTooLong",
                "a".repeat(257), "t".repeat(129), "source");

        assertFalse(envelope.hasEconomicIdentity());
        assertEquals(16, envelope.displayName().length());
        assertEquals(128, envelope.normalizedService().length());
        assertEquals(128, envelope.providerTimestamp().length());
        assertEquals(64, envelope.eventHash().length());
    }
}
