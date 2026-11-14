package studio.timeandtime.claimmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Every admin action in one place, shared by {@code /api/admin/*} and the
 * in-game {@code /eco} command (PHASES.md 7). Each call writes one audit line,
 * whether it succeeded or was rejected.
 */
public final class AdminService {
	public enum Result { OK, BAD_REQUEST, NO_ACCOUNT, INSUFFICIENT, NOT_FOUND, DISABLED, IO_ERROR }

	public record MoneyOutcome(Result result, long balance) {
	}

	public record PriceOutcome(Result result, long base) {
	}

	private final ClaimMapConfig config;
	private final EconomyManager economy;
	private final AccountManager accounts;
	private final LinkManager links;
	private final PriceService prices;
	private final AuctionManager auctions;
	private final MailboxManager mailbox;
	private final ShopManager shop;
	private final AuditLog audit;
	private final Predicate<UUID> online;

	public AdminService(ClaimMapConfig config, EconomyManager economy, AccountManager accounts, LinkManager links,
			PriceService prices, AuctionManager auctions, MailboxManager mailbox, ShopManager shop,
			AuditLog audit, Predicate<UUID> online) {
		this.config = config;
		this.economy = economy;
		this.accounts = accounts;
		this.links = links;
		this.prices = prices;
		this.auctions = auctions;
		this.mailbox = mailbox;
		this.shop = shop;
		this.audit = audit;
		this.online = online;
	}

	/** A reason is required for every state-changing action: 3–200 chars. */
	public static boolean validReason(String reason) {
		if (reason == null) return false;
		String r = reason.trim();
		return r.length() >= 3 && r.length() <= 200 && r.indexOf('\n') < 0 && r.indexOf('\r') < 0;
	}

	// ---- money --------------------------------------------------------------

	public MoneyOutcome money(String actor, String ip, String playerName, String op, long amount, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("player", playerName);
		details.put("op", op);
		details.put("amount", amount);
		details.put("reason", reason);
		if (!validReason(reason) || op == null
				|| !(op.equals("give") || op.equals("take") || op.equals("set"))
				|| amount < 0 || amount > EconomyManager.MAX_MONEY) {
			audit.write(actor, "money." + op, details, ip, false);
			return new MoneyOutcome(Result.BAD_REQUEST, 0);
		}
		Optional<UUID> target = accounts.anyUuidOf(playerName);
		if (target.isEmpty()) {
			audit.write(actor, "money." + op, details, ip, false);
			return new MoneyOutcome(Result.NO_ACCOUNT, 0);
		}
		if (economy.isDisabled()) {
			audit.write(actor, "money." + op, details, ip, false);
			return new MoneyOutcome(Result.DISABLED, 0);
		}
		UUID uuid = target.get();
		economy.ensureAccount(uuid);
		String ref = "admin:" + (actor == null ? "CONSOLE" : actor);

		TxResult r;
		switch (op) {
			case "give" -> {
				if (amount < 1) r = TxResult.INVALID_AMOUNT;
				else r = economy.apply(List.of(new Move(Acct.SYSTEM, Acct.player(uuid), amount, "admin.give", ref)), true);
			}
			case "take" -> {
				if (amount < 1) r = TxResult.INVALID_AMOUNT;
				else r = economy.apply(List.of(new Move(Acct.player(uuid), Acct.SYSTEM, amount, "admin.take", ref)), true);
			}
			default -> {
				if (amount < 0) {
					r = TxResult.INVALID_AMOUNT;
				} else {
					long delta = amount - economy.balance(uuid);
					if (delta > 0) {
						r = economy.apply(List.of(new Move(Acct.SYSTEM, Acct.player(uuid), delta, "admin.set", ref)), true);
					} else if (delta < 0) {
						r = economy.apply(List.of(new Move(Acct.player(uuid), Acct.SYSTEM, -delta, "admin.set", ref)), true);
					} else {
						r = TxResult.OK;
					}
				}
			}
		}
		long balance = economy.balance(uuid);
		Result out = switch (r) {
			case OK -> Result.OK;
			case INSUFFICIENT -> Result.INSUFFICIENT;
			case INVALID_AMOUNT -> Result.BAD_REQUEST;
			case IO_ERROR, OVERFLOW -> Result.IO_ERROR;
			case FROZEN -> Result.IO_ERROR;
			case NO_ACCOUNT -> Result.NO_ACCOUNT;
			case SAME_ACCOUNT -> Result.BAD_REQUEST;
		};
		details.put("balance", balance);
		audit.write(actor, "money." + op, details, ip, out == Result.OK);
		return new MoneyOutcome(out, balance);
	}

	// ---- accounts -----------------------------------------------------------

	public Result freeze(String actor, String ip, String playerName, boolean frozen, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("player", playerName);
		details.put("frozen", frozen);
		details.put("reason", reason);
		if (!validReason(reason)) {
			audit.write(actor, "account.freeze", details, ip, false);
			return Result.BAD_REQUEST;
		}
		Optional<UUID> target = accounts.anyUuidOf(playerName);
		if (target.isEmpty()) {
			audit.write(actor, "account.freeze", details, ip, false);
			return Result.NO_ACCOUNT;
		}
		economy.setFrozen(target.get(), frozen);
		audit.write(actor, "account.freeze", details, ip, true);
		return Result.OK;
	}

	public Result resetPassword(String actor, String ip, String playerName, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("player", playerName);
		details.put("reason", reason);
		if (!validReason(reason)) {
			audit.write(actor, "account.reset_password", details, ip, false);
			return Result.BAD_REQUEST;
		}
		Optional<UUID> target = accounts.anyUuidOf(playerName);
		if (target.isEmpty()) {
			audit.write(actor, "account.reset_password", details, ip, false);
			return Result.NO_ACCOUNT;
		}
		boolean ok = accounts.resetPassword(target.get().toString());
		links.revokeAllForUuid(target.get().toString());
		audit.write(actor, "account.reset_password", details, ip, ok);
		return ok ? Result.OK : Result.NOT_FOUND;
	}

	// ---- prices -------------------------------------------------------------

	public PriceOutcome overridePrice(String actor, String ip, String item, Long base, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("item", item);
		details.put("base", base);
		details.put("reason", reason);
		String id = PriceService.normalize(item);
		if (!validReason(reason) || id == null || id.isBlank()
				|| (base != null && (base < 1 || base > EconomyManager.MAX_MONEY))) {
			audit.write(actor, "prices.override", details, ip, false);
			return new PriceOutcome(Result.BAD_REQUEST, 0);
		}
		prices.setOverride(id, base);
		audit.write(actor, "prices.override", details, ip, true);
		return new PriceOutcome(Result.OK, base == null ? 0 : base);
	}

	public Result blockItem(String actor, String ip, String item, boolean blocked, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("item", item);
		details.put("blocked", blocked);
		details.put("reason", reason);
		String id = PriceService.normalize(item);
		if (!validReason(reason) || id == null || id.isBlank()) {
			audit.write(actor, "prices.block", details, ip, false);
			return Result.BAD_REQUEST;
		}
		prices.setBlocked(id, blocked);
		audit.write(actor, "prices.block", details, ip, true);
		return Result.OK;
	}

	/** Returns true when a refresh actually succeeded. */
	public boolean refreshPrices(String actor, String ip) {
		boolean ok = prices.refreshNow();
		audit.write(actor, "prices.refresh", Map.of("ok", ok), ip, true);
		return ok;
	}

	// ---- auctions -----------------------------------------------------------

	public Result cancelAuction(String actor, String ip, String auctionId, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("auction", auctionId);
		details.put("reason", reason);
		if (!validReason(reason)) {
			audit.write(actor, "auction.admin_cancel", details, ip, false);
			return Result.BAD_REQUEST;
		}
		AuctionManager.Result r = auctions.adminCancel(auctionId);
		Result out = switch (r) {
			case OK -> Result.OK;
			case NOT_FOUND -> Result.NOT_FOUND;
			case CLOSED -> Result.BAD_REQUEST;
			case DISABLED -> Result.DISABLED;
			case FORBIDDEN -> Result.BAD_REQUEST;
			case HAS_BIDS -> Result.BAD_REQUEST;
			case BAD_REQUEST -> Result.BAD_REQUEST;
			default -> Result.IO_ERROR;
		};
		audit.write(actor, "auction.admin_cancel", details, ip, out == Result.OK);
		return out;
	}

	// ---- config -------------------------------------------------------------

	/** Returns false when port/bindAddress were edited and need a restart. */
	public boolean reloadConfig(String actor, String ip) {
		boolean noRestartNeeded = config.reload();
		audit.write(actor, "config.reload", Map.of("restartNeeded", !noRestartNeeded), ip, true);
		return noRestartNeeded;
	}

	// ---- read models --------------------------------------------------------

	public JsonObject overview(String actor, String ip) {
		JsonObject out = new JsonObject();
		JsonArray players = new JsonArray();
		for (AccountManager.Identity id : accounts.identities()) {
			UUID uuid;
			try {
				uuid = UUID.fromString(id.uuid());
			} catch (RuntimeException e) {
				continue;
			}
			JsonObject p = new JsonObject();
			p.addProperty("name", id.name());
			p.addProperty("uuid", id.uuid());
			p.addProperty("balance", economy.balance(uuid));
			p.addProperty("frozen", economy.isFrozen(uuid));
			p.addProperty("online", online.test(uuid));
			p.addProperty("mailboxCount", mailbox.count(uuid));
			players.add(p);
		}
		out.add("players", players);
		long sumPlayers = economy.sumPlayerBalances();
		long escrow = economy.sumEscrow();
		long systemNet = economy.systemNet();
		JsonObject totals = new JsonObject();
		totals.addProperty("balances", sumPlayers);
		totals.addProperty("escrow", escrow);
		totals.addProperty("systemNet", systemNet);
		totals.addProperty("invariantOk", sumPlayers + escrow + systemNet == 0);
		out.add("totals", totals);
		JsonObject market = new JsonObject();
		market.addProperty("economyDisabled", economy.isDisabled());
		market.addProperty("shopDisabled", shop.isDisabled());
		market.addProperty("auctionsDisabled", auctions.isDisabled());
		market.addProperty("pricesStale", prices.staleForApi());
		market.addProperty("pricesUpdatedAt", prices.updatedAt());
		market.addProperty("currency", config.currencyName);
		out.add("market", market);
		audit.write(actor, "admin.overview", Map.of(), ip, true);
		return out;
	}

	public JsonArray auctionsForAdmin(String actor, String ip) {
		JsonArray arr = new JsonArray();
		for (AuctionManager.Auction a : auctions.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", a.id);
			o.addProperty("seller", a.seller);
			o.addProperty("sellerName", a.sellerName);
			o.addProperty("itemId", a.itemId);
			o.addProperty("count", a.count);
			o.addProperty("label", a.label);
			o.addProperty("startPrice", a.startPrice);
			o.addProperty("buyout", a.buyout);
			o.addProperty("highBid", a.highBid);
			o.addProperty("highBidder", a.highBidder);
			o.addProperty("highBidderName", a.highBidderName);
			o.addProperty("endsAt", a.endsAt);
			o.addProperty("status", a.status);
			o.addProperty("paidOut", a.paidOut);
			o.addProperty("itemDelivered", a.itemDelivered);
			arr.add(o);
		}
		audit.write(actor, "admin.auctions", Map.of(), ip, true);
		return arr;
	}

	public List<EconomyManager.TxView> wallet(String actor, String ip, String playerName, int limit) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("player", playerName);
		Optional<UUID> target = accounts.anyUuidOf(playerName);
		if (target.isEmpty()) {
			audit.write(actor, "admin.wallet", details, ip, false);
			return List.of();
		}
		int n = Math.max(1, Math.min(200, limit));
		List<EconomyManager.TxView> out = economy.history(target.get(), n);
		audit.write(actor, "admin.wallet", details, ip, true);
		return out;
	}

	public List<String> auditTail(String actor, String ip, int limit) {
		audit.write(actor, "admin.audit", Map.of("limit", limit), ip, true);
		return audit.tail(limit);
	}

	/** Recorded when someone who is not an admin calls an admin endpoint. */
	public void auditDenied(String actor, String ip, String path) {
		audit.write(actor, "admin.denied", Map.of("path", path), ip, false);
	}

	/** True when the name is in the admin list (web sessions only). */
	public boolean isAdminName(String name) {
		return config.isAdmin(name);
	}
}
