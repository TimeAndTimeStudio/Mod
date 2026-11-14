package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Player balances, escrow pots and the append-only ledger (PHASES.md phase 2).
 *
 * <p>The snapshot {@code economy.json} is a cache; {@code ledger.jsonl} is the
 * truth. A crash between the two is repaired by replaying ledger rows whose
 * {@code seq} is greater than the snapshot's {@code lastSeq}.
 *
 * <p>Does not depend on any Minecraft class (R9) so it is unit-testable.
 */
public final class EconomyManager {
	public static final long MAX_MONEY = 1_000_000_000_000L;       // per transaction
	public static final long MAX_BALANCE = 1_000_000_000_000_000L; // per account ceiling
	public static final int HISTORY_PER_PLAYER = 50;

	public record TxView(long seq, long t, String id, String dir, Acct other, long amount, String reason, String ref) {
	}

	private final Path dir;
	private final Path economyFile;
	private final Path ledgerFile;
	private final Path backupsDir;
	private final ClaimMapConfig cfg;
	private final LongSupplier now;
	private final Gson gson = new GsonBuilder().create();
	private static final AtomicLong BATCH_COUNTER = new AtomicLong();

	private final Map<UUID, Long> balances = new HashMap<>();
	private final Map<String, Long> escrow = new HashMap<>();
	private final Set<UUID> frozen = new HashSet<>();
	private final Map<UUID, ArrayDeque<TxView>> history = new HashMap<>();
	private long systemNet;
	private long lastSeq;
	private boolean disabled;

	public EconomyManager(Path dir, ClaimMapConfig cfg, LongSupplier now) {
		this.dir = dir;
		this.cfg = cfg;
		this.now = now;
		this.economyFile = dir.resolve("economy.json");
		this.ledgerFile = dir.resolve("ledger.jsonl");
		this.backupsDir = dir.resolve("backups");
	}

	// ---- loading ------------------------------------------------------------

	public void load() {
		synchronized (Market.LOCK) {
			balances.clear();
			escrow.clear();
			frozen.clear();
			history.clear();
			systemNet = 0;
			lastSeq = 0;
			disabled = false;

			if (!readSnapshot()) return;      // disabled already set on corruption
			if (!replayLedger()) return;
			if (!checkInvariant()) {
				corrupt(economyFile, "ledger replay broke the money invariant");
				return;
			}
			ClaimMap.LOGGER.info("[claimmap] Economy loaded: {} accounts, {} escrow pots, lastSeq={}, systemNet={}",
					balances.size(), escrow.size(), lastSeq, systemNet);
		}
	}

	private boolean readSnapshot() {
		if (!Files.exists(economyFile)) return true;
		try {
			String json = Files.readString(economyFile, StandardCharsets.UTF_8);
			JsonObject root = gson.fromJson(json, JsonObject.class);
			if (root == null) throw new IllegalStateException("empty snapshot");
			lastSeq = root.get("lastSeq").getAsLong();
			JsonObject b = root.getAsJsonObject("balances");
			if (b != null) {
				for (Map.Entry<String, JsonElement> e : b.entrySet()) {
					UUID u = UUID.fromString(e.getKey());
					long v = e.getValue().getAsLong();
					if (v < 0 || v > MAX_BALANCE) throw new IllegalStateException("bad balance");
					balances.put(u, v);
				}
			}
			JsonObject eo = root.getAsJsonObject("escrow");
			if (eo != null) {
				for (Map.Entry<String, JsonElement> e : eo.entrySet()) {
					long v = e.getValue().getAsLong();
					if (v < 0 || v > MAX_BALANCE) throw new IllegalStateException("bad escrow");
					escrow.put(e.getKey(), v);
				}
			}
			JsonArray f = root.getAsJsonArray("frozen");
			if (f != null) {
				for (JsonElement e : f) frozen.add(UUID.fromString(e.getAsString()));
			}
			systemNet = root.has("systemNet") ? root.get("systemNet").getAsLong() : 0L;
			return true;
		} catch (Exception e) {
			corrupt(economyFile, "cannot read economy.json: " + e.getMessage());
			return false;
		}
	}

	private boolean replayLedger() {
		if (!Files.exists(ledgerFile)) return true;
		List<String> lines;
		try {
			lines = Files.readAllLines(ledgerFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			corrupt(ledgerFile, "cannot read ledger.jsonl: " + e.getMessage());
			return false;
		}
		int lastNonEmpty = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).isBlank()) lastNonEmpty = i;
		}
		long maxSeq = lastSeq;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isBlank()) continue;
			JsonObject o;
			try {
				o = gson.fromJson(line, JsonObject.class);
				if (o == null) throw new IllegalStateException("null row");
			} catch (Exception e) {
				if (i == lastNonEmpty) {
					// Torn write from a crash: ignore the incomplete tail (R5/2.5).
					ClaimMap.LOGGER.warn("[claimmap] Ignoring incomplete last ledger line ({} bytes)", line.length());
					continue;
				}
				corrupt(ledgerFile, "damaged ledger row at line " + (i + 1));
				return false;
			}
			try {
				long seq = o.get("seq").getAsLong();
				long t = o.has("t") ? o.get("t").getAsLong() : 0L;
				Acct from = Acct.parse(o.get("from").getAsString());
				Acct to = Acct.parse(o.get("to").getAsString());
				long amount = o.get("amount").getAsLong();
				String reason = o.has("reason") ? o.get("reason").getAsString() : "";
				String ref = o.has("ref") ? o.get("ref").getAsString() : "";
				if (from == null || to == null || amount < 1 || amount > MAX_MONEY) {
					throw new IllegalStateException("bad row values");
				}
				if (seq > lastSeq) {
					applyLoadDelta(from, to, amount);
					lastSeq = seq;
				}
				if (seq > maxSeq) maxSeq = seq;
				indexHistory(seq, t, from, to, amount, reason, ref);
			} catch (Exception e) {
				corrupt(ledgerFile, "invalid ledger row at line " + (i + 1) + ": " + e.getMessage());
				return false;
			}
		}
		lastSeq = Math.max(lastSeq, maxSeq);
		for (Map.Entry<UUID, Long> e : balances.entrySet()) {
			if (e.getValue() < 0) {
				corrupt(ledgerFile, "negative balance after replay for " + e.getKey());
				return false;
			}
		}
		for (Map.Entry<String, Long> e : escrow.entrySet()) {
			if (e.getValue() < 0) {
				corrupt(ledgerFile, "negative escrow after replay for " + e.getKey());
				return false;
			}
		}
		return true;
	}

	private void applyLoadDelta(Acct from, Acct to, long amount) {
		if (from.isPlayer()) {
			balances.merge(from.uuid(), -amount, Long::sum);
		} else if (from.isEscrow()) {
			escrow.merge(from.id(), -amount, Long::sum);
		} else {
			systemNet -= amount;
		}
		if (to.isPlayer()) {
			balances.merge(to.uuid(), amount, Long::sum);
		} else if (to.isEscrow()) {
			escrow.merge(to.id(), amount, Long::sum);
		} else {
			systemNet += amount;
		}
	}

	private boolean checkInvariant() {
		long sum = 0;
		try {
			for (long v : balances.values()) sum = Math.addExact(sum, v);
			for (long v : escrow.values()) sum = Math.addExact(sum, v);
			sum = Math.addExact(sum, systemNet);
		} catch (ArithmeticException e) {
			return false;
		}
		if (sum != 0) {
			ClaimMap.LOGGER.error("[claimmap] Money invariant broken: players+escrow+systemNet = {}", sum);
			return false;
		}
		return true;
	}

	private void corrupt(Path file, String why) {
		disabled = true;
		ClaimMap.LOGGER.error("[claimmap] MARKET DISABLED — {}: {}", file.getFileName(), why);
		try {
			Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + now.getAsLong());
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			ClaimMap.LOGGER.error("[claimmap] Moved damaged file to {}", target);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not move damaged {} aside", file.getFileName(), e);
		}
		balances.clear();
		escrow.clear();
		frozen.clear();
		history.clear();
		systemNet = 0;
		lastSeq = 0;
	}

	// ---- queries ------------------------------------------------------------

	public boolean isDisabled() {
		return disabled;
	}

	public long lastSeq() {
		synchronized (Market.LOCK) {
			return lastSeq;
		}
	}

	public long balance(UUID u) {
		if (u == null) return 0;
		synchronized (Market.LOCK) {
			return balances.getOrDefault(u, 0L);
		}
	}

	public boolean hasAccount(UUID u) {
		if (u == null) return false;
		synchronized (Market.LOCK) {
			return balances.containsKey(u);
		}
	}

	public boolean isFrozen(UUID u) {
		if (u == null) return false;
		synchronized (Market.LOCK) {
			return frozen.contains(u);
		}
	}

	public void setFrozen(UUID u, boolean freeze) {
		if (u == null) return;
		synchronized (Market.LOCK) {
			if (disabled) return;
			boolean changed = freeze ? frozen.add(u) : frozen.remove(u);
			if (changed) writeSnapshot();
		}
	}

	/** Snapshot of every player balance (admin overview). */
	public Map<UUID, Long> balancesView() {
		synchronized (Market.LOCK) {
			return Map.copyOf(balances);
		}
	}

	public Set<UUID> frozenView() {
		synchronized (Market.LOCK) {
			return Set.copyOf(frozen);
		}
	}

	public long escrowBalance(String escrowId) {
		if (escrowId == null) return 0;
		synchronized (Market.LOCK) {
			return escrow.getOrDefault(escrowId, 0L);
		}
	}

	public long sumPlayerBalances() {
		synchronized (Market.LOCK) {
			long sum = 0;
			for (long v : balances.values()) sum += v;
			return sum;
		}
	}

	public long sumEscrow() {
		synchronized (Market.LOCK) {
			long sum = 0;
			for (long v : escrow.values()) sum += v;
			return sum;
		}
	}

	public long systemNet() {
		synchronized (Market.LOCK) {
			return systemNet;
		}
	}

	/** Newest first. */
	public List<TxView> history(UUID u, int limit) {
		if (u == null || limit <= 0) return List.of();
		synchronized (Market.LOCK) {
			ArrayDeque<TxView> q = history.get(u);
			if (q == null) return List.of();
			List<TxView> out = new ArrayList<>(Math.min(limit, q.size()));
			for (TxView v : q) {
				if (out.size() >= limit) break;
				out.add(v);
			}
			return out;
		}
	}

	// ---- accounts -----------------------------------------------------------

	/** Creates the account and grants {@code startingBalance}. Idempotent. */
	public boolean ensureAccount(UUID u) {
		if (u == null) return false;
		synchronized (Market.LOCK) {
			if (disabled) return false;
			if (balances.containsKey(u)) return false;
			long start = Math.max(0L, Math.min(MAX_BALANCE, cfg.startingBalance));
			balances.put(u, 0L);
			if (start > 0) {
				TxResult r = apply(List.of(new Move(Acct.SYSTEM, Acct.player(u), start, "signup", "")), true);
				if (r != TxResult.OK) {
					balances.remove(u);
					ClaimMap.LOGGER.error("[claimmap] Could not grant starting balance to {}: {}", u, r);
					return false;
				}
			} else {
				writeSnapshot();
			}
			return true;
		}
	}

	/** Convenience wrappers around {@link #apply}. */
	public TxResult deposit(UUID to, long amount, String reason, String ref) {
		return apply(List.of(new Move(Acct.SYSTEM, Acct.player(to), amount, reason, ref)), false);
	}

	public TxResult withdraw(UUID from, long amount, String reason, String ref) {
		return apply(List.of(new Move(Acct.player(from), Acct.SYSTEM, amount, reason, ref)), false);
	}

	public TxResult transfer(UUID from, UUID to, long amount, String reason, String ref) {
		return apply(List.of(new Move(Acct.player(from), Acct.player(to), amount, reason, ref)), false);
	}

	// ---- the core -----------------------------------------------------------

	/**
	 * Applies the whole batch or nothing at all (2.4). Order matters:
	 * validate → simulate → append ledger → commit memory → snapshot.
	 */
	public TxResult apply(List<Move> moves, boolean bypassFreeze) {
		synchronized (Market.LOCK) {
			if (disabled) return TxResult.IO_ERROR;
			if (moves == null || moves.isEmpty()) return TxResult.OK;

			// 2) per-move validation
			for (Move m : moves) {
				if (m == null || m.from() == null || m.to() == null) return TxResult.INVALID_AMOUNT;
				if (m.amount() < 1 || m.amount() > MAX_MONEY) return TxResult.INVALID_AMOUNT;
				if (m.from().equals(m.to())) return TxResult.SAME_ACCOUNT;
				if (m.from().isPlayer() && !balances.containsKey(m.from().uuid())) return TxResult.NO_ACCOUNT;
				if (m.to().isPlayer() && !balances.containsKey(m.to().uuid())) return TxResult.NO_ACCOUNT;
			}

			// 3) freeze
			if (!bypassFreeze) {
				for (Move m : moves) {
					if (m.from().isPlayer() && frozen.contains(m.from().uuid())) return TxResult.FROZEN;
					if (m.from().isPlayer() && m.to().isPlayer() && frozen.contains(m.to().uuid())) {
						return TxResult.FROZEN;
					}
				}
			}

			// 4) simulate on a delta map
			Map<String, Long> delta = new LinkedHashMap<>();
			long systemDelta = 0;
			for (Move m : moves) {
				String fk = m.from().toString();
				String tk = m.to().toString();
				long fv = delta.getOrDefault(fk, 0L);
				long tv = delta.getOrDefault(tk, 0L);
				try {
					delta.put(fk, Math.subtractExact(fv, m.amount()));
					delta.put(tk, Math.addExact(tv, m.amount()));
				} catch (ArithmeticException e) {
					return TxResult.OVERFLOW;
				}
				if (m.from().isSystem()) {
					try {
						systemDelta = Math.subtractExact(systemDelta, m.amount());
					} catch (ArithmeticException e) {
						return TxResult.OVERFLOW;
					}
				}
				if (m.to().isSystem()) {
					try {
						systemDelta = Math.addExact(systemDelta, m.amount());
					} catch (ArithmeticException e) {
						return TxResult.OVERFLOW;
					}
				}
			}
			for (Move m : moves) {
				for (Acct a : new Acct[]{m.from(), m.to()}) {
					if (a.isSystem()) continue;
					long d = delta.getOrDefault(a.toString(), 0L);
					long base = a.isPlayer() ? balances.getOrDefault(a.uuid(), 0L) : escrow.getOrDefault(a.id(), 0L);
					long next;
					try {
						next = Math.addExact(base, d);
					} catch (ArithmeticException e) {
						return TxResult.OVERFLOW;
					}
					if (next < 0) return TxResult.INSUFFICIENT;
					if (next > MAX_BALANCE) return TxResult.OVERFLOW;
				}
			}
			try {
				Math.addExact(systemNet, systemDelta);
			} catch (ArithmeticException e) {
				return TxResult.OVERFLOW;
			}

			// 5) ledger first — the truth
			long firstSeq = lastSeq + 1;
			String batch = "b_" + firstSeq + "_" + BATCH_COUNTER.incrementAndGet();
			long t = now.getAsLong();
			if (!appendLedger(moves, firstSeq, batch, t)) return TxResult.IO_ERROR;

			// 6) commit to memory
			for (Move m : moves) {
				if (m.from().isPlayer()) balances.merge(m.from().uuid(), -m.amount(), Long::sum);
				else if (m.from().isEscrow()) escrow.merge(m.from().id(), -m.amount(), Long::sum);
				if (m.to().isPlayer()) balances.merge(m.to().uuid(), m.amount(), Long::sum);
				else if (m.to().isEscrow()) escrow.merge(m.to().id(), m.amount(), Long::sum);
			}
			systemNet += systemDelta;
			long seq = firstSeq;
			for (Move m : moves) {
				indexHistory(seq, t, m.from(), m.to(), m.amount(), m.reason(), m.ref());
				seq++;
			}
			lastSeq = seq - 1;

			// 7) snapshot is only a cache; a failure here is logged, not fatal
			if (!writeSnapshot()) {
				ClaimMap.LOGGER.error("[claimmap] Could not write economy.json; the ledger still holds the truth");
			}
			return TxResult.OK;
		}
	}

	private boolean appendLedger(List<Move> moves, long firstSeq, String batch, long t) {
		StringBuilder sb = new StringBuilder();
		long seq = firstSeq;
		for (Move m : moves) {
			JsonObject o = new JsonObject();
			o.addProperty("seq", seq);
			o.addProperty("id", "tx_" + seq);
			o.addProperty("batch", batch);
			o.addProperty("t", t);
			o.addProperty("from", m.from().toString());
			o.addProperty("to", m.to().toString());
			o.addProperty("amount", m.amount());
			o.addProperty("reason", m.reason());
			o.addProperty("ref", m.ref());
			sb.append(gson.toJson(o)).append('\n');
			seq++;
		}
		byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
		try {
			Path parent = ledgerFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			try (FileChannel ch = FileChannel.open(ledgerFile,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
				ByteBuffer buf = ByteBuffer.wrap(bytes);
				while (buf.hasRemaining()) ch.write(buf);
				ch.force(false);
			}
			return true;
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not append to ledger.jsonl", e);
			return false;
		}
	}

	private void indexHistory(long seq, long t, Acct from, Acct to, long amount, String reason, String ref) {
		String id = "tx_" + seq;
		if (from.isPlayer()) pushHistory(from.uuid(), new TxView(seq, t, id, "out", to, amount, reason, ref));
		if (to.isPlayer()) pushHistory(to.uuid(), new TxView(seq, t, id, "in", from, amount, reason, ref));
	}

	private void pushHistory(UUID player, TxView view) {
		ArrayDeque<TxView> q = history.computeIfAbsent(player, k -> new ArrayDeque<>());
		q.addFirst(view);
		while (q.size() > HISTORY_PER_PLAYER) q.removeLast();
	}

	private boolean writeSnapshot() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			root.addProperty("lastSeq", lastSeq);
			JsonObject b = new JsonObject();
			for (Map.Entry<UUID, Long> e : balances.entrySet()) b.addProperty(e.getKey().toString(), e.getValue());
			root.add("balances", b);
			JsonObject eo = new JsonObject();
			for (Map.Entry<String, Long> e : escrow.entrySet()) eo.addProperty(e.getKey(), e.getValue());
			root.add("escrow", eo);
			JsonArray f = new JsonArray();
			for (UUID u : frozen) f.add(u.toString());
			root.add("frozen", f);
			root.addProperty("systemNet", systemNet);

			Path parent = economyFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = economyFile.resolveSibling("economy.json.tmp");
			Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, economyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not write economy.json", e);
			return false;
		}
	}

	// ---- backups ------------------------------------------------------------

	public void startBackups(ScheduledExecutorService ex, long intervalMs) {
		long every = Math.max(1_000L, intervalMs);
		ex.scheduleWithFixedDelay(this::backupNow, every, every, TimeUnit.MILLISECONDS);
	}

	/** Copies the data files into {@code backups/<timestamp>/}, keeping 24 sets. */
	public void backupNow() {
		synchronized (Market.LOCK) {
			try {
				String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
						.withZone(ZoneId.systemDefault())
						.format(Instant.ofEpochMilli(now.getAsLong()));
				Path target = backupsDir.resolve(stamp);
				Files.createDirectories(target);
				for (String name : new String[]{"economy.json", "ledger.jsonl", "accounts.json", "mailbox.json", "auctions.json"}) {
					Path src = dir.resolve(name);
					if (Files.exists(src)) {
						Files.copy(src, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
					}
				}
				pruneBackups(24);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[claimmap] Backup failed", e);
			}
		}
	}

	private void pruneBackups(int keep) throws IOException {
		if (!Files.isDirectory(backupsDir)) return;
		List<Path> dirs = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupsDir)) {
			for (Path p : stream) {
				if (Files.isDirectory(p)) dirs.add(p);
			}
		}
		dirs.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
		for (int i = keep; i < dirs.size(); i++) {
			deleteRecursively(dirs.get(i));
		}
	}

	private static void deleteRecursively(Path p) throws IOException {
		if (Files.isDirectory(p)) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
				for (Path child : stream) deleteRecursively(child);
			}
		}
		Files.deleteIfExists(p);
	}
}
