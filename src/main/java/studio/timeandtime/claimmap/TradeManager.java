package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Item-for-item trade offers. The offered stack is taken out of the player's
 * inventory/container when the offer is created, and both stacks are delivered
 * through the mailbox when another player accepts.
 */
public final class TradeManager {
	public static final String ACTIVE = "ACTIVE";
	public static final String COMPLETED = "COMPLETED";
	public static final String CANCELLED = "CANCELLED";
	private static final int MAX_ACTIVE_PER_PLAYER = 10;
	private static final long KEEP_HISTORY_MS = 7L * 24 * 60 * 60_000L;
	private static final long OFFER_LIFETIME_MS = 7L * 24 * 60 * 60_000L;

	public enum Result {
		OK, BAD_REQUEST, NOT_FOUND, OWN_OFFER, CLOSED, LIMIT_REACHED, MAILBOX_FULL, IO_ERROR
	}

	public static final class Offer {
		public String id;
		public String owner;
		public String ownerName;
		public String giveJson;
		public String giveItemId;
		public int giveCount;
		public String giveLabel;
		public String wantItemId;
		public int wantCount;
		public String wantLabel;
		public long createdAt;
		public long endsAt;
		public String status = ACTIVE;
		public String taker;
		public String takerName;
		public Long closedAt;

		public boolean isActive() {
			return ACTIVE.equals(status) && endsAt > System.currentTimeMillis();
		}
	}

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private final LongSupplier now;
	private final List<Offer> offers = new ArrayList<>();
	private final Object lock = new Object();
	private long idCounter;

	public TradeManager(Path dir, LongSupplier now) {
		this.file = dir.resolve("trades.json");
		this.now = now;
	}

	public void load() {
		synchronized (lock) {
			offers.clear();
			if (file == null || !Files.exists(file)) return;
			try {
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonElement list = root.get("offers");
				if (list != null && list.isJsonArray()) {
					for (JsonElement e : list.getAsJsonArray()) {
						Offer o = e.isJsonObject() ? gson.fromJson(e, Offer.class) : null;
						if (o != null && o.id != null) offers.add(o);
					}
				}
			} catch (Exception e) {
				ClaimMap.LOGGER.error("[claimmap] Could not load trades.json", e);
			}
		}
	}

	public void save() {
		synchronized (lock) {
			try {
				Path parent = file.getParent();
				if (parent != null) Files.createDirectories(parent);
				JsonObject root = new JsonObject();
				JsonArray arr = new JsonArray();
				for (Offer o : offers) arr.add(gson.toJsonTree(o));
				root.add("offers", arr);
				Path tmp = file.resolveSibling("trades.json.tmp");
				Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[claimmap] Could not save trades.json", e);
			}
		}
	}

	public List<Offer> active() {
		long t = now.getAsLong();
		List<Offer> out = new ArrayList<>();
		synchronized (lock) {
			for (Offer o : offers) {
				if (ACTIVE.equals(o.status) && o.endsAt > t) out.add(o);
			}
		}
		return out;
	}

	public List<Offer> recentFor(String uuid) {
		long cutoff = now.getAsLong() - KEEP_HISTORY_MS;
		List<Offer> out = new ArrayList<>();
		synchronized (lock) {
			for (Offer o : offers) {
				if (o.createdAt >= cutoff && (uuid.equals(o.owner) || uuid.equals(o.taker))) out.add(o);
			}
		}
		return out;
	}

	public Offer find(String id) {
		synchronized (lock) {
			for (Offer o : offers) if (o.id.equals(id)) return o;
			return null;
		}
	}

	public Result create(String owner, String ownerName, String giveJson, String giveItemId, int giveCount,
			String giveLabel, String wantItemId, int wantCount, String wantLabel) {
		if (owner == null || ownerName == null || giveJson == null || giveItemId == null
				|| wantItemId == null || giveCount < 1 || wantCount < 1) {
			return Result.BAD_REQUEST;
		}
		synchronized (lock) {
			int active = 0;
			for (Offer o : offers) {
				if (owner.equals(o.owner) && ACTIVE.equals(o.status) && o.endsAt > now.getAsLong()) active++;
			}
			if (active >= MAX_ACTIVE_PER_PLAYER) return Result.LIMIT_REACHED;
			long t = now.getAsLong();
			Offer o = new Offer();
			o.id = "tr_" + Long.toString(t, 36) + "_" + Long.toString(++idCounter, 36);
			o.owner = owner;
			o.ownerName = ownerName;
			o.giveJson = giveJson;
			o.giveItemId = giveItemId;
			o.giveCount = giveCount;
			o.giveLabel = giveLabel;
			o.wantItemId = wantItemId;
			o.wantCount = wantCount;
			o.wantLabel = wantLabel;
			o.createdAt = t;
			o.endsAt = t + OFFER_LIFETIME_MS;
			offers.add(o);
			save();
			return Result.OK;
		}
	}

	/** Adds both escrow stacks to mailboxes and closes the offer. */
	public Result accept(String id, String taker, String takerName, String paymentJson,
			String paymentItemId, int paymentCount, String paymentLabel, MailboxManager mailbox) {
		synchronized (lock) {
			Offer o = find(id);
			if (o == null) return Result.NOT_FOUND;
			if (!ACTIVE.equals(o.status) || o.endsAt <= now.getAsLong()) return Result.CLOSED;
			if (o.owner.equals(taker)) return Result.OWN_OFFER;
			UUID ownerId = parseUuid(o.owner);
			UUID takerId = parseUuid(taker);
			if (ownerId == null || takerId == null) return Result.BAD_REQUEST;
			if (!mailbox.hasCapacity(ownerId, 1) || !mailbox.hasCapacity(takerId, 1)) return Result.MAILBOX_FULL;

			String giveMail = "trade:" + o.id;
			String payMail = "trade:" + o.id + ":pay";
			boolean giveAdded = mailbox.add(takerId, new MailboxManager.MailEntry(giveMail, o.giveJson,
					o.giveItemId, o.giveCount, o.giveLabel, o.ownerName, now.getAsLong()));
			if (!giveAdded) return Result.IO_ERROR;
			boolean payAdded = mailbox.add(ownerId, new MailboxManager.MailEntry(payMail, paymentJson,
					paymentItemId, paymentCount, paymentLabel, takerName, now.getAsLong()));
			if (!payAdded) {
				mailbox.remove(takerId, giveMail);
				return Result.IO_ERROR;
			}
			o.status = COMPLETED;
			o.taker = taker;
			o.takerName = takerName;
			o.closedAt = now.getAsLong();
			save();
			return Result.OK;
		}
	}

	public Result cancel(String id, String actor, boolean admin, MailboxManager mailbox) {
		synchronized (lock) {
			Offer o = find(id);
			if (o == null) return Result.NOT_FOUND;
			// An expired offer may still be cancelled; that is how the owner gets
			// the escrowed stack back.
			if (!ACTIVE.equals(o.status)) return Result.CLOSED;
			if (!admin && !o.owner.equals(actor)) return Result.BAD_REQUEST;
			UUID ownerId = parseUuid(o.owner);
			if (ownerId == null) return Result.BAD_REQUEST;
			if (!mailbox.hasCapacity(ownerId, 1)) return Result.MAILBOX_FULL;
			String mail = "trade-cancel:" + o.id;
			if (!mailbox.add(ownerId, new MailboxManager.MailEntry(mail, o.giveJson, o.giveItemId,
					o.giveCount, o.giveLabel, "เทรด", now.getAsLong()))) {
				return Result.IO_ERROR;
			}
			o.status = CANCELLED;
			o.closedAt = now.getAsLong();
			save();
			return Result.OK;
		}
	}

	private static UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
