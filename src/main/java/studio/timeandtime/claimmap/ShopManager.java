package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Shop: buy from the system (items always land in the mailbox), sell plain
 * items from the player's main inventory (PHASES.md 5.4/5.5).
 *
 * <p>Money + mailbox work is {@code Market.LOCK}-guarded and independent of
 * Minecraft; the inventory/encode steps run on the server thread (R3).
 */
public final class ShopManager {
	public enum Result {
		OK, INVALID_ITEM, INVALID_QTY, NO_PRICE, BLOCKED, INSUFFICIENT, NOT_ENOUGH_ITEMS,
		LIMIT_REACHED, MAILBOX_FULL, FROZEN, DISABLED, OVERFLOW, IO_ERROR, OFFLINE
	}

	/** Prepared purchase: everything needed to charge and fill the mailbox. */
	public record BuyPlan(Result result, String itemId, int qty, long unitPrice, long total,
			List<MailboxManager.MailEntry> entries, String ref) {
		static BuyPlan error(Result r, String itemId, int qty) {
			return new BuyPlan(r, itemId, qty, 0, 0, List.of(), "");
		}
	}

	public record BuyOutcome(Result result, int bought, long spent, long balance, int mailboxCount) {
	}

	public record SellOutcome(Result result, int sold, long earned, long balance) {
	}

	public record ClaimOutcome(int claimed, int remaining, int skipped, boolean inventoryFull) {
		public ClaimOutcome(int claimed, int remaining, int skipped) {
			this(claimed, remaining, skipped, false);
		}
	}

	private static final Gson GSON = new GsonBuilder().create();

	private final ClaimMapConfig cfg;
	private final EconomyManager economy;
	private final MailboxManager mailbox;
	private final PriceService prices;
	private final LongSupplier now;
	private final Path usageFile;
	private final Map<String, Integer> usage = new HashMap<>();
	private boolean usageLoaded;

	public ShopManager(ClaimMapConfig cfg, EconomyManager economy, MailboxManager mailbox, PriceService prices,
			LongSupplier now) {
		this.cfg = cfg;
		this.economy = economy;
		this.mailbox = mailbox;
		this.prices = prices;
		this.now = now;
		this.usageFile = cfg.shopUsageFile();
	}

	private volatile AuctionManager auctions;

	/** Market-wide kill switch: a corrupt auctions.json also stops the shop (R6). */
	public void attachAuctions(AuctionManager auctions) {
		this.auctions = auctions;
	}

	public boolean isDisabled() {
		AuctionManager a = auctions;
		return economy.isDisabled() || mailbox.isDisabled() || (a != null && a.isDisabled());
	}

	/** Unit sell price, or 0 when the item has no sell price. */
	public long sellUnitPrice(String itemId) {
		var p = prices.sellPrice(itemId);
		return p.isEmpty() ? 0L : p.getAsLong();
	}

	// ---- buying -------------------------------------------------------------

	/**
	 * Validates and materialises the purchase (server thread: needs the registry
	 * and item codecs). Nothing is charged here.
	 */
	public BuyPlan prepareBuy(UUID buyer, String rawItem, int qty, String ref, RegistryAccess access) {
		String itemId = PriceService.normalize(rawItem);
		if (itemId.isEmpty() || !ItemRegistryAdapter.exists(itemId)) {
			return BuyPlan.error(Result.INVALID_ITEM, itemId, qty);
		}
		if (qty < 1 || qty > cfg.shopMaxQtyPerTx) return BuyPlan.error(Result.INVALID_QTY, itemId, qty);
		if (economy.isFrozen(buyer)) return BuyPlan.error(Result.FROZEN, itemId, qty);
		if (prices.isBlocked(itemId)) return BuyPlan.error(Result.BLOCKED, itemId, qty);

		var unit = prices.buyPrice(itemId);
		if (unit.isEmpty()) return BuyPlan.error(Result.NO_PRICE, itemId, qty);
		long total;
		try {
			total = Math.multiplyExact(unit.getAsLong(), (long) qty);
		} catch (ArithmeticException e) {
			return BuyPlan.error(Result.OVERFLOW, itemId, qty);
		}
		if (total > EconomyManager.MAX_MONEY) return BuyPlan.error(Result.OVERFLOW, itemId, qty);

		Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
		if (item == null) return BuyPlan.error(Result.INVALID_ITEM, itemId, qty);
		int maxStack = Math.max(1, new ItemStack(item).getMaxStackSize());

		List<MailboxManager.MailEntry> entries = new ArrayList<>();
		int remaining = qty;
		int index = 0;
		while (remaining > 0) {
			int count = Math.min(maxStack, remaining);
			ItemStack stack = new ItemStack(item, count);
			String json = ItemCodecs.encode(stack, access);
			if (json == null) return BuyPlan.error(Result.IO_ERROR, itemId, qty);
			ItemCodecs.ItemView view = ItemCodecs.view(stack);
			entries.add(new MailboxManager.MailEntry(
					"mb_shop_" + ref + "_" + index++, json, view.itemId(), view.count(), view.label(),
					"shop", now.getAsLong()));
			remaining -= count;
		}
		return new BuyPlan(Result.OK, itemId, qty, unit.getAsLong(), total, entries, ref);
	}

	/** Charges the buyer then fills the mailbox, rolling back on a surprise failure. */
	public BuyOutcome commitBuy(UUID buyer, BuyPlan plan) {
		if (plan == null || plan.result() != Result.OK) {
			Result r = plan == null ? Result.IO_ERROR : plan.result();
			return new BuyOutcome(r, 0, 0, economy.balance(buyer), mailbox.count(buyer));
		}
		synchronized (Market.LOCK) {
			if (isDisabled()) return new BuyOutcome(Result.DISABLED, 0, 0, economy.balance(buyer), mailbox.count(buyer));
			if (economy.isFrozen(buyer)) {
				return new BuyOutcome(Result.FROZEN, 0, 0, economy.balance(buyer), mailbox.count(buyer));
			}
			// A retry after a crash must not charge twice: if every entry is already
			// in the mailbox, the purchase completed.
			if (!plan.entries().isEmpty() && plan.entries().stream().allMatch(e -> mailbox.exists(buyer, e.id()))) {
				return new BuyOutcome(Result.OK, plan.qty(), 0, economy.balance(buyer), mailbox.count(buyer));
			}
			if (!mailbox.hasCapacity(buyer, plan.entries().size())) {
				return new BuyOutcome(Result.MAILBOX_FULL, 0, 0, economy.balance(buyer), mailbox.count(buyer));
			}
			if (!usageAllows(buyer, plan.itemId(), plan.qty())) {
				return new BuyOutcome(Result.LIMIT_REACHED, 0, 0, economy.balance(buyer), mailbox.count(buyer));
			}

			TxResult charge = economy.withdraw(buyer, plan.total(), "shop.buy:" + plan.itemId(), plan.ref());
			if (charge == TxResult.INSUFFICIENT) {
				return new BuyOutcome(Result.INSUFFICIENT, 0, 0, economy.balance(buyer), mailbox.count(buyer));
			}
			if (charge == TxResult.FROZEN) {
				return new BuyOutcome(Result.FROZEN, 0, 0, economy.balance(buyer), mailbox.count(buyer));
			}
			if (charge != TxResult.OK) {
				return new BuyOutcome(Result.IO_ERROR, 0, 0, economy.balance(buyer), mailbox.count(buyer));
			}

			List<String> added = new ArrayList<>();
			for (MailboxManager.MailEntry entry : plan.entries()) {
				if (mailbox.exists(buyer, entry.id())) continue;      // idempotent retry
				if (!mailbox.add(buyer, entry)) {
					for (String id : added) mailbox.remove(buyer, id);
					economy.deposit(buyer, plan.total(), "shop.buy:" + plan.itemId(), plan.ref() + "_refund");
					return new BuyOutcome(Result.IO_ERROR, 0, 0, economy.balance(buyer), mailbox.count(buyer));
				}
				added.add(entry.id());
			}
			recordUsage(buyer, plan.itemId(), plan.qty());
			return new BuyOutcome(Result.OK, plan.qty(), plan.total(), economy.balance(buyer),
					mailbox.count(buyer));
		}
	}

	// ---- selling ------------------------------------------------------------

	/** Counts plain stacks of one item in the main inventory + hotbar. */
	public static int countPlain(ServerPlayer player, String itemId) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack == null || stack.isEmpty()) continue;
			if (!ItemCodecs.idOf(stack).equals(itemId)) continue;
			if (!ItemCodecs.isPlain(stack)) continue;
			total += stack.getCount();
		}
		return total;
	}

	/** Every (itemId, qty) the shop would buy from this player right now. */
	public Map<String, Integer> sellableSummary(ServerPlayer player) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack == null || stack.isEmpty() || !ItemCodecs.isPlain(stack)) continue;
			String id = ItemCodecs.idOf(stack);
			if (prices.isBlocked(id) || prices.sellPrice(id).isEmpty()) continue;
			out.merge(id, stack.getCount(), Integer::sum);
		}
		return out;
	}

	/** Runs on the server thread. Removes the items, then credits the money. */
	public SellOutcome sell(ServerPlayer player, String rawItem, int qty, String ref) {
		UUID uuid = player.getUUID();
		String itemId = PriceService.normalize(rawItem);
		if (itemId.isEmpty() || !ItemRegistryAdapter.exists(itemId)) {
			return new SellOutcome(Result.INVALID_ITEM, 0, 0, economy.balance(uuid));
		}
		if (qty < 1 || qty > cfg.shopMaxQtyPerTx) {
			return new SellOutcome(Result.INVALID_QTY, 0, 0, economy.balance(uuid));
		}
		synchronized (Market.LOCK) {
			if (isDisabled()) return new SellOutcome(Result.DISABLED, 0, 0, economy.balance(uuid));
			if (economy.isFrozen(uuid)) return new SellOutcome(Result.FROZEN, 0, 0, economy.balance(uuid));
			if (prices.isBlocked(itemId)) return new SellOutcome(Result.BLOCKED, 0, 0, economy.balance(uuid));
			var unit = prices.sellPrice(itemId);
			if (unit.isEmpty()) return new SellOutcome(Result.NO_PRICE, 0, 0, economy.balance(uuid));

			int have = countPlain(player, itemId);
			if (have < qty) return new SellOutcome(Result.NOT_ENOUGH_ITEMS, 0, 0, economy.balance(uuid));

			long total;
			try {
				total = Math.multiplyExact(unit.getAsLong(), (long) qty);
			} catch (ArithmeticException e) {
				return new SellOutcome(Result.OVERFLOW, 0, 0, economy.balance(uuid));
			}
			if (economy.balance(uuid) > EconomyManager.MAX_BALANCE - total) {
				return new SellOutcome(Result.OVERFLOW, 0, 0, economy.balance(uuid));
			}

			List<ItemStack> removed = takeItems(player, itemId, qty);
			if (removed == null) {
				return new SellOutcome(Result.NOT_ENOUGH_ITEMS, 0, 0, economy.balance(uuid));
			}
			TxResult credit = economy.deposit(uuid, total, "shop.sell:" + itemId, ref);
			if (credit != TxResult.OK) {
				for (ItemStack stack : removed) {
					if (!player.getInventory().add(stack)) {
						String json = ItemCodecs.encode(stack, player.level().registryAccess());
						if (json != null) {
							ItemCodecs.ItemView v = ItemCodecs.view(stack);
							mailbox.add(uuid, new MailboxManager.MailEntry(
									"mb_sell_fail_" + now.getAsLong() + "_" + Math.abs(stack.hashCode()),
									json, v.itemId(), v.count(), v.label(), "shop", now.getAsLong()));
						}
					}
				}
				return new SellOutcome(credit == TxResult.FROZEN ? Result.FROZEN : Result.IO_ERROR, 0, 0,
						economy.balance(uuid));
			}
			return new SellOutcome(Result.OK, qty, total, economy.balance(uuid));
		}
	}

	/** Removes exactly {@code qty}; returns the removed stacks, or null when short. */
	private static List<ItemStack> takeItems(ServerPlayer player, String itemId, int qty) {
		List<ItemStack> removed = new ArrayList<>();
		int left = qty;
		List<ItemStack> inv = player.getInventory().getNonEquipmentItems();
		for (ItemStack stack : inv) {
			if (left <= 0) break;
			if (stack == null || stack.isEmpty()) continue;
			if (!ItemCodecs.idOf(stack).equals(itemId) || !ItemCodecs.isPlain(stack)) continue;
			int take = Math.min(left, stack.getCount());
			if (take == stack.getCount()) {
				removed.add(stack.copy());
				stack.setCount(0);
			} else {
				removed.add(stack.copyWithCount(take));
				stack.shrink(take);
			}
			left -= take;
		}
		if (left > 0) {
			// put back what we already removed so a failed sell changes nothing
			for (ItemStack stack : removed) player.getInventory().add(stack);
			return null;
		}
		return removed;
	}

	// ---- mailbox ------------------------------------------------------------

	/** Runs on the server thread: moves as much as fits into the inventory. */
	public ClaimOutcome claim(ServerPlayer player) {
		UUID uuid = player.getUUID();
		RegistryAccess access = player.level().registryAccess();
		int claimed = 0;
		int skipped = 0;
		boolean inventoryFull = false;
		synchronized (Market.LOCK) {
			for (MailboxManager.MailEntry entry : mailbox.list(uuid)) {
				var decoded = ItemCodecs.decode(entry.itemJson(), access);
				if (decoded.isEmpty()) {
					skipped++;
					ClaimMap.LOGGER.warn("[claimmap] Mailbox entry {} for {} cannot be decoded; leaving it in place",
							entry.id(), uuid);
					continue;
				}
				ItemStack stack = decoded.get();
				stack.setCount(Math.min(entry.count(), stack.getMaxStackSize()));
				int before = stack.getCount();
				player.getInventory().add(stack);
				int moved = before - stack.getCount();
				claimed += moved;
				if (stack.getCount() > 0) {
					// Inventory is full: keep the remainder as one smaller entry.
					inventoryFull = true;
					stack.setCount(stack.getCount());
					String json = ItemCodecs.encode(stack, access);
					if (json == null) {
						mailbox.remove(uuid, entry.id());
						continue;
					}
					ItemCodecs.ItemView v = ItemCodecs.view(stack);
					mailbox.replace(uuid, entry.id(), new MailboxManager.MailEntry(
							entry.id(), json, v.itemId(), v.count(), v.label(), entry.from(), entry.t()));
					break;
				}
				mailbox.remove(uuid, entry.id());
			}
		}
		int remaining = mailbox.count(uuid);
		if (remaining > 0 && player.getInventory().getFreeSlot() == -1) {
			inventoryFull = true;
		}
		return new ClaimOutcome(claimed, remaining, skipped, inventoryFull);
	}

	// ---- daily quota --------------------------------------------------------

	private boolean usageAllows(UUID uuid, String itemId, int qty) {
		int quota = cfg.shopDailyBuyQty;
		if (quota <= 0) return true;
		loadUsage();
		String key = usageKey(uuid, itemId);
		return usage.getOrDefault(key, 0) + qty <= quota;
	}

	private void recordUsage(UUID uuid, String itemId, int qty) {
		if (cfg.shopDailyBuyQty <= 0) return;
		loadUsage();
		usage.merge(usageKey(uuid, itemId), qty, Integer::sum);
		saveUsage();
	}

	private String usageKey(UUID uuid, String itemId) {
		return LocalDate.now() + "|" + uuid + "|" + itemId;
	}

	private void loadUsage() {
		if (usageLoaded) return;
		usageLoaded = true;
		if (!Files.exists(usageFile)) return;
		try {
			JsonObject root = GSON.fromJson(Files.readString(usageFile, StandardCharsets.UTF_8), JsonObject.class);
			JsonObject u = root == null ? null : root.getAsJsonObject("usage");
			if (u == null) return;
			String today = LocalDate.now().toString();
			for (Map.Entry<String, JsonElement> e : u.entrySet()) {
				if (!e.getKey().startsWith(today + "|")) continue;   // drop yesterday's counters
				usage.put(e.getKey(), e.getValue().getAsInt());
			}
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[claimmap] Could not read shop-usage.json ({}); resetting quotas", e.getMessage());
			usage.clear();
		}
	}

	private void saveUsage() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonObject u = new JsonObject();
			for (Map.Entry<String, Integer> e : usage.entrySet()) u.addProperty(e.getKey(), e.getValue());
			root.add("usage", u);
			Path parent = usageFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = usageFile.resolveSibling("shop-usage.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, usageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not write shop-usage.json", e);
		}
	}
}
