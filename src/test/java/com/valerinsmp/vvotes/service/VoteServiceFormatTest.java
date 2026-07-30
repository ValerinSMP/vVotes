package com.valerinsmp.vvotes.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoteServiceFormatTest {
    @Test
    void formatsWholeAndFractionalVoteCounts() {
        assertEquals("4", VoteService.formatDoubleStatic(4));
        assertEquals("4.25", VoteService.formatDoubleStatic(4.25));
    }
}
