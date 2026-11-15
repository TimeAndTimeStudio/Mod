package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceServiceTest {
	@TempDir
	Path tmp;

	private final long[] clock = {1_000_000L};
	private ClaimMapConfig cfg;
	private PriceService prices;

	@BeforeEach
	void setUp() {
		cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		prices = new PriceService(tmp, cfg, () -> clock[0]);
	}

	@Test
	void parserNormalizesAndSkipsUnusableValues() {
		String body = """
				{"prices":{
				  "diamond":10,
				  "minecraft:emerald":2.6,
				  "zero":0,
				  "negative":-5,
				  "huge":1e18,
				  "text":"abc",
				  "stone":3
				}}""";
		Optional<Map<String, Long>> parsed = PriceService.parsePayload(body);
		assertTrue(parsed.isPresent());
		Map<String, Long> p = parsed.get();
		assertEquals(10L, p.get("minecraft:diamond"));
		assertEquals(3L, p.get("minecraft:emerald"), "2.6 rounds HALF_UP to 3");
		assertEquals(3L, p.get("minecraft:stone"));
		assertFalse(p.containsKey("minecraft:zero"));
		assertFalse(p.containsKey("minecraft:negative"));
		assertFalse(p.containsKey("minecraft:huge"));
		assertFalse(p.containsKey("minecraft:text"));
	}

	@Test
	void parserAcceptsTheWrappedJsonBinShape() {
		assertTrue(PriceService.parsePayload("{\"record\":{\"prices\":{\"diamond\":7}}}").isPresent());
	}

	@Test
	void parserRejectsBrokenOrEmptyPayloads() {
		assertTrue(PriceService.parsePayload(null).isEmpty());
		assertTrue(PriceService.parsePayload("").isEmpty());
		assertTrue(PriceService.parsePayload("not json").isEmpty());
		assertTrue(PriceService.parsePayload("[]").isEmpty());
		assertTrue(PriceService.parsePayload("{\"prices\":{}}").isEmpty());
		assertTrue(PriceService.parsePayload("{\"prices\":{\"x\":0}}").isEmpty());
	}

	@Test
	void fetchedTableIsWrittenAndEmptyFetchesDoNotOverwriteIt() throws Exception {
		assertTrue(prices.applyFetched(Map.of("diamond", 10L)));
		Path cache = tmp.resolve("prices-cache.json");
		assertTrue(Files.exists(cache));
		String first = Files.readString(cache);
		assertTrue(first.contains("minecraft:diamond"));

		assertFalse(prices.applyFetched(Map.of()), "empty payload must be rejected");
		assertTrue(prices.applyFetched(Map.of("diamond", 0L)) == false, "all-invalid payload rejected");
		assertEquals(first, Files.readString(cache), "cache must not be overwritten by a failed refresh");
		assertEquals(10L, prices.base("diamond").getAsLong());
	}

	@Test
	void cacheIsReloadedOnStartup() throws Exception {
		assertTrue(prices.applyFetched(Map.of("diamond", 10L, "emerald", 4L)));

		PriceService reloaded = new PriceService(tmp, cfg, () -> clock[0]);
		reloaded.loadCache();
		assertEquals(10L, reloaded.base("diamond").getAsLong());
		assertEquals(4L, reloaded.base("emerald").getAsLong());
	}

	@Test
	void cacheUsesTheSameRoundingAsFreshFetches() throws Exception {
		Files.writeString(tmp.resolve("prices-cache.json"),
				"{\"version\":1,\"fetchedAt\":1000,\"prices\":{\"emerald\":2.6,\"diamond\":2.4}}");
		PriceService reloaded = new PriceService(tmp, cfg, () -> clock[0]);
		reloaded.loadCache();
		assertEquals(3L, reloaded.base("emerald").getAsLong(), "2.6 rounds up");
		assertEquals(2L, reloaded.base("diamond").getAsLong(), "2.4 rounds down");
	}

	@Test
	void overrideBeatsTheWebPriceAndPersists() {
		prices.applyFetched(Map.of("diamond", 10L));
		prices.setOverride("diamond", 100L);
		assertEquals(100L, prices.base("diamond").getAsLong());
		assertEquals(PriceMath.buyPrice(100, cfg.sellPercent, cfg.buyPercent), prices.buyPrice("diamond").getAsLong());
		assertEquals(PriceMath.sellPrice(100, cfg.sellPercent), prices.sellPrice("diamond").getAsLong());

		PriceService reloaded = new PriceService(tmp, cfg, () -> clock[0]);
		reloaded.loadCache();
		assertEquals(100L, reloaded.base("diamond").getAsLong());

		prices.setOverride("diamond", null);
		assertEquals(10L, prices.base("diamond").getAsLong(), "removing the override falls back to the web price");
	}

	@Test
	void blockedItemsHaveNoPriceAndAreHiddenFromTheSnapshot() {
		prices.applyFetched(Map.of("diamond", 10L, "bedrock", 5L));
		assertTrue(prices.isBlocked("bedrock"), "config default blocks bedrock");
		assertTrue(prices.buyPrice("bedrock").isEmpty());
		assertTrue(prices.sellPrice("bedrock").isEmpty());
		assertFalse(prices.snapshot().containsKey("minecraft:bedrock"));

		prices.setBlocked("diamond", true);
		assertTrue(prices.isBlocked("minecraft:diamond"));
		assertTrue(prices.buyPrice("diamond").isEmpty());
		assertFalse(prices.snapshot().containsKey("minecraft:diamond"));

		PriceService reloaded = new PriceService(tmp, cfg, () -> clock[0]);
		reloaded.loadCache();
		assertTrue(reloaded.isBlocked("diamond"), "blocked list persisted to price-overrides.json");

		prices.setBlocked("diamond", false);
		assertFalse(prices.isBlocked("diamond"));
		assertNotNull(prices.snapshot().get("minecraft:diamond"));
	}

	@Test
	void snapshotPricesAlwaysAllowNoArbitrage() {
		prices.applyFetched(Map.of("a", 1L, "b", 2L, "c", 1000L));
		for (Map.Entry<String, PriceService.PricePair> e : prices.snapshot().entrySet()) {
			assertTrue(e.getValue().buy() > e.getValue().sell(), e.getKey());
			assertTrue(e.getValue().sell() >= 1, e.getKey());
		}
	}

	@Test
	void staleFlagsFollowFailuresAndTime() {
		prices.applyFetched(Map.of("diamond", 10L));
		assertFalse(prices.usingStaleCache());
		assertFalse(prices.staleForApi());
		assertEquals(clock[0], prices.lastSuccessMs());

		prices.markFailure("test");
		assertTrue(prices.usingStaleCache(), "a failed attempt marks the table stale");
		assertFalse(prices.staleForApi(), "but the UI warning waits for an hour");

		clock[0] += 60L * 60_000L + 1;
		assertTrue(prices.staleForApi());
	}

	@Test
	void unknownItemsHaveNoPrice() {
		assertTrue(prices.base("does_not_exist").isEmpty());
		assertTrue(prices.buyPrice("does_not_exist").isEmpty());
	}

	@Test
	void testDynamicPriceRecovery() {
		prices.applyFetched(Map.of("minecraft:diamond", 100L));
		assertEquals(1.0, prices.multiplier("minecraft:diamond"));

		// Sell items -> price multiplier drops
		prices.recordSale("minecraft:diamond", 100);
		double dipped = prices.multiplier("minecraft:diamond");
		assertTrue(dipped < 1.0, "Multiplier should drop after sale");
		assertEquals(100, prices.getSoldUnits("minecraft:diamond"));

		// Advance clock by 10 minutes -> price recovers partially
		clock[0] += 10 * 60_000L;
		double recovered1 = prices.multiplier("minecraft:diamond");
		assertTrue(recovered1 > dipped, "Multiplier should increase as time passes");
		assertTrue(prices.getSoldUnits("minecraft:diamond") < 100);

		// Advance clock by 2 hours -> price fully recovers to 1.0
		clock[0] += 120 * 60_000L;
		assertEquals(1.0, prices.multiplier("minecraft:diamond"), "Should fully recover to 1.0");
		assertEquals(0, prices.getSoldUnits("minecraft:diamond"));
	}

	@Test
	void testDecimalPriceDropsForCheapItems() {
		prices.applyFetched(Map.of("minecraft:dirt", 1L));
		assertEquals(1.00, prices.sellPriceDouble("minecraft:dirt"));

		// Sell 82 dirt
		prices.recordSale("minecraft:dirt", 82);
		double sellPrice = prices.sellPriceDouble("minecraft:dirt");
		assertTrue(sellPrice < 1.00, "Price should drop below 1.0 into decimals: " + sellPrice);
		assertTrue(sellPrice > 0.80, "Price should be around 0.89: " + sellPrice);

		var hist = prices.getHistory("minecraft:dirt");
		assertFalse(hist.isEmpty());
		assertTrue(hist.get(hist.size() - 1).price() < 1.0);
	}
}
