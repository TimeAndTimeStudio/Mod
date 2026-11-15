package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LandPriceServiceTest {

	@Test
	void testFloorPriceAndIncrease(@TempDir Path tmp) {
		Path file = tmp.resolve("land-price.json");
		LandPriceService service = new LandPriceService(file, 0);

		assertEquals(LandPriceService.MIN_PRICE, service.currentPrice());
		assertEquals(100_000L, service.currentPrice());

		// Purchase increments price
		service.onCellPurchased();
		assertEquals(130_000L, service.currentPrice());

		service.onCellPurchased();
		assertEquals(160_000L, service.currentPrice());
	}

	@Test
	void testMaxPriceCeiling(@TempDir Path tmp) {
		Path file = tmp.resolve("land-price.json");
		LandPriceService service = new LandPriceService(file, 0);

		for (int i = 0; i < 50; i++) {
			service.onCellPurchased();
		}
		assertEquals(LandPriceService.MAX_PRICE, service.currentPrice());
		assertEquals(1_000_000L, service.currentPrice());
	}

	@Test
	void testPersistenceAndDecayFloor(@TempDir Path tmp) {
		Path file = tmp.resolve("land-price.json");
		LandPriceService service = new LandPriceService(file, 0);

		service.onCellPurchased();
		service.onCellPurchased();
		long p1 = service.currentPrice();
		assertTrue(p1 > LandPriceService.MIN_PRICE);

		// Reload from file
		LandPriceService reloaded = new LandPriceService(file, 0);
		assertEquals(p1, reloaded.currentPrice());
	}
}
