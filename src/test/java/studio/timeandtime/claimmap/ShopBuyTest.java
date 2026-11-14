package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money + mailbox half of the shop. {@code prepareBuy} needs a live registry,
 * so these tests build a BuyPlan directly and exercise {@code commitBuy}.
 */
class ShopBuyTest {
	@TempDir
	Path tmp;

	private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private ClaimMapConfig cfg;
	private EconomyManager economy;
	private MailboxManager mailbox;
	private PriceService prices;
	private ShopManager shop;

	@BeforeEach
	void setUp() {
		cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		cfg.startingBalance = 1000;
		cfg.mailboxMaxEntries = 5;
		cfg.shopMaxQtyPerTx = 2304;
		economy = new EconomyManager(tmp, cfg, () -> 5000L);
		economy.load();
		economy.ensureAccount(alice);
		mailbox = new MailboxManager(tmp.resolve("mailbox.json"), () -> 5000L, cfg.mailboxMaxEntries);
		mailbox.load();
		prices = new PriceService(tmp, cfg, () -> 5000L);
		prices.applyFetched(Map.of("diamond", 10L));
		shop = new ShopManager(cfg, economy, mailbox, prices, () -> 5000L);
	}

	private ShopManager.BuyPlan plan(int qty, long unit, String ref, int entries) {
		List<MailboxManager.MailEntry> list = new ArrayList<>();
		for (int i = 0; i < entries; i++) {
			list.add(new MailboxManager.MailEntry("mb_shop_" + ref + "_" + i,
					"{\"id\":\"minecraft:diamond\"}", "minecraft:diamond", 64, "เพชร", "shop", 5000L));
		}
		return new ShopManager.BuyPlan(ShopManager.Result.OK, "minecraft:diamond", qty, unit, unit * qty, list, ref);
	}

	@Test
	void successfulPurchaseChargesOnceAndFillsTheMailbox() {
		ShopManager.BuyOutcome o = shop.commitBuy(alice, plan(2, 13, "r1", 1));

		assertEquals(ShopManager.Result.OK, o.result());
		assertEquals(2, o.bought());
		assertEquals(26, o.spent());
		assertEquals(974, o.balance());
		assertEquals(974, economy.balance(alice));
		assertEquals(1, mailbox.count(alice));
		assertEquals(0, economy.sumPlayerBalances() + economy.sumEscrow() + economy.systemNet());
	}

	@Test
	void insufficientFundsChangesNothing() {
		ShopManager.BuyOutcome o = shop.commitBuy(alice, plan(100, 13, "r2", 1));

		assertEquals(ShopManager.Result.INSUFFICIENT, o.result());
		assertEquals(1000, economy.balance(alice));
		assertEquals(0, mailbox.count(alice));
	}

	@Test
	void fullMailboxIsRejectedBeforeCharging() {
		cfg.mailboxMaxEntries = 1;
		MailboxManager small = new MailboxManager(tmp.resolve("small.json"), () -> 5000L, 1);
		small.load();
		ShopManager smallShop = new ShopManager(cfg, economy, small, prices, () -> 5000L);

		ShopManager.BuyOutcome first = smallShop.commitBuy(alice, plan(64, 13, "r3", 1));
		assertEquals(ShopManager.Result.OK, first.result());
		assertEquals(1, small.count(alice));

		ShopManager.BuyOutcome second = smallShop.commitBuy(alice, plan(64, 13, "r4", 1));
		assertEquals(ShopManager.Result.MAILBOX_FULL, second.result());
		assertEquals(1000 - 64 * 13, economy.balance(alice));
	}

	@Test
	void frozenAccountsCannotBuy() {
		economy.setFrozen(alice, true);
		ShopManager.BuyOutcome o = shop.commitBuy(alice, plan(1, 13, "r5", 1));
		assertEquals(ShopManager.Result.FROZEN, o.result());
		assertEquals(1000, economy.balance(alice));
	}

	@Test
	void dailyQuotaIsEnforced() {
		cfg.shopDailyBuyQty = 5;
		ShopManager quotaShop = new ShopManager(cfg, economy, mailbox, prices, () -> 5000L);

		assertEquals(ShopManager.Result.OK, quotaShop.commitBuy(alice, plan(3, 13, "r6", 1)).result());
		assertEquals(ShopManager.Result.OK, quotaShop.commitBuy(alice, plan(2, 13, "r7", 1)).result());
		ShopManager.BuyOutcome over = quotaShop.commitBuy(alice, plan(1, 13, "r8", 1));
		assertEquals(ShopManager.Result.LIMIT_REACHED, over.result());
		assertEquals(1000 - 5 * 13, economy.balance(alice));
	}

	@Test
	void retryingTheSamePlanIsIdempotent() {
		ShopManager.BuyPlan p = plan(2, 13, "r9", 1);
		assertEquals(ShopManager.Result.OK, shop.commitBuy(alice, p).result());
		ShopManager.BuyOutcome retry = shop.commitBuy(alice, p);
		assertEquals(ShopManager.Result.OK, retry.result());
		assertEquals(1, mailbox.count(alice), "duplicate mailbox ids are not added twice");
		assertEquals(974, economy.balance(alice), "a retry must not charge a second time");
		assertTrue(retry.spent() == 0);
	}
}
