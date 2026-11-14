package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyManagerTest {
	@TempDir
	Path tmp;

	private ClaimMapConfig cfg;
	private EconomyManager econ;
	private long clock = 1_700_000_000_000L;

	private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private final UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");
	private final UUID carol = UUID.fromString("33333333-3333-4333-8333-333333333333");

	@BeforeEach
	void setUp() {
		cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		cfg.startingBalance = 1000L;
		econ = new EconomyManager(tmp, cfg, () -> clock);
		econ.load();
		econ.ensureAccount(alice);
		econ.ensureAccount(bob);
		econ.ensureAccount(carol);
	}

	private long ledgerLines() throws Exception {
		Path ledger = tmp.resolve("ledger.jsonl");
		return Files.exists(ledger) ? Files.readAllLines(ledger, StandardCharsets.UTF_8).size() : 0;
	}

	private long invariant() {
		return econ.sumPlayerBalances() + econ.sumEscrow() + econ.systemNet();
	}

	@Test
	void concurrentTransfersStayConsistentAndNonNegative() throws Exception {
		int threads = 100;
		ExecutorService pool = Executors.newFixedThreadPool(16);
		CountDownLatch start = new CountDownLatch(1);
		List<UUID> players = List.of(alice, bob, carol);
		AtomicLong okCount = new AtomicLong();
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			final int seed = i;
			futures.add(pool.submit(() -> {
				Random rnd = new Random(seed);
				try {
					start.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				for (int n = 0; n < 10; n++) {
					UUID from = players.get(rnd.nextInt(players.size()));
					UUID to = players.get(rnd.nextInt(players.size()));
					if (from.equals(to)) continue;
					TxResult r = econ.transfer(from, to, 1 + rnd.nextInt(50), "pay", "");
					if (r == TxResult.OK) okCount.incrementAndGet();
				}
			}));
		}
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers finished");
		for (var f : futures) f.get();

		assertTrue(okCount.get() > 0, "some transfers succeeded");
		assertEquals(0L, invariant(), "players + escrow + systemNet must stay 0");
		for (UUID u : List.of(alice, bob, carol)) {
			assertTrue(econ.balance(u) >= 0, "no negative balance");
		}
	}

	@Test
	void rejectsInvalidAmountsWithoutTouchingAnything() throws Exception {
		long before = econ.balance(alice);
		long lines = ledgerLines();

		assertEquals(TxResult.INVALID_AMOUNT, econ.transfer(alice, bob, 0, "pay", ""));
		assertEquals(TxResult.INVALID_AMOUNT, econ.transfer(alice, bob, -5, "pay", ""));
		assertEquals(TxResult.INVALID_AMOUNT, econ.transfer(alice, bob, Long.MAX_VALUE, "pay", ""));
		assertEquals(TxResult.INVALID_AMOUNT, econ.transfer(alice, bob, EconomyManager.MAX_MONEY + 1, "pay", ""));

		assertEquals(before, econ.balance(alice));
		assertEquals(lines, ledgerLines());
	}

	@Test
	void batchIsAllOrNothing() throws Exception {
		long a = econ.balance(alice);
		long b = econ.balance(bob);
		long lines = ledgerLines();

		TxResult r = econ.apply(List.of(
				new Move(Acct.player(alice), Acct.player(bob), 100, "pay", ""),
				new Move(Acct.player(alice), Acct.SYSTEM, 99_999, "admin.take", "")), false);

		assertEquals(TxResult.INSUFFICIENT, r);
		assertEquals(a, econ.balance(alice));
		assertEquals(b, econ.balance(bob));
		assertEquals(lines, ledgerLines(), "no ledger row may be written for a failed batch");
	}

	@Test
	void replayFromAnOlderSnapshotRestoresTheLedgerTruth() throws Exception {
		econ.transfer(alice, bob, 250, "pay", "");
		Path snapshot = tmp.resolve("economy.json");
		byte[] older = Files.readAllBytes(snapshot);   // lastSeq before the next movements

		econ.transfer(bob, carol, 75, "pay", "");
		econ.transfer(carol, alice, 25, "pay", "");
		long expectedAlice = econ.balance(alice);
		long expectedBob = econ.balance(bob);
		long expectedCarol = econ.balance(carol);

		// Simulate a crash between the ledger append and the snapshot write.
		Files.write(snapshot, older);

		EconomyManager reloaded = new EconomyManager(tmp, cfg, () -> clock);
		reloaded.load();

		assertFalse(reloaded.isDisabled());
		assertEquals(expectedAlice, reloaded.balance(alice));
		assertEquals(expectedBob, reloaded.balance(bob));
		assertEquals(expectedCarol, reloaded.balance(carol));
		assertEquals(0L, reloaded.sumPlayerBalances() + reloaded.sumEscrow() + reloaded.systemNet());
	}

	@Test
	void skipsATruncatedLastLedgerLine() throws Exception {
		econ.transfer(alice, bob, 40, "pay", "");
		long expectedAlice = econ.balance(alice);
		long expectedBob = econ.balance(bob);

		Path ledger = tmp.resolve("ledger.jsonl");
		String torn = "{\"seq\":99999,\"id\":\"tx_99999\",\"batch\":\"b\",\"t\":1,\"from\":\"P:"
				+ alice + "\",\"to\":\"P:" + bob + "\",\"amount\":50,\"reas";
		Files.writeString(ledger, torn, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

		EconomyManager reloaded = new EconomyManager(tmp, cfg, () -> clock);
		reloaded.load();

		assertFalse(reloaded.isDisabled(), "a torn tail must not disable the market");
		assertEquals(expectedAlice, reloaded.balance(alice));
		assertEquals(expectedBob, reloaded.balance(bob));
	}

	@Test
	void damagedSnapshotDisablesTheMarketWithoutRecreatingIt() throws Exception {
		Path snapshot = tmp.resolve("economy.json");
		Files.writeString(snapshot, "{ not json at all", StandardCharsets.UTF_8);
		long lines = ledgerLines();

		EconomyManager reloaded = new EconomyManager(tmp, cfg, () -> clock);
		reloaded.load();

		assertTrue(reloaded.isDisabled());
		assertEquals(TxResult.IO_ERROR, reloaded.apply(
				List.of(new Move(Acct.SYSTEM, Acct.player(alice), 1, "admin.give", "")), true));
		assertFalse(Files.exists(snapshot), "must not create an empty snapshot over the damaged one");
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmp, "economy.json.corrupt-*")) {
			assertTrue(stream.iterator().hasNext(), "damaged file moved aside");
		}
		assertEquals(lines, ledgerLines(), "ledger untouched");
	}

	@Test
	void damagedMiddleLedgerRowDisablesTheMarket() throws Exception {
		econ.transfer(alice, bob, 10, "pay", "");
		Path ledger = tmp.resolve("ledger.jsonl");
		List<String> lines = new ArrayList<>(Files.readAllLines(ledger, StandardCharsets.UTF_8));
		lines.add(0, "this is not json");
		Files.write(ledger, lines, StandardCharsets.UTF_8);

		EconomyManager reloaded = new EconomyManager(tmp, cfg, () -> clock);
		reloaded.load();

		assertTrue(reloaded.isDisabled());
		assertFalse(Files.exists(ledger));
	}

	@Test
	void ensureAccountGrantsTheStartingBalanceOnlyOnce() {
		long before = econ.balance(alice);
		assertFalse(econ.ensureAccount(alice), "existing account is not recreated");
		assertEquals(before, econ.balance(alice));

		UUID dave = UUID.fromString("44444444-4444-4444-8444-444444444444");
		assertTrue(econ.ensureAccount(dave));
		assertEquals(1000L, econ.balance(dave));
		assertFalse(econ.ensureAccount(dave));
		assertEquals(1000L, econ.balance(dave));
		assertEquals(0L, invariant());
	}

	@Test
	void frozenAccountsCannotSendOrReceiveButSystemMayRefund() {
		econ.setFrozen(bob, true);

		assertEquals(TxResult.FROZEN, econ.transfer(alice, bob, 10, "pay", ""));
		assertEquals(TxResult.FROZEN, econ.transfer(bob, alice, 10, "pay", ""));
		// A system credit is not a player-to-player transfer, so it still works.
		assertEquals(TxResult.OK, econ.deposit(bob, 10, "admin.give", ""));

		// Escrow refunds are system actions and must still reach a frozen player.
		econ.apply(List.of(new Move(Acct.player(alice), Acct.escrow("au_1"), 100, "auction.bid:au_1", "")), true);
		long bobBefore = econ.balance(bob);
		assertEquals(TxResult.OK, econ.apply(
				List.of(new Move(Acct.escrow("au_1"), Acct.player(bob), 100, "auction.refund:au_1", "")), false));
		assertEquals(bobBefore + 100, econ.balance(bob));
		assertEquals(0L, econ.escrowBalance("au_1"));

		econ.setFrozen(bob, false);
		assertEquals(TxResult.OK, econ.transfer(alice, bob, 10, "pay", ""));
	}

	@Test
	void historyKeepsNewestFirstPerPlayer() {
		for (int i = 0; i < 5; i++) {
			clock++;
			econ.transfer(alice, bob, 1, "pay", "");
		}
		List<EconomyManager.TxView> h = econ.history(alice, 50);
		// 5 outgoing transfers + the signup grant from the system
		assertEquals(6, h.size());
		assertEquals("out", h.get(0).dir());
		assertTrue(h.get(0).seq() > h.get(4).seq());
		assertEquals(bob, h.get(0).other().uuid(), "outgoing row points at the receiver");
		assertNotEquals(0L, h.get(0).t());
	}

	@Test
	void snapshotAndLedgerAgreeAfterManyBatches() throws Exception {
		for (int i = 0; i < 20; i++) {
			clock++;
			econ.transfer(alice, bob, 3, "pay", "");
			econ.transfer(bob, carol, 1, "pay", "");
		}
		long lines = ledgerLines();
		assertTrue(lines > 0);

		EconomyManager reloaded = new EconomyManager(tmp, cfg, () -> clock);
		reloaded.load();
		assertEquals(econ.balance(alice), reloaded.balance(alice));
		assertEquals(econ.balance(bob), reloaded.balance(bob));
		assertEquals(econ.balance(carol), reloaded.balance(carol));
		assertEquals(0L, reloaded.sumPlayerBalances() + reloaded.sumEscrow() + reloaded.systemNet());
	}

	@Test
	void backupCreatesAFolderAndKeepsTheFiles() throws Exception {
		econ.transfer(alice, bob, 5, "pay", "");
		econ.backupNow();

		Path backups = tmp.resolve("backups");
		assertTrue(Files.isDirectory(backups));
		try (DirectoryStream<Path> sets = Files.newDirectoryStream(backups)) {
			Path set = sets.iterator().next();
			assertTrue(Files.exists(set.resolve("economy.json")));
			assertTrue(Files.exists(set.resolve("ledger.jsonl")));
		}
	}
}
