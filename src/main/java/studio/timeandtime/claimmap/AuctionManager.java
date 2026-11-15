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
 * Player auctions backed by real escrow money (PHASES.md phase 6).
 *
 * <p>Every money movement goes through {@link EconomyManager#apply} so a bid
 * that cannot be paid changes nothing. Settling is idempotent through the
 * {@code paidOut}/{@code itemDelivered} flags so a crash can be retried.
 *
 * <p>No Minecraft dependency: items are opaque {@code itemJson} strings.
 */
public final class AuctionManager {
	public static final String ACTIVE = "ACTIVE";
	public static final String SOLD = "SOLD";
	public static final String EXPIRED = "EXPIRED";
	public static final String CANCELLED = "CANCELLED";

	private static final long HISTORY_KEEP_MS = 7L * 24 * 60 * 60_000L;

	public enum Result {
		OK, NOT_FOUND, CLOSED, OWN_AUCTION, ALREADY_HIGHEST, BID_TOO_LOW, USE_BUYOUT,
		LIMIT_REACHED, INSUFFICIENT, FROZEN, DISABLED, IO_ERROR, HAS_BIDS, FORBIDDEN, BAD_REQUEST,
		ALREADY_LISTED, ALREADY_OWNER
	}

	public static final class Auction {
		public String id;
		public String seller;
		public String sellerName;
		/** ITEM or CELL. Older files without this field are item auctions. */
		public String kind = "ITEM";
		/** Cell id "cx,cz" for CELL auctions, null for item auctions. */
		public String cellId;
		public String itemJson;
		public String itemId;
		public int count;
		public String label;
		public long startPrice;
		public Long buyout;
		public long highBid;
		public String highBidder;
		public String highBidderName;
		public long createdAt;
		public long endsAt;
		public String status = ACTIVE;
		/** True for auctions created by the server/admin from a consignment. */
		public boolean serverOwned;
		public boolean paidOut;
		public boolean itemDelivered;
		public Long closedAt;
		public String winner;
		public Long finalPrice;

		public boolean isActive() {
			return ACTIVE.equals(status);
		}

		public boolean isCellAuction() {
			return "CELL".equals(kind);
		}
	}

	public record CreatePlan(String seller, String sellerName, String itemJson, String itemId, int count,
			String label, long startPrice, Long buyout, int hours) {
	}

	public record CreateOutcome(Result result, String auctionId) {
	}

	public record BidOutcome(Result result, long minBid, long highBid, long endsAt) {
	}

	private static final Gson GSON = new GsonBuilder().create();

	private final Path file;
	private final ClaimMapConfig cfg;
	private final EconomyManager economy;
	private final MailboxManager mailbox;
	private final LongSupplier now;
	private final List<Auction> auctions = new ArrayList<>();
	private volatile ConsignmentManager consignments;
	private volatile RegionManager regions;
	private volatile boolean disabled;
	private long lastPruneMs;
	private int idCounter;

	public AuctionManager(Path dir, ClaimMapConfig cfg, EconomyManager economy, MailboxManager mailbox,
			LongSupplier now) {
		this.file = dir.resolve("auctions.json");
		this.cfg = cfg;
		this.economy = economy;
		this.mailbox = mailbox;
		this.now = now;
	}

	// ---- persistence --------------------------------------------------------

	public void load() {
		synchronized (Market.LOCK) {
			auctions.clear();
			disabled = false;
			if (!Files.exists(file)) return;
			try {
				JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
				if (root == null) throw new IllegalStateException("empty auctions.json");
				JsonArray arr = root.getAsJsonArray("auctions");
				if (arr != null) {
					for (JsonElement el : arr) {
						Auction a = GSON.fromJson(el, Auction.class);
						if (a == null || a.id == null || a.seller == null || a.status == null || a.count < 1) {
							throw new IllegalStateException("invalid auction row");
						}
						if (a.kind == null) a.kind = "ITEM";
						if (a.isCellAuction()) {
							if (a.cellId == null || a.cellId.isBlank()) throw new IllegalStateException("invalid cell auction row");
						} else if (a.itemJson == null) {
							throw new IllegalStateException("invalid auction row");
						}
						auctions.add(a);
					}
				}
			} catch (Exception e) {
				corrupt("cannot read auctions.json: " + e.getMessage());
				return;
			}
			pruneOld(now.getAsLong());
		}
		// Economy is always loaded before auctions, so the escrow check can run here.
		validateEscrow();
	}

	private void corrupt(String why) {
		disabled = true;
		auctions.clear();
		ClaimMap.LOGGER.error("[claimmap] MARKET DISABLED — auctions.json: {}", why);
		try {
			Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + now.getAsLong());
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			ClaimMap.LOGGER.error("[claimmap] Moved damaged file to {}", target);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not move damaged auctions.json aside", e);
		}
	}

	private void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonArray arr = new JsonArray();
			for (Auction a : auctions) arr.add(GSON.toJsonTree(a));
			root.add("auctions", arr);
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = file.resolveSibling("auctions.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not save auctions.json", e);
		}
	}

	/**
	 * Checks the escrow invariant against the economy. Must run after both
	 * managers loaded; a mismatch disables the market (R6/6.5).
	 */
	public boolean validateEscrow() {
		synchronized (Market.LOCK) {
			if (disabled) return false;
			for (Auction a : auctions) {
				long held = economy.escrowBalance(a.id);
				long expected = a.isActive() ? a.highBid : 0L;
				if (held != expected) {
					corrupt("escrow mismatch for " + a.id + " (held=" + held + ", highBid=" + a.highBid
							+ ", status=" + a.status + ")");
					return false;
				}
			}
			return true;
		}
	}

	/** Server-owned auctions return cancelled items to the consignment store. */
	public void attachConsignments(ConsignmentManager consignments) {
		this.consignments = consignments;
	}

	/** Needed to settle CELL auctions; regions load before auctions start. */
	public void attachRegions(RegionManager regions) {
		this.regions = regions;
	}

	/** Creates an auction owned by the server (the only kind players cannot open). */
	public CreateOutcome createServerAuction(String itemJson, String itemId, int count, String label,
			long startPrice, Long buyout, int hours) {
		if (itemJson == null || itemJson.isBlank() || itemId == null || count < 1 || label == null) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		if (startPrice < 1 || startPrice > EconomyManager.MAX_MONEY) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		if (buyout != null && (buyout <= startPrice || buyout > EconomyManager.MAX_MONEY)) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		int duration = hours <= 0 ? cfg.auctionDefaultHours : hours;
		if (duration < 1 || duration > cfg.auctionMaxHours) return new CreateOutcome(Result.BAD_REQUEST, null);
		synchronized (Market.LOCK) {
			if (disabled) return new CreateOutcome(Result.DISABLED, null);
			long nowMs = now.getAsLong();
			Auction a = new Auction();
			a.id = newId();
			a.seller = ConsignmentManager.SERVER;
			a.sellerName = "เซิร์ฟเวอร์";
			a.itemJson = itemJson;
			a.itemId = itemId;
			a.count = count;
			a.label = label;
			a.startPrice = startPrice;
			a.buyout = buyout;
			a.highBid = 0;
			a.createdAt = nowMs;
			a.endsAt = nowMs + duration * 3_600_000L;
			a.status = ACTIVE;
			a.serverOwned = true;
			auctions.add(a);
			save();
			return new CreateOutcome(Result.OK, a.id);
		}
	}

	/** Creates an admin-owned auction for a cell. The cell may be free or owned. */
	public CreateOutcome createCellAuction(String cellId, long startPrice, Long buyout, int hours) {
		int[] coords = parseCellId(cellId);
		if (coords == null || startPrice < 1 || startPrice > EconomyManager.MAX_MONEY) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		if (buyout != null && (buyout <= startPrice || buyout > EconomyManager.MAX_MONEY)) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		RegionManager rm = regions;
		if (rm == null || rm.isCommonArea(coords[0], coords[1])) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		int duration = hours <= 0 ? cfg.auctionDefaultHours : hours;
		if (duration < 1 || duration > cfg.auctionMaxHours) return new CreateOutcome(Result.BAD_REQUEST, null);
		String canonical = coords[0] + "," + coords[1];
		synchronized (Market.LOCK) {
			if (disabled) return new CreateOutcome(Result.DISABLED, null);
			for (Auction a : auctions) {
				if (a.isActive() && a.isCellAuction() && canonical.equals(a.cellId)) {
					return new CreateOutcome(Result.ALREADY_LISTED, null);
				}
			}
			long nowMs = now.getAsLong();
			Auction a = new Auction();
			a.id = newId();
			a.seller = ConsignmentManager.SERVER;
			a.sellerName = "เซิร์ฟเวอร์";
			a.kind = "CELL";
			a.cellId = canonical;
			a.itemId = "cell";
			a.count = 1;
			a.label = "เขต " + canonical;
			a.startPrice = startPrice;
			a.buyout = buyout;
			a.highBid = 0;
			a.createdAt = nowMs;
			a.endsAt = nowMs + duration * 3_600_000L;
			a.status = ACTIVE;
			a.serverOwned = true;
			auctions.add(a);
			save();
			return new CreateOutcome(Result.OK, a.id);
		}
	}

	private static int[] parseCellId(String cellId) {
		if (cellId == null) return null;
		String[] parts = cellId.trim().split(",", -1);
		if (parts.length != 2) return null;
		try {
			return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---- queries ------------------------------------------------------------

	public boolean isDisabled() {
		return disabled;
	}

	/** True only while the auction is ACTIVE and before its deadline. */
	public boolean isOpen(Auction a) {
		return a != null && a.isActive() && now.getAsLong() < a.endsAt;
	}

	/** Current cell owner for admin displays, or null when the cell is free. */
	public String cellOwnerName(String cellId) {
		RegionManager rm = regions;
		return rm == null || cellId == null ? null : rm.ownerName(cellId);
	}

	public List<Auction> all() {
		synchronized (Market.LOCK) {
			return List.copyOf(auctions);
		}
	}

	public Auction find(String id) {
		synchronized (Market.LOCK) {
			for (Auction a : auctions) if (a.id.equals(id)) return a;
			return null;
		}
	}

	/** Minimum acceptable next bid (6.3). */
	public long minBid(Auction a) {
		if (a.highBid <= 0) return a.startPrice;
		long inc;
		try {
			long raw = Math.multiplyExact(a.highBid, (long) cfg.auctionMinIncrementPercent);
			inc = Math.max(1L, raw / 100L + (raw % 100L == 0 ? 0 : 1));
		} catch (ArithmeticException e) {
			inc = Math.max(1L, a.highBid / 100L);
		}
		try {
			return Math.addExact(a.highBid, inc);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	// ---- create -------------------------------------------------------------

	/** Pure validation of a new listing (no side effects). */
	public CreateOutcome planCreate(CreatePlan p) {
		if (p == null || p.seller() == null || p.itemJson() == null || p.itemJson().isBlank()) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		try {
			UUID.fromString(p.seller());
		} catch (RuntimeException e) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		if (p.startPrice() < 1 || p.startPrice() > EconomyManager.MAX_MONEY) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		if (p.buyout() != null && (p.buyout() <= p.startPrice() || p.buyout() > EconomyManager.MAX_MONEY)) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		int hours = p.hours() <= 0 ? cfg.auctionDefaultHours : p.hours();
		if (hours < 1 || hours > cfg.auctionMaxHours) {
			return new CreateOutcome(Result.BAD_REQUEST, null);
		}
		return new CreateOutcome(Result.OK, null);
	}

	/** Charges the listing fee (if any), stores the auction, and returns its id. */
	public CreateOutcome create(CreatePlan p) {
		CreateOutcome plan = planCreate(p);
		if (plan.result() != Result.OK) return plan;
		synchronized (Market.LOCK) {
			if (disabled) return new CreateOutcome(Result.DISABLED, null);
			if (economy.isFrozen(UUID.fromString(p.seller()))) return new CreateOutcome(Result.FROZEN, null);
			int active = 0;
			for (Auction a : auctions) {
				if (a.isActive() && a.seller.equals(p.seller())) active++;
			}
			if (active >= cfg.auctionMaxActivePerPlayer) return new CreateOutcome(Result.LIMIT_REACHED, null);

			int hours = p.hours() <= 0 ? cfg.auctionDefaultHours : p.hours();
			long nowMs = now.getAsLong();
			String id = newId();

			long fee;
			try {
				long raw = Math.multiplyExact(p.startPrice(), (long) cfg.auctionListFeePercent);
				fee = raw / 100L + (raw % 100L == 0 ? 0 : 1);
			} catch (ArithmeticException e) {
				fee = EconomyManager.MAX_MONEY;
			}
			if (fee > 0) {
				TxResult r = economy.withdraw(UUID.fromString(p.seller()), fee, "auction.listfee:" + id, id);
				if (r == TxResult.INSUFFICIENT) return new CreateOutcome(Result.INSUFFICIENT, null);
				if (r == TxResult.FROZEN) return new CreateOutcome(Result.FROZEN, null);
				if (r != TxResult.OK) return new CreateOutcome(Result.IO_ERROR, null);
			}

			Auction a = new Auction();
			a.id = id;
			a.seller = p.seller();
			a.sellerName = p.sellerName();
			a.itemJson = p.itemJson();
			a.itemId = p.itemId();
			a.count = p.count();
			a.label = p.label();
			a.startPrice = p.startPrice();
			a.buyout = p.buyout();
			a.highBid = 0;
			a.createdAt = nowMs;
			a.endsAt = nowMs + hours * 3_600_000L;
			a.status = ACTIVE;
			auctions.add(a);
			save();
			return new CreateOutcome(Result.OK, id);
		}
	}

	/** Refunds a listing fee when the item could not be taken from the player. */
	public void refundListingFee(String sellerUuid, String auctionId, long fee) {
		if (fee <= 0) return;
		economy.deposit(UUID.fromString(sellerUuid), fee, "auction.cancel:" + auctionId, auctionId);
	}

	private String newId() {
		idCounter++;
		return "au_" + Long.toString(now.getAsLong(), 36) + "_" + Integer.toString(idCounter, 36);
	}

	// ---- bids ---------------------------------------------------------------

	public BidOutcome bid(UUID bidder, String bidderName, String auctionId, long amount) {
		synchronized (Market.LOCK) {
			if (disabled) return new BidOutcome(Result.DISABLED, 0, 0, 0);
			Auction a = byId(auctionId);
			if (a == null) return new BidOutcome(Result.NOT_FOUND, 0, 0, 0);
			long nowMs = now.getAsLong();
			if (!a.isActive() || nowMs >= a.endsAt) {
				return new BidOutcome(Result.CLOSED, 0, a.highBid, a.endsAt);
			}
			if (a.seller.equals(bidder.toString())) return new BidOutcome(Result.OWN_AUCTION, 0, a.highBid, a.endsAt);
			if (a.isCellAuction() && isCellOwner(a.cellId, bidder)) {
				return new BidOutcome(Result.ALREADY_OWNER, 0, a.highBid, a.endsAt);
			}
			if (economy.isFrozen(bidder)) return new BidOutcome(Result.FROZEN, 0, a.highBid, a.endsAt);
			if (a.highBidder != null && a.highBidder.equals(bidder.toString())) {
				return new BidOutcome(Result.ALREADY_HIGHEST, 0, a.highBid, a.endsAt);
			}
			long min = minBid(a);
			if (amount < min) return new BidOutcome(Result.BID_TOO_LOW, min, a.highBid, a.endsAt);
			if (a.buyout != null && amount >= a.buyout) {
				return new BidOutcome(Result.USE_BUYOUT, min, a.highBid, a.endsAt);
			}
			if (amount > EconomyManager.MAX_MONEY) return new BidOutcome(Result.BAD_REQUEST, min, a.highBid, a.endsAt);

			List<Move> moves = new ArrayList<>();
			if (a.highBidder != null) {
				moves.add(new Move(Acct.escrow(a.id), Acct.player(UUID.fromString(a.highBidder)),
						a.highBid, "auction.refund:" + a.id, a.id));
			}
			moves.add(new Move(Acct.player(bidder), Acct.escrow(a.id), amount, "auction.bid:" + a.id, a.id));
			TxResult r = economy.apply(moves, false);
			if (r == TxResult.INSUFFICIENT) return new BidOutcome(Result.INSUFFICIENT, min, a.highBid, a.endsAt);
			if (r == TxResult.FROZEN) return new BidOutcome(Result.FROZEN, min, a.highBid, a.endsAt);
			if (r != TxResult.OK) return new BidOutcome(Result.IO_ERROR, min, a.highBid, a.endsAt);

			a.highBid = amount;
			a.highBidder = bidder.toString();
			a.highBidderName = bidderName;
			long antiSnipe = cfg.auctionAntiSnipeSeconds * 1000L;
			if (antiSnipe > 0 && a.endsAt - nowMs < antiSnipe) {
				a.endsAt = nowMs + antiSnipe;
			}
			save();
			return new BidOutcome(Result.OK, min, a.highBid, a.endsAt);
		}
	}

	// ---- buyout -------------------------------------------------------------

	public Result buyout(UUID buyer, String buyerName, String auctionId) {
		synchronized (Market.LOCK) {
			if (disabled) return Result.DISABLED;
			Auction a = byId(auctionId);
			if (a == null) return Result.NOT_FOUND;
			long nowMs = now.getAsLong();
			if (!a.isActive() || nowMs >= a.endsAt) return Result.CLOSED;
			if (a.buyout == null) return Result.BAD_REQUEST;
			if (a.seller.equals(buyer.toString())) return Result.OWN_AUCTION;
			if (a.isCellAuction() && isCellOwner(a.cellId, buyer)) return Result.ALREADY_OWNER;
			if (economy.isFrozen(buyer)) return Result.FROZEN;

			long price = a.buyout;
			long fee = feeOf(price);
			List<Move> moves = new ArrayList<>();
			if (a.highBidder != null) {
				moves.add(new Move(Acct.escrow(a.id), Acct.player(UUID.fromString(a.highBidder)),
						a.highBid, "auction.refund:" + a.id, a.id));
			}
			moves.add(new Move(Acct.player(buyer), Acct.escrow(a.id), price, "auction.buyout:" + a.id, a.id));
			if (a.serverOwned) {
				moves.add(new Move(Acct.escrow(a.id), Acct.SYSTEM, price, "auction.server_sale:" + a.id, a.id));
			} else {
				moves.add(new Move(Acct.escrow(a.id), Acct.player(UUID.fromString(a.seller)),
						price - fee, "auction.sale:" + a.id, a.id));
				moves.add(new Move(Acct.escrow(a.id), Acct.SYSTEM, fee, "auction.fee:" + a.id, a.id));
			}
			TxResult r = economy.apply(moves, false);
			if (r == TxResult.INSUFFICIENT) return Result.INSUFFICIENT;
			if (r == TxResult.FROZEN) return Result.FROZEN;
			if (r != TxResult.OK) return Result.IO_ERROR;

			a.highBid = price;
			a.highBidder = buyer.toString();
			a.highBidderName = buyerName;
			a.status = SOLD;
			a.paidOut = true;
			a.winner = buyer.toString();
			a.finalPrice = price;
			a.closedAt = nowMs;
			deliverWinner(a);
			save();
			return Result.OK;
		}
	}

	// ---- cancel -------------------------------------------------------------

	public Result cancel(UUID actor, boolean admin, String auctionId) {
		synchronized (Market.LOCK) {
			if (disabled) return Result.DISABLED;
			Auction a = byId(auctionId);
			if (a == null) return Result.NOT_FOUND;
			if (!a.isActive()) return Result.CLOSED;
			if (!admin && !a.seller.equals(actor.toString())) return Result.FORBIDDEN;
			if (a.highBidder != null) return Result.HAS_BIDS;

			a.status = CANCELLED;
			a.closedAt = now.getAsLong();
			deliverSeller(a);
			save();
			return Result.OK;
		}
	}

	/**
	 * Admin cancel of an ACTIVE auction (PHASES.md 7.3): refund the leading bid
	 * from escrow, return the item to the seller's mailbox, mark CANCELLED.
	 * Repeat calls are safe: money is refunded only while the auction is ACTIVE.
	 */
	public Result adminCancel(String auctionId) {
		synchronized (Market.LOCK) {
			if (disabled) return Result.DISABLED;
			Auction a = byId(auctionId);
			if (a == null) return Result.NOT_FOUND;
			if (!a.isActive()) return Result.CLOSED;

			if (a.highBidder != null && a.highBid > 0) {
				TxResult r = economy.apply(List.of(new Move(Acct.escrow(a.id),
						Acct.player(UUID.fromString(a.highBidder)), a.highBid,
						"auction.refund:" + a.id, a.id)), true);
				if (r != TxResult.OK) return Result.IO_ERROR;
			}
			a.highBid = 0;
			a.status = CANCELLED;
			a.closedAt = now.getAsLong();
			if (!a.itemDelivered) {
				if (deliverSeller(a)) a.itemDelivered = true;
			}
			save();
			return Result.OK;
		}
	}

	// ---- settling -----------------------------------------------------------

	/** Called from the server tick and at startup. Idempotent. */
	public void closeExpired(long nowMs) {
		synchronized (Market.LOCK) {
			if (disabled) return;
			for (Auction a : List.copyOf(auctions)) {
				if (a.isActive()) {
					if (nowMs >= a.endsAt) settle(a, nowMs);
				} else if (!a.itemDelivered) {
					retryDelivery(a);
				}
			}
			if (nowMs - lastPruneMs > 3_600_000L) {
				lastPruneMs = nowMs;
				if (pruneOld(nowMs)) save();
			}
		}
	}

	/** Settles one ended auction; every step checks its flag first (6.4). */
	void settle(Auction a, long nowMs) {
		if (!a.isActive()) {
			if (!a.itemDelivered) retryDelivery(a);
			return;
		}
		if (nowMs < a.endsAt) return;

		if (a.highBidder != null) {
			a.winner = a.highBidder;
			a.finalPrice = a.highBid;
			if (!a.paidOut) {
				long fee = feeOf(a.highBid);
				List<Move> payout = a.serverOwned
						? List.of(new Move(Acct.escrow(a.id), Acct.SYSTEM, a.highBid,
								"auction.server_sale:" + a.id, a.id))
						: List.of(
								new Move(Acct.escrow(a.id), Acct.player(UUID.fromString(a.seller)),
										a.highBid - fee, "auction.sale:" + a.id, a.id),
								new Move(Acct.escrow(a.id), Acct.SYSTEM, fee, "auction.fee:" + a.id, a.id));
				TxResult r = economy.apply(payout, true);
				if (r != TxResult.OK) {
					ClaimMap.LOGGER.error("[claimmap] Settle payment failed for {} ({}); retrying next tick",
							a.id, r);
					return;
				}
				a.paidOut = true;
				save();
			}
			// Status becomes SOLD once money is settled so the escrow invariant
			// (ACTIVE == highBid) still holds if we crash before delivery.
			a.status = SOLD;
			a.closedAt = nowMs;
			save();
			if (!a.itemDelivered) {
				if (!deliverWinner(a)) return;   // mailbox full: retry later, never mark delivered
				a.itemDelivered = true;
				save();
			}
			ClaimMap.LOGGER.info("[claimmap] Auction {} sold to {} for {}", a.id, a.highBidderName, a.highBid);
		} else {
			if (!a.itemDelivered) {
				if (!deliverSeller(a)) return;
				a.itemDelivered = true;
			}
			a.status = EXPIRED;
			a.closedAt = nowMs;
			save();
		}
	}

	private boolean isCellOwner(String cellId, UUID player) {
		RegionManager rm = regions;
		if (rm == null || cellId == null) return false;
		RegionManager.CellData cell = rm.get(cellId);
		return cell != null && player.toString().equals(cell.ownerUuid);
	}

	private boolean deliverWinner(Auction a) {
		if (a.isCellAuction()) {
			RegionManager rm = regions;
			if (rm == null || a.winner == null) return false;
			String winnerName = a.highBidderName == null ? "ผู้ชนะ" : a.highBidderName;
			return rm.assign(a.cellId, a.winner, winnerName);
		}
		String id = "mb_au_" + a.id + "_winner";
		if (mailbox.exists(UUID.fromString(a.winner), id)) return true;
		return mailbox.add(UUID.fromString(a.winner), new MailboxManager.MailEntry(
				id, a.itemJson, a.itemId, a.count, a.label, "auction:" + a.id, now.getAsLong()));
	}

	private boolean deliverSeller(Auction a) {
		if (a.isCellAuction()) return true;
		if (a.serverOwned) {
			ConsignmentManager store = consignments;
			if (store == null) return false;
			return store.putReturned(a.itemJson, a.itemId, a.count, a.label, a.id);
		}
		String id = "mb_au_" + a.id + "_return";
		if (mailbox.exists(UUID.fromString(a.seller), id)) return true;
		return mailbox.add(UUID.fromString(a.seller), new MailboxManager.MailEntry(
				id, a.itemJson, a.itemId, a.count, a.label, "auction:" + a.id, now.getAsLong()));
	}

	private void retryDelivery(Auction a) {
		boolean ok;
		if (SOLD.equals(a.status)) {
			ok = a.winner != null && deliverWinner(a);
		} else {
			ok = deliverSeller(a);
		}
		if (ok) {
			a.itemDelivered = true;
			save();
		}
	}

	private long feeOf(long price) {
		try {
			return Math.multiplyExact(price, (long) cfg.auctionFeePercent) / 100L;
		} catch (ArithmeticException e) {
			return price / 100L;
		}
	}

	private Auction byId(String id) {
		if (id == null) return null;
		for (Auction a : auctions) {
			if (a.id.equals(id)) return a;
		}
		return null;
	}

	/** Drops closed auctions after 7 days of history. */
	private boolean pruneOld(long nowMs) {
		return auctions.removeIf(a -> !a.isActive() && a.closedAt != null
				&& nowMs - a.closedAt > HISTORY_KEEP_MS);
	}

	// ---- views --------------------------------------------------------------

	/** Closed auctions of one seller from the last 7 days, newest first. */
	public List<Auction> recentForSeller(String sellerUuid) {
		long cutoff = now.getAsLong() - HISTORY_KEEP_MS;
		List<Auction> out = new ArrayList<>();
		synchronized (Market.LOCK) {
			for (Auction a : auctions) {
				if (!a.seller.equals(sellerUuid)) continue;
				if (a.isActive()) continue;
				if (a.closedAt != null && a.closedAt < cutoff) continue;
				out.add(a);
			}
		}
		out.sort(Comparator.comparingLong((Auction a) -> a.closedAt == null ? 0 : a.closedAt).reversed());
		return out;
	}
}
