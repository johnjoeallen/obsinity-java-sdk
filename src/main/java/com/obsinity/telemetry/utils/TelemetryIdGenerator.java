package com.obsinity.telemetry.utils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/** Minimal UUIDv7 generator + OTEL-friendly hex helpers. */
public final class TelemetryIdGenerator {
	private static final SecureRandom RNG = new SecureRandom();

	public static UUID generate() {
		long millis = Instant.now().toEpochMilli();             // 48 bits
		long msb = (millis & 0xFFFFFFFFFFFFL) << 16;            // timestamp << 16
		msb |= 0x7000L;                                         // version 7 in bits 12..15
		msb |= (RNG.nextLong() & 0x0FFFL);                      // 12-bit rand_a

		long lsb = RNG.nextLong();
		lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // set variant 10xx

		return new UUID(msb, lsb);
	}

	/** 128-bit hex (lowercase, 32 chars) — good for OTEL traceId. */
	public static String hex128(UUID u) {
		return String.format("%016x%016x", u.getMostSignificantBits(), u.getLeastSignificantBits());
	}

	/** 64-bit hex from LSB (lowercase, 16 chars) — good for OTEL spanId. */
	public static String hex64lsb(UUID u) {
		return String.format("%016x", u.getLeastSignificantBits());
	}

	private TelemetryIdGenerator() {}
}
