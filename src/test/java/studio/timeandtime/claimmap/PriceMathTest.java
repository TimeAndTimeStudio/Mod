package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceMathTest {
	private static final int SELL = 70;
	private static final int BUY = 130;

	@Test
	void buyIsAlwaysAboveSellForTypicalBases() {
		long[] bases = {1, 2, 3, 10, 99, 1000, EconomyManager.MAX_MONEY};
		for (long base : bases) {
			long sell = PriceMath.sellPrice(base, SELL);
			long buy = PriceMath.buyPrice(base, SELL, BUY);
			assertTrue(sell >= 1, "sell >= 1 for base " + base);
			assertTrue(buy > sell, "buy > sell for base " + base + " (buy=" + buy + ", sell=" + sell + ")");
		}
	}

	@Test
	void randomBasesNeverAllowArbitrage() {
		Random rnd = new Random(20261114L);
		for (int i = 0; i < 100_000; i++) {
			long base = 1 + (long) (rnd.nextDouble() * (EconomyManager.MAX_MONEY - 1));
			long sell = PriceMath.sellPrice(base, SELL);
			long buy = PriceMath.buyPrice(base, SELL, BUY);
			assertTrue(buy > sell, "arbitrage at base " + base + ": buy=" + buy + " sell=" + sell);
		}
	}

	@Test
	void percentageBoundariesStaySafe() {
		// sell=100: sell price equals the base, buy must still be strictly higher.
		for (long base : new long[]{1, 7, 99, 1000}) {
			long sell = PriceMath.sellPrice(base, 100);
			long buy = PriceMath.buyPrice(base, 100, 101);
			assertTrue(buy > sell, "base " + base);
		}
	}

	@Test
	void percentValidationRejectsUnsafeCombos() {
		assertTrue(PriceMath.validPercents(70, 130));
		assertTrue(PriceMath.validPercents(100, 101));
		assertFalse(PriceMath.validPercents(100, 100), "equal percents would allow break-even loops");
		assertFalse(PriceMath.validPercents(90, 80), "sell above buy is arbitrage");
		assertFalse(PriceMath.validPercents(0, 130));
		assertFalse(PriceMath.validPercents(101, 130));
		assertFalse(PriceMath.validPercents(70, 99));
	}

	@Test
	void zeroBaseHasNoPrice() {
		assertTrue(PriceMath.sellPrice(0, SELL) == 0);
		assertTrue(PriceMath.sellPrice(-5, SELL) == 0);
	}
}
