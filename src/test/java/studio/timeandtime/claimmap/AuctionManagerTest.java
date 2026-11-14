package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionManagerTest {
	@TempDir
	Path tmp;

	private ClaimMapConfig cfg;
	private EconomyManager econ;
	private MailboxManager mailbox;
	private AuctionManager auctions;
	private long clock = 1_700_000_000_000L;

	private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private final UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");
	private final UUID carol = UUID.fromString("33333333-3333-4333-8333-333333333333");

	private static final String ITEM = "{\"id\":\"minecraft:diamond\",\"count\":3}";

	@BeforeEach
	void setUp() {
		cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		cfg.startingBalance = 1000L;
		cfg.auctionMaxActivePerPlayer = 3;
		cfg.auctionFeePercent = 5;
		cfg.auctionListFeePercent = 0;
		cfg.auctionMinIncrementPercent = 5;
		cfg.auctionAntiSnipeSeconds = 60;
		cfg.auctionDefaultHours = 24;
		econ = new EconomyManager(tmp, cfg, () -> clock);
		econ.load();
		econ.ensureAccount(alice);
		econ.ensureAccount(bob);
		econ.ensureAccount(carol);
		mailbox = new MailboxManager(tmp.resolve("mailbox.json"), () -> clock, 100);
		mailbox.load();
		auctions = new AuctionManager(tmp, cfg, econ, mailbox, () -> clock);
		auctions.load();
		assertTrue(auctions.validateEscrow());
	}

	private String list(UUID seller, long start, Long buyout, int hours) {
		AuctionManager.CreatePlan p = new AuctionManager.CreatePlan(seller.toString(), "seller",
				ITEM, "minecraft:diamond", 3, "Diamond", start, buyout, hours);
		AuctionManager.CreateOutcome o = auctions.create(p);
		assertEquals(AuctionManager.Result.OK, o.result());
		assertNotNull(o.auctionId());
		return o.auctionId();
	}

	private long invariant() {
		return econ.sumPlayerBalances() + econ.sumEscrow() + econ.systemNet();
	}

	@Test
	void fullLifecycleMovesMoneyIntoEscrowAndDeliversToTheWinner() {
		econ.deposit(bob, 500, "test.give", "");
		econ.deposit(carol, 500, "test.give", "");
		long aliceBefore = econ.balance(alice);
		long bobBefore = econ.balance(bob);
		long carolBefore = econ.balance(carol);
		String id = list(alice, 100, null, 24);

		AuctionManager.BidOutcome b1 = auctions.bid(bob, "Bob", id, 100);
		assertEquals(AuctionManager.Result.OK, b1.result());
		assertEquals(100, econ.escrowBalance(id));
		assertEquals(bobBefore - 100, econ.balance(bob));

		AuctionManager.BidOutcome b2 = auctions.bid(carol, "Carol", id, 110);
		assertEquals(AuctionManager.Result.OK, b2.result());
		assertEquals(bobBefore, econ.balance(bob), "outbid money comes straight back");
		assertEquals(carolBefore - 110, econ.balance(carol));
		assertEquals(110, econ.escrowBalance(id), "only the highest bid stays in escrow");

		clock += 25L * 3_600_000L;
		auctions.closeExpired(clock);

		AuctionManager.Auction a = auctions.find(id);
		assertEquals(AuctionManager.SOLD, a.status);
		assertEquals(carol.toString(), a.winner);
		assertEquals(110L, a.finalPrice);
		assertTrue(a.itemDelivered);
		assertEquals(0L, econ.escrowBalance(id));
		assertEquals(aliceBefore + 110 - 5, econ.balance(alice), "seller receives the bid minus the 5% fee");
		assertTrue(mailbox.exists(carol, "mb_au_" + id + "_winner"));
		assertEquals(0L, invariant());
	}

	@Test
	void expireWithoutBidsReturnsTheItemToTheSeller() {
		String id = list(alice, 50, null, 1);
		clock += 2L * 3_600_000L;
		auctions.closeExpired(clock);

		AuctionManager.Auction a = auctions.find(id);
		assertEquals(AuctionManager.EXPIRED, a.status);
		assertTrue(a.itemDelivered);
		assertTrue(mailbox.exists(alice, "mb_au_" + id + "_return"));
		assertEquals(0L, econ.escrowBalance(id));
		assertEquals(0L, invariant());
	}

	@Test
	void rejectsInvalidBidsWithoutTakingMoney() {
		econ.deposit(bob, 500, "test.give", "");
		String id = list(alice, 100, 300L, 24);

		assertEquals(AuctionManager.Result.BID_TOO_LOW, auctions.bid(bob, "Bob", id, 99).result());
		assertEquals(0, econ.escrowBalance(id));
		assertEquals(AuctionManager.Result.OWN_AUCTION, auctions.bid(alice, "Alice", id, 100).result());
		assertEquals(AuctionManager.Result.NOT_FOUND, auctions.bid(bob, "Bob", "au_nope", 100).result());

		assertEquals(AuctionManager.Result.OK, auctions.bid(bob, "Bob", id, 100).result());
		assertEquals(AuctionManager.Result.ALREADY_HIGHEST, auctions.bid(bob, "Bob", id, 120).result());
		assertEquals(AuctionManager.Result.BID_TOO_LOW, auctions.bid(carol, "Carol", id, 104).result());
		assertEquals(AuctionManager.Result.USE_BUYOUT, auctions.bid(carol, "Carol", id, 300).result());
		assertEquals(0L, invariant());
	}

	@Test
	void buyoutRefundsThePreviousLeaderAndPaysTheSeller() {
		econ.deposit(bob, 500, "test.give", "");
		econ.deposit(carol, 500, "test.give", "");
		long aliceBefore = econ.balance(alice);
		long bobBefore = econ.balance(bob);
		long carolBefore = econ.balance(carol);
		String id = list(alice, 100, 300L, 24);

		assertEquals(AuctionManager.Result.OK, auctions.bid(bob, "Bob", id, 100).result());
		assertEquals(AuctionManager.Result.OK, auctions.buyout(carol, "Carol", id));
		assertEquals(bobBefore, econ.balance(bob), "previous leader is refunded in full");
		assertEquals(carolBefore - 300, econ.balance(carol));
		assertEquals(aliceBefore + 300 - 15, econ.balance(alice));
		assertEquals(0L, econ.escrowBalance(id));
		AuctionManager.Auction a = auctions.find(id);
		assertEquals(AuctionManager.SOLD, a.status);
		assertTrue(mailbox.exists(carol, "mb_au_" + id + "_winner"));
		assertEquals(0L, invariant());
	}

	@Test
	void cancelRulesAndRefund() {
		String id = list(alice, 100, null, 24);
		assertEquals(AuctionManager.Result.FORBIDDEN, auctions.cancel(bob, false, id));
		auctions.bid(bob, "Bob", id, 100);
		assertEquals(AuctionManager.Result.HAS_BIDS, auctions.cancel(alice, false, id));
		assertEquals(0L, invariant());

		String id2 = list(alice, 20, null, 24);
		assertEquals(AuctionManager.Result.OK, auctions.cancel(alice, false, id2));
		assertTrue(mailbox.exists(alice, "mb_au_" + id2 + "_return"));
		assertEquals(0L, econ.escrowBalance(id2));
	}

	@Test
	void aLateBidExtendsTheEndTimeAgainstSniping() {
		econ.deposit(bob, 500, "test.give", "");
		String id = list(alice, 100, null, 1);
		long endsAt = auctions.find(id).endsAt;
		clock = endsAt - 30_000L;

		assertEquals(AuctionManager.Result.OK, auctions.bid(bob, "Bob", id, 100).result());
		assertEquals(clock + 60_000L, auctions.find(id).endsAt);
		assertTrue(auctions.find(id).endsAt > endsAt);
	}

	@Test
	void settlingTwiceChangesNothing() {
		econ.deposit(bob, 500, "test.give", "");
		long aliceBefore = econ.balance(alice);
		String id = list(alice, 100, null, 24);
		auctions.bid(bob, "Bob", id, 100);

		clock += 25L * 3_600_000L;
		auctions.closeExpired(clock);
		long aliceAfter = econ.balance(alice);
		long bobAfter = econ.balance(bob);
		int mail = mailbox.count(bob);

		auctions.closeExpired(clock);
		auctions.closeExpired(clock);

		assertEquals(aliceAfter, econ.balance(alice));
		assertEquals(bobAfter, econ.balance(bob));
		assertEquals(mail, mailbox.count(bob), "the winner gets exactly one mail entry");
		assertEquals(aliceBefore + 100 - 5, aliceAfter);
		assertEquals(0L, invariant());
	}

	@Test
	void retriesDeliveryAfterACrashBetweenPayoutAndDelivery() {
		econ.deposit(bob, 500, "test.give", "");
		String id = list(alice, 100, null, 24);
		auctions.bid(bob, "Bob", id, 100);

		AuctionManager.Auction a = auctions.find(id);
		// Simulate: money already settled, process died before the item was mailed.
		econ.apply(List.of(
				new Move(Acct.escrow(id), Acct.player(alice), 95, "auction.sale:" + id, id),
				new Move(Acct.escrow(id), Acct.SYSTEM, 5, "auction.fee:" + id, id)), true);
		a.status = AuctionManager.SOLD;
		a.paidOut = true;
		a.itemDelivered = false;
		a.winner = bob.toString();
		a.finalPrice = 100L;
		long aliceNow = econ.balance(alice);
		long bobNow = econ.balance(bob);

		auctions.closeExpired(clock);
		auctions.closeExpired(clock);

		assertTrue(a.itemDelivered);
		assertTrue(mailbox.exists(bob, "mb_au_" + id + "_winner"));
		assertEquals(aliceNow, econ.balance(alice), "no second payout");
		assertEquals(bobNow, econ.balance(bob));
		assertEquals(0L, invariant());
	}

	@Test
	void listingFeeAndActiveLimit() {
		cfg.auctionListFeePercent = 2;
		econ.deposit(alice, 200, "test.give", "");
		long before = econ.balance(alice);
		String id = list(alice, 100, null, 24);
		assertEquals(before - 2, econ.balance(alice), "ceil(2% of 100)");
		assertEquals(0L, invariant());

		list(alice, 10, null, 24);
		list(alice, 10, null, 24);
		AuctionManager.CreatePlan p = new AuctionManager.CreatePlan(alice.toString(), "Alice",
				ITEM, "minecraft:diamond", 3, "Diamond", 10, null, 24);
		assertEquals(AuctionManager.Result.LIMIT_REACHED, auctions.create(p).result());
		assertEquals(3, auctions.all().size());
		assertNotNull(auctions.find(id));
	}

	@Test
	void damagedFileDisablesTheMarketAndIsMovedAside() throws Exception {
		Files.writeString(tmp.resolve("auctions.json"), "{ broken", StandardCharsets.UTF_8);
		AuctionManager broken = new AuctionManager(tmp, cfg, econ, mailbox, () -> clock);
		broken.load();

		assertTrue(broken.isDisabled());
		assertEquals(AuctionManager.Result.DISABLED, broken.create(new AuctionManager.CreatePlan(
				alice.toString(), "Alice", ITEM, "minecraft:diamond", 3, "Diamond", 10, null, 24)).result());
		try (var stream = Files.newDirectoryStream(tmp, "auctions.json.corrupt-*")) {
			assertTrue(stream.iterator().hasNext());
		}
		ShopManager shop = new ShopManager(cfg, econ, mailbox,
				new PriceService(tmp, cfg, () -> clock), () -> clock);
		shop.attachAuctions(broken);
		assertTrue(shop.isDisabled(), "a broken auction file also stops the shop (R6)");
	}

	@Test
	void escrowMismatchAtStartupDisablesTheMarket() throws Exception {
		String id = list(alice, 100, null, 24);
		econ.apply(List.of(new Move(Acct.player(alice), Acct.escrow(id), 5, "test", id)), true);

		AuctionManager reloaded = new AuctionManager(tmp, cfg, econ, mailbox, () -> clock);
		reloaded.load();
		assertTrue(reloaded.isDisabled());
	}

	@Test
	void createBoundariesAreRejectedBeforeAnyMoneyMoves() {
		long before = econ.balance(alice);
		for (long start : new long[]{0, -1, EconomyManager.MAX_MONEY + 1}) {
			AuctionManager.CreatePlan p = new AuctionManager.CreatePlan(alice.toString(), "Alice",
					ITEM, "minecraft:diamond", 3, "Diamond", start, null, 24);
			assertEquals(AuctionManager.Result.BAD_REQUEST, auctions.create(p).result(), "start=" + start);
		}
		AuctionManager.CreatePlan cheapBuyout = new AuctionManager.CreatePlan(alice.toString(), "Alice",
				ITEM, "minecraft:diamond", 3, "Diamond", 100, 100L, 24);
		assertEquals(AuctionManager.Result.BAD_REQUEST, auctions.create(cheapBuyout).result(),
				"buyout must be strictly above the start price");
		AuctionManager.CreatePlan tooLong = new AuctionManager.CreatePlan(alice.toString(), "Alice",
				ITEM, "minecraft:diamond", 3, "Diamond", 100, null, cfg.auctionMaxHours + 1);
		assertEquals(AuctionManager.Result.BAD_REQUEST, auctions.create(tooLong).result());
		assertEquals(0, auctions.all().size());
		assertEquals(before, econ.balance(alice));
	}

	@Test
	void concurrentBidsKeepOneLeaderAndExactEscrow() throws Exception {
		econ.deposit(bob, 100_000, "test.give", "");
		econ.deposit(carol, 100_000, "test.give", "");
		String id = list(alice, 100, null, 72);

		int threads = 24;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			final boolean isBob = i % 2 == 0;
			final int seed = i;
			futures.add(pool.submit(() -> {
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				long amount = 100L + seed * 10L;
				if (isBob) auctions.bid(bob, "Bob", id, amount);
				else auctions.bid(carol, "Carol", id, amount);
			}));
		}
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
		for (var f : futures) f.get();

		AuctionManager.Auction a = auctions.find(id);
		assertTrue(a.highBid > 0);
		assertEquals(a.highBid, econ.escrowBalance(id), "escrow holds exactly the highest bid");
		assertFalse(econ.isFrozen(bob));
		assertEquals(0L, invariant());
	}
}
