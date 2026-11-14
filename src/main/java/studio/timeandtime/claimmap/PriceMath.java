package studio.timeandtime.claimmap;

/**
 * Shop price arithmetic (PHASES.md 4.2). Pure {@code long} math so it can be
 * tested without Minecraft. Guarantees {@code buyPrice > sellPrice >= 1}, which
 * makes buy-then-sell-back a guaranteed loss (no arbitrage).
 */
public final class PriceMath {
	private PriceMath() {
	}

	/** @return floor(base * sellPercent / 100), at least 1. */
	public static long sellPrice(long base, int sellPercent) {
		if (base < 1) return 0;
		long raw;
		try {
			raw = Math.multiplyExact(base, (long) sellPercent);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
		return Math.max(1L, raw / 100L);
	}

	/** @return ceil(base * buyPercent / 100), always greater than the sell price. */
	public static long buyPrice(long base, int sellPercent, int buyPercent) {
		long sell = sellPrice(base, sellPercent);
		long raw;
		try {
			raw = Math.multiplyExact(base, (long) buyPercent);
		} catch (ArithmeticException e) {
			raw = Long.MAX_VALUE;
		}
		long buy = raw / 100L + (raw % 100L == 0 ? 0 : 1); // ceilDiv for positives
		long floor = sell == Long.MAX_VALUE ? Long.MAX_VALUE : sell + 1;
		return Math.max(floor, buy);
	}

	/** Percentages accepted by the config (and required for the no-arbitrage rule). */
	public static boolean validPercents(int sellPercent, int buyPercent) {
		return sellPercent >= 1 && sellPercent <= 100 && buyPercent >= 100 && sellPercent < buyPercent;
	}
}
