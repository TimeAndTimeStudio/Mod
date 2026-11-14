package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeManagerTest {
	private static final long WEEK = 7L * 24 * 60 * 60_000L;
	private static final String DIAMOND = "{\"id\":\"minecraft:diamond\",\"count\":4}";
	private static final String IRON = "{\"id\":\"minecraft:iron_ingot\",\"count\":32}";

	@TempDir
	Path tmp;

	private final String alice = "11111111-1111-4111-8111-111111111111";
	private final String bob = "22222222-2222-4222-8222-222222222222";
	private long clock = 1_000_000L;
	private TradeManager trades;
	private MailboxManager mailbox;

	@BeforeEach
	void setUp() {
		trades = new TradeManager(tmp, () -> clock);
		trades.load();
		mailbox = new MailboxManager(tmp.resolve("mailbox.json"), () -> clock, 5);
		mailbox.load();
	}

	private String createAliceOffer() {
		assertEquals(TradeManager.Result.OK, trades.create(alice, "Alice", DIAMOND, "minecraft:diamond", 4,
				"เพชร", "minecraft:iron_ingot", 32, "เหล็ก"));
		return trades.active().get(trades.active().size() - 1).id;
	}

	private TradeManager.Result bobAccepts(String id) {
		return trades.accept(id, bob, "Bob", IRON, "minecraft:iron_ingot", 32, "เหล็ก", mailbox);
	}

	@Test
	void acceptDeliversBothStacksExactlyOnce() {
		String id = createAliceOffer();
		assertEquals(TradeManager.Result.OK, bobAccepts(id));
		assertEquals(1, mailbox.count(UUID.fromString(bob)));
		assertEquals(1, mailbox.count(UUID.fromString(alice)));
		assertEquals("minecraft:diamond", mailbox.list(UUID.fromString(bob)).get(0).itemId());
		assertEquals("minecraft:iron_ingot", mailbox.list(UUID.fromString(alice)).get(0).itemId());

		assertEquals(TradeManager.Result.CLOSED, bobAccepts(id), "a second accept must not deliver again");
		assertEquals(1, mailbox.count(UUID.fromString(bob)));
		assertEquals(TradeManager.Result.CLOSED, trades.cancel(id, alice, false, mailbox),
				"a completed offer cannot be cancelled for a refund");
		assertEquals(1, mailbox.count(UUID.fromString(alice)));
	}

	@Test
	void ownerCannotAcceptOwnOffer() {
		String id = createAliceOffer();
		assertEquals(TradeManager.Result.OWN_OFFER,
				trades.accept(id, alice, "Alice", IRON, "minecraft:iron_ingot", 32, "เหล็ก", mailbox));
	}

	@Test
	void onlyOwnerOrAdminCanCancel() {
		String id = createAliceOffer();
		assertEquals(TradeManager.Result.BAD_REQUEST, trades.cancel(id, bob, false, mailbox));
		assertEquals(TradeManager.Result.OK, trades.cancel(id, bob, true, mailbox));
		assertEquals(1, mailbox.count(UUID.fromString(alice)), "escrowed stack returns to the owner");
		assertEquals(TradeManager.Result.CLOSED, trades.cancel(id, alice, false, mailbox));
		assertEquals(1, mailbox.count(UUID.fromString(alice)));
	}

	@Test
	void expiredOfferCannotBeAcceptedButCanBeRefunded() {
		String id = createAliceOffer();
		clock += WEEK + 1;
		assertTrue(trades.active().isEmpty());
		assertEquals(TradeManager.Result.CLOSED, bobAccepts(id));
		assertEquals(TradeManager.Result.OK, trades.cancel(id, alice, false, mailbox));
		assertEquals(1, mailbox.count(UUID.fromString(alice)));
	}

	@Test
	void fullMailboxBlocksAcceptWithoutClosingTheOffer() {
		String id = createAliceOffer();
		UUID bobId = UUID.fromString(bob);
		for (int i = 0; i < 5; i++) {
			mailbox.add(bobId, new MailboxManager.MailEntry("filler_" + i, DIAMOND, "minecraft:diamond", 1,
					"เพชร", "shop", clock));
		}
		assertEquals(TradeManager.Result.MAILBOX_FULL, bobAccepts(id));
		assertEquals(0, mailbox.count(UUID.fromString(alice)));
		assertEquals(1, trades.active().size(), "offer stays open for a later accept");
	}

	@Test
	void activeOfferLimitPerPlayer() {
		for (int i = 0; i < 10; i++) createAliceOffer();
		assertEquals(TradeManager.Result.LIMIT_REACHED, trades.create(alice, "Alice", DIAMOND,
				"minecraft:diamond", 4, "เพชร", "minecraft:iron_ingot", 32, "เหล็ก"));
	}

	@Test
	void rejectsInvalidInput() {
		assertEquals(TradeManager.Result.BAD_REQUEST, trades.create(alice, "Alice", DIAMOND,
				"minecraft:diamond", 0, "เพชร", "minecraft:iron_ingot", 32, "เหล็ก"));
		assertEquals(TradeManager.Result.BAD_REQUEST, trades.create(alice, "Alice", DIAMOND,
				"minecraft:diamond", 4, "เพชร", null, 32, "เหล็ก"));
		assertEquals(TradeManager.Result.NOT_FOUND, bobAccepts("tr_missing"));
	}

	@Test
	void offersSurviveRestart() {
		String id = createAliceOffer();
		TradeManager reloaded = new TradeManager(tmp, () -> clock);
		reloaded.load();
		TradeManager.Offer o = reloaded.find(id);
		assertNotNull(o);
		assertEquals(TradeManager.ACTIVE, o.status);
		assertEquals(4, o.giveCount);
		assertEquals("minecraft:iron_ingot", o.wantItemId);
	}
}
