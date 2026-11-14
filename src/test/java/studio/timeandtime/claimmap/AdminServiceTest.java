package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServiceTest {
	@TempDir
	Path tmp;

	private ClaimMapConfig cfg;
	private EconomyManager econ;
	private AccountManager accounts;
	private LinkManager links;
	private PriceService prices;
	private MailboxManager mailbox;
	private AuctionManager auctions;
	private AuditLog audit;
	private AdminService admin;
	private long clock = 1_700_000_000_000L;

	private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private final UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private static final String ITEM = "{\"id\":\"minecraft:diamond\",\"count\":1}";

	@BeforeEach
	void setUp() {
		cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		cfg.startingBalance = 1000L;
		cfg.admins = "Alice";
		cfg.players = "Alice,Bob";
		cfg.auctionFeePercent = 5;
		cfg.auctionAntiSnipeSeconds = 60;
		econ = new EconomyManager(tmp, cfg, () -> clock);
		econ.load();
		econ.ensureAccount(alice);
		econ.ensureAccount(bob);

		accounts = new AccountManager(cfg);
		accounts.setPasswordHash(alice.toString(), "Alice", "fake-modern-hash");
		accounts.setPasswordHash(bob.toString(), "Bob", "fake-modern-hash");

		links = new LinkManager(cfg);
		links.load();
		prices = new PriceService(tmp, cfg, () -> clock);
		prices.loadCache();
		mailbox = new MailboxManager(tmp.resolve("mailbox.json"), () -> clock, 100);
		mailbox.load();
		auctions = new AuctionManager(tmp, cfg, econ, mailbox, () -> clock);
		auctions.load();
		ShopManager shop = new ShopManager(cfg, econ, mailbox, prices, () -> clock);
		shop.attachAuctions(auctions);
		audit = new AuditLog(tmp, () -> clock);
		admin = new AdminService(cfg, econ, accounts, links, prices, auctions, mailbox, shop, audit, u -> false);
	}

	private long invariant() {
		return econ.sumPlayerBalances() + econ.sumEscrow() + econ.systemNet();
	}

	@Test
	void giveTakeSetKeepTheInvariantAndWriteAudit() throws Exception {
		AdminService.MoneyOutcome give = admin.money("Alice", "127.0.0.1", "Bob", "give", 250, "ทดสอบให้เงิน");
		assertEquals(AdminService.Result.OK, give.result());
		assertEquals(1250, give.balance());

		AdminService.MoneyOutcome take = admin.money("Alice", "127.0.0.1", "Bob", "take", 50, "ทดสอบหักเงิน");
		assertEquals(AdminService.Result.OK, take.result());
		assertEquals(1200, take.balance());

		AdminService.MoneyOutcome set = admin.money("Alice", "127.0.0.1", "Bob", "set", 700, "ทดสอบตั้งยอด");
		assertEquals(AdminService.Result.OK, set.result());
		assertEquals(700, set.balance());

		assertEquals(0L, invariant());
		List<String> lines = audit.tail(200);
		assertEquals(3, lines.size());
		assertTrue(lines.get(0).contains("money.give"));
		assertTrue(lines.get(0).contains("\"actor\":\"Alice\""));
		assertTrue(lines.get(0).contains("\"ok\":true"));
		assertFalse(lines.get(0).contains("fake-modern-hash"));
		assertEquals(3, Files.readAllLines(tmp.resolve("audit.jsonl"), StandardCharsets.UTF_8).size());
	}

	@Test
	void takeOverBalanceIsRejectedAndAudited() {
		long before = econ.balance(bob);
		AdminService.MoneyOutcome o = admin.money("Alice", "1.2.3.4", "Bob", "take", 10_000, "หักเกินยอด");
		assertEquals(AdminService.Result.INSUFFICIENT, o.result());
		assertEquals(before, econ.balance(bob));
		assertEquals(0L, invariant());

		List<String> lines = audit.tail(10);
		assertEquals(1, lines.size());
		assertTrue(lines.get(0).contains("\"ok\":false"));
		assertTrue(lines.get(0).contains("1.2.3.4"));
	}

	@Test
	void missingOrShortReasonIsRejectedBeforeTouchingMoney() {
		long before = econ.balance(bob);
		assertEquals(AdminService.Result.BAD_REQUEST,
				admin.money("Alice", null, "Bob", "give", 10, "ok").result());
		assertEquals(AdminService.Result.BAD_REQUEST,
				admin.money("Alice", null, "Bob", "give", 10, null).result());
		assertEquals(before, econ.balance(bob));
		assertEquals(2, audit.tail(10).size(), "rejections are audited too");
	}

	@Test
	void unknownPlayerAndBadOperationAreRejected() {
		assertEquals(AdminService.Result.NO_ACCOUNT,
				admin.money("Alice", null, "Nobody", "give", 10, "ไม่มีบัญชี").result());
		assertEquals(AdminService.Result.BAD_REQUEST,
				admin.money("Alice", null, "Bob", "burn", 10, "คำสั่งผิด").result());
		assertEquals(0L, invariant());
	}

	@Test
	void frozenPlayerCannotTradeOrBidButCanStillLogin() {
		assertEquals(AdminService.Result.OK, admin.freeze("Alice", null, "Bob", true, "ทดสอบระงับ"));
		assertTrue(econ.isFrozen(bob));

		String id = auctions.create(new AuctionManager.CreatePlan(alice.toString(), "Alice",
				ITEM, "minecraft:diamond", 1, "Diamond", 50, null, 24)).auctionId();
		assertEquals(AuctionManager.Result.FROZEN, auctions.bid(bob, "Bob", id, 50).result());

		assertEquals(AdminService.Result.OK, admin.freeze("Alice", null, "Bob", false, "คืนสิทธิ์"));
		assertFalse(econ.isFrozen(bob));
		assertEquals(AuctionManager.Result.OK, auctions.bid(bob, "Bob", id, 50).result());
		assertEquals(0L, invariant());
	}

	@Test
	void adminCancelRefundsTheBidderAndReturnsTheItemOnce() {
		econ.deposit(bob, 500, "test.give", "");
		long bobBefore = econ.balance(bob);
		String id = auctions.create(new AuctionManager.CreatePlan(alice.toString(), "Alice",
				ITEM, "minecraft:diamond", 1, "Diamond", 100, null, 24)).auctionId();
		auctions.bid(bob, "Bob", id, 100);
		assertEquals(100, econ.escrowBalance(id));

		assertEquals(AdminService.Result.OK, admin.cancelAuction("Alice", null, id, "ลบรายการทดสอบ"));
		assertEquals(bobBefore, econ.balance(bob), "bid comes back in full");
		assertEquals(0L, econ.escrowBalance(id));
		assertTrue(mailbox.exists(alice, "mb_au_" + id + "_return"));
		assertEquals(AuctionManager.CANCELLED, auctions.find(id).status);
		assertEquals(0L, invariant());

		long bobAfter = econ.balance(bob);
		assertEquals(AdminService.Result.BAD_REQUEST, admin.cancelAuction("Alice", null, id, "กดซ้ำ"));
		assertEquals(bobAfter, econ.balance(bob), "a repeat cancel must not refund twice");
		assertEquals(1, mailbox.count(alice));
	}

	@Test
	void resetPasswordClearsTheHashAndRevokesSessions() {
		LinkManager.Session session = links.createSession(bob.toString(), "Bob");
		assertNotNull(links.resolve(session.token()), "sanity: session was valid");

		assertEquals(AdminService.Result.OK, admin.resetPassword("Alice", null, "Bob", "ผู้เล่นลืมรหัส"));
		assertFalse(accounts.hasPassword(bob.toString()));
		assertFalse(accounts.verify("Bob", "anything") != null);
		assertNull(links.resolve(session.token()));
		assertTrue(accounts.nameOf(bob).isPresent(), "the name is kept for the next /register");
	}

	@Test
	void priceOverrideAndBlockAreAppliedAndAudited() {
		assertEquals(AdminService.Result.OK,
				admin.overridePrice("Alice", null, "gold_ingot", 8L, "ตั้งราคาทอง").result());
		assertEquals(8L, prices.base("minecraft:gold_ingot").orElse(-1));

		assertEquals(AdminService.Result.OK,
				admin.overridePrice("Alice", null, "gold_ingot", null, "ลบราคาที่ตั้ง").result());
		assertTrue(prices.base("minecraft:gold_ingot").isEmpty());

		assertEquals(AdminService.Result.OK,
				admin.blockItem("Alice", null, "bedrock", true, "บล็อก bedrock"));
		assertTrue(prices.isBlocked("minecraft:bedrock"));
		assertEquals(AdminService.Result.OK,
				admin.blockItem("Alice", null, "bedrock", false, "ปลดบล็อก bedrock"));
		assertFalse(prices.isBlocked("minecraft:bedrock"));

		assertEquals(4, audit.tail(10).size());
	}

	@Test
	void overviewReportsTotalsAndPlayers() {
		admin.money("Alice", null, "Bob", "give", 10, "ทดสอบภาพรวม");
		var overview = admin.overview("Alice", "127.0.0.1");
		assertTrue(overview.getAsJsonObject("totals").get("invariantOk").getAsBoolean());
		assertEquals(0L, overview.getAsJsonObject("totals").get("balances").getAsLong()
				+ overview.getAsJsonObject("totals").get("escrow").getAsLong()
				+ overview.getAsJsonObject("totals").get("systemNet").getAsLong());
		assertEquals(2, overview.getAsJsonArray("players").size());
		assertTrue(overview.getAsJsonObject("market").has("pricesStale"));
	}
}
