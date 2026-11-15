package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMarketTest {
	@TempDir
	Path tmp;

	private ClaimMapConfig cfg;
	private EconomyManager econ;
	private MailboxManager mailbox;
	private PlayerMarket market;
	private AuctionManager auctions;
	private ConsignmentManager consignments;
	private long clock = 1_700_000_000_000L;

	private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private final UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private static final String ITEM = "{\"id\":\"minecraft:diamond\",\"count\":4}";

	@BeforeEach
	void setUp() {
		cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		cfg.startingBalance = 1000L;
		cfg.marketMaxActivePerPlayer = 2;
		cfg.auctionFeePercent = 5;
		cfg.auctionDefaultHours = 24;
		econ = new EconomyManager(tmp, cfg, () -> clock);
		econ.load();
		econ.ensureAccount(alice);
		econ.ensureAccount(bob);
		mailbox = new MailboxManager(tmp.resolve("mailbox.json"), () -> clock, 100);
		mailbox.load();
		market = new PlayerMarket(tmp, cfg, econ, mailbox, () -> clock);
		market.load();
		auctions = new AuctionManager(tmp, cfg, econ, mailbox, () -> clock);
		auctions.load();
		consignments = new ConsignmentManager(tmp, () -> clock);
		consignments.load();
		auctions.attachConsignments(consignments);
	}

	private String list(UUID seller, long price) {
		PlayerMarket.CreateOutcome o = market.create(new PlayerMarket.CreatePlan(
				seller.toString(), "seller", ITEM, "minecraft:diamond", 4, "Diamond", price));
		assertEquals(PlayerMarket.Result.OK, o.result());
		assertNotNull(o.listingId());
		return o.listingId();
	}

	@Test
	void buyingMovesMoneyAndDeliversTheItem() {
		long aliceBefore = econ.balance(alice);
		long bobBefore = econ.balance(bob);
		String id = list(alice, 250);

		assertEquals(PlayerMarket.Result.OK, market.buy(bob, "Bob", id));
		assertEquals(bobBefore - 250, econ.balance(bob));
		assertEquals(aliceBefore + 250, econ.balance(alice));
		assertEquals(1, mailbox.count(bob));
		assertEquals(PlayerMarket.SOLD, market.find(id).status);
		assertTrue(market.find(id).itemDelivered);
	}

	@Test
	void ownerCannotBuyAndCancelReturnsTheItem() {
		String id = list(alice, 100);
		assertEquals(PlayerMarket.Result.OWN_LISTING, market.buy(alice, "Alice", id));
		assertEquals(PlayerMarket.Result.OK, market.cancel(alice, false, id));
		assertEquals(1, mailbox.count(alice));
		assertEquals(PlayerMarket.CANCELLED, market.find(id).status);
	}

	@Test
	void activeListingLimitIsEnforced() {
		list(alice, 10);
		list(alice, 20);
		PlayerMarket.CreateOutcome third = market.create(new PlayerMarket.CreatePlan(
				alice.toString(), "Alice", ITEM, "minecraft:diamond", 1, "Diamond", 30));
		assertEquals(PlayerMarket.Result.LIMIT_REACHED, third.result());
	}

	@Test
	void serverAuctionPaysTheServerAndCancelledItemReturnsToConsignments() {
		ConsignmentManager.Entry entry = consignments.add(new ConsignmentManager.Plan(
				alice.toString(), "Alice", ITEM, "minecraft:diamond", 4, "Diamond"));
		assertNotNull(entry);
		assertEquals(1, consignments.pending().size());

		ConsignmentManager.Entry taken = consignments.take(entry.id);
		assertNotNull(taken);
		AuctionManager.CreateOutcome created = auctions.createServerAuction(
				taken.itemJson, taken.itemId, taken.count, taken.label, 100, 300L, 24);
		assertEquals(AuctionManager.Result.OK, created.result());
		assertTrue(auctions.find(created.auctionId()).serverOwned);
		assertEquals(0, consignments.pending().size());

		long aliceBefore = econ.balance(alice);
		long bobBefore = econ.balance(bob);
		assertEquals(AuctionManager.Result.OK, auctions.buyout(bob, "Bob", created.auctionId()));
		assertEquals(bobBefore - 300, econ.balance(bob));
		assertEquals(aliceBefore, econ.balance(alice), "server auctions do not pay a player seller");
		assertEquals(1, mailbox.count(bob));

		// A second server auction cancelled by an admin returns the item to the pool.
		ConsignmentManager.Entry second = consignments.add(new ConsignmentManager.Plan(
				alice.toString(), "Alice", ITEM, "minecraft:diamond", 4, "Diamond"));
		AuctionManager.CreateOutcome again = auctions.createServerAuction(
				second.itemJson, second.itemId, second.count, second.label, 100, null, 24);
		assertEquals(AuctionManager.Result.OK, again.result());
		assertEquals(AuctionManager.Result.OK, auctions.adminCancel(again.auctionId()));
		assertFalse(consignments.pending().isEmpty());
	}
}
