package com.valerinsmp.vvotes.service;

record DateContext(String dayKey, String monthKey, long epochSeconds, long dayStartEpoch, long nextDayEpoch) {}
