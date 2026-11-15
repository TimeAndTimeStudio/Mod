package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Player fixed-price listings. This is deliberately separate from the auction
 * house: players list items at a fixed price here, while only the server opens
 * auctions (see {@link ConsignmentManager}).
 *
 * <p>No Minecraft dependency: items are opaque {@code itemJson} strings. The
 * listing holds the item, so buying is a direct player-to-player transfer and
 * the item always lands in the buyer's mailbox.
 */
public final class PlayerMarket {
	public static final String ACTIVE = "ACTIVE";
	public static final String SOLD = "SOLD";
	public static final String CANCELLED = "CANCELLED";

	private static final long HISTORY_KEEP_MS = 7L * 24 * 60 * 60_000L;

	public enum Result {
		OK, NOT_FOUND, CLOSED, OWN_LISTING, LIMIT_REACHED, INSUFFICIENT, FROZEN,
		DISABLED, IO_ERROR, BAD_REQUEST, FORBIDDEN
	}

	public static final class Listing {
		public String id;
		public String seller;
		public String sellerName;
		public String itemJson;
		public String itemId;
		public int count;
		public String label;
		public long price;
		public long createdAt;
		public String status = ACTIVE;
		public String buyer;
		public String buyerName;
		public Long closedAt;
		public boolean paidOut;
		public boolean itemDelivered;

		public boolean isActive() {
			return ACTIVE.equals(status);
		}
	}

	public record CreatePlan(String seller, String sellerName, String itemJson, String itemId, int count,
			String label, long price) {
	}

	public record CreateOutcome(Result result, String listingId) {
	}

	private static final Gson GSON = new GsonBuilder().create();

	private final Path file;
	private final ClaimMapConfig cfg;
	private final EconomyManager economy;
	private final MailboxManager mailbox;
	private final LongSupplier now;
	private final List<Listing> listings = new ArrayList<>();
	private volatile boolean disabled;
	private long lastPruneMs;
	private int idCounter;

	public PlayerMarket(Path dir, ClaimMapConfig cfg, EconomyManager economy, MailboxManager mailbox,
			LongSupplier now) {
		this.file = dir.resolve("player-market.json");
		this.cfg = cfg;
		this.economy = economy;
		this.mailbox = mailbox;
		this.now = now;
	}

	// ---- persistence --------------------------------------------------------

	public void load() {
		synchronized (Market.LOCK) {
			listings.clear();
			disabled = false;
			if (!Files.exists(file)) return;
			try {
				JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
				if (root == null) throw new IllegalStateException("empty player-market.json");
				JsonArray arr = root.getAsJsonArray("listings");
				if (arr != null) {
					for (JsonElement el : arr) {
						Listing l = GSON.fromJson(el, Listing.class);
						if (l == null || l.id == null || l.seller == null || l.itemJson == null
								|| l.status == null || l.count < 1 || l.price < 1) {
							throw new IllegalStateException("invalid market row");
						}
						listings.add(l);
					}
				}
			} catch (Exception e) {
				corrupt("cannot read player-market.json: " + e.getMessage());
				return;
			}
			pruneOld(now.getAsLong());
		}
	}

	private void corrupt(String why) {
		disabled = true;
		listings.clear();
		ClaimMap.LOGGER.error("[timesmp] PLAYER MARKET DISABLED — player-market.json: {}", why);
		try {
			Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + now.getAsLong());
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not move damaged player-market.json aside", e);
		}
	}

	private void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonArray arr = new JsonArray();
			for (Listing l : listings) arr.add(GSON.toJsonTree(l));
			root.add("listings", arr);
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = file.resolveSibling("player-market.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not save player-market.json", e);
		}
	}

	// ---- queries ------------------------------------------------------------

	public boolean isDisabled() {
		return disabled;
	}

	public List<Listing> all() {
		synchronized (Market.LOCK) {
			return List.copyOf(listings);
		}
	}

	public Listing find(String id) {
		synchronized (Market.LOCK) {
			for (Listing l : listings) if (l.id.equals(id)) return l;
			return null;
		}
	}

	public List<Listing> recentForSeller(String sellerUuid) {
		long cutoff = now.getAsLong() - HISTORY_KEEP_MS;
		List<Listing> out = new ArrayList<>();
		synchronized (Market.LOCK) {
			for (Listing l : listings) {
				if (!l.seller.equals(sellerUuid) || l.isActive()) continue;
				if (l.closedAt != null && l.closedAt < cutoff) continue;
				out.add(l);
			}
		}
		out.sort(Comparator.comparingLong((Listing l) -> l.closedAt == null ? 0 : l.closedAt).reversed());
		return out;
	}

	// ---- create -------------------------------------------------------------

	public CreateOutcome create(CreatePlan p) {
		if (p == null || p.seller() == null || p.itemJson() == null || p.itemJson().isBlank()) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		try {
			UUID.fromString(p.seller());
		} catch (RuntimeException e) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		if (p.price() < 1 || p.price() > EconomyManager.MAX_MONEY || p.count() < 1) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		synchronized (Market.LOCK) {
			if (disabled) return new CreateOutcome(Result.DISABLED, null);
			UUID seller = UUID.fromString(p.seller());
			if (economy.isFrozen(seller)) return new CreateOutcome(Result.FROZEN, null);
			economy.ensureAccount(seller);
			int active = 0;
			for (Listing l : listings) {
				if (l.isActive() && l.seller.equals(p.seller())) active++;
			}
			if (active >= cfg.marketMaxActivePerPlayer) return new CreateOutcome(Result.LIMIT_REACHED, null);

			Listing l = new Listing();
			l.id = newId();
			l.seller = p.seller();
			l.sellerName = p.sellerName();
			l.itemJson = p.itemJson();
			l.itemId = p.itemId();
			l.count = p.count();
			l.label = p.label();
			l.price = p.price();
			l.createdAt = now.getAsLong();
			l.status = ACTIVE;
			listings.add(l);
			save();
			return new CreateOutcome(Result.OK, l.id);
		}
	}

	private String newId() {
		idCounter++;
		return "pm_" + Long.toString(now.getAsLong(), 36) + "_" + Integer.toString(idCounter, 36);
	}

	// ---- buy / cancel -------------------------------------------------------

	public Result buy(UUID buyer, String buyerName, String listingId) {
		synchronized (Market.LOCK) {
			if (disabled) return Result.DISABLED;
			Listing l = byId(listingId);
			if (l == null) return Result.NOT_FOUND;
			if (!l.isActive()) return Result.CLOSED;
			if (l.seller.equals(buyer.toString())) return Result.OWN_LISTING;
			if (economy.isFrozen(buyer)) return Result.FROZEN;
			economy.ensureAccount(buyer);
			UUID seller = UUID.fromString(l.seller);
			economy.ensureAccount(seller);
			if (!mailbox.hasCapacity(buyer, 1)) return Result.IO_ERROR;

			// Seller may be frozen but must still be paid for a sale that started.
			TxResult r = economy.apply(List.of(new Move(Acct.player(buyer), Acct.player(seller),
					l.price, "market.buy:" + l.id, l.id)), true);
			if (r == TxResult.INSUFFICIENT) return Result.INSUFFICIENT;
			if (r == TxResult.NO_ACCOUNT) return Result.NOT_FOUND;
			if (r != TxResult.OK) return Result.IO_ERROR;

			l.status = SOLD;
			l.buyer = buyer.toString();
			l.buyerName = buyerName;
			l.closedAt = now.getAsLong();
			l.paidOut = true;
			deliver(l, buyer);
			save();
			return Result.OK;
		}
	}

	public Result cancel(UUID actor, boolean admin, String listingId) {
		synchronized (Market.LOCK) {
			if (disabled) return Result.DISABLED;
			Listing l = byId(listingId);
			if (l == null) return Result.NOT_FOUND;
			if (!l.isActive()) return Result.CLOSED;
			if (!admin && !l.seller.equals(actor.toString())) return Result.FORBIDDEN;
			l.status = CANCELLED;
			l.closedAt = now.getAsLong();
			deliver(l, UUID.fromString(l.seller));
			save();
			return Result.OK;
		}
	}

	private boolean deliver(Listing l, UUID to) {
		String mailId = "mb_pm_" + l.id + "_" + (SOLD.equals(l.status) ? "buyer" : "return");
		if (mailbox.exists(to, mailId)) {
			l.itemDelivered = true;
			return true;
		}
		boolean ok = mailbox.add(to, new MailboxManager.MailEntry(
				mailId, l.itemJson, l.itemId, l.count, l.label,
				"market:" + l.id, now.getAsLong()));
		if (ok) l.itemDelivered = true;
		return ok;
	}

	/** Retries item delivery for sold/cancelled listings whose mailbox was full. */
	public void retryPending() {
		synchronized (Market.LOCK) {
			if (disabled) return;
			boolean changed = false;
			for (Listing l : listings) {
				if (l.isActive() || l.itemDelivered) continue;
				UUID to = SOLD.equals(l.status) && l.buyer != null
						? UUID.fromString(l.buyer)
						: UUID.fromString(l.seller);
				if (deliver(l, to)) changed = true;
			}
			if (changed) save();
			long nowMs = now.getAsLong();
			if (nowMs - lastPruneMs > 3_600_000L) {
				lastPruneMs = nowMs;
				if (pruneOld(nowMs)) save();
			}
		}
	}

	private Listing byId(String id) {
		if (id == null) return null;
		for (Listing l : listings) if (l.id.equals(id)) return l;
		return null;
	}

	private boolean pruneOld(long nowMs) {
		return listings.removeIf(l -> !l.isActive() && l.closedAt != null
				&& nowMs - l.closedAt > HISTORY_KEEP_MS);
	}
}
