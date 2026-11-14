package studio.timeandtime.claimmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server (default {@code 0.0.0.0:8085}). Serves the single page
 * web UI at {@code /} plus the JSON API under {@code /api} (PHASES.md E).
 *
 * <p>The master token from the config is a script-only credential: it is never
 * embedded in the served page and only {@code POST /api/command} accepts it.
 * Everything else uses a web session issued by {@code POST /api/login}.
 */
public final class WebServer {
	private final ClaimMapConfig config;
	private final RegionManager regions;
	private final MapRenderer maps;
	private final LinkManager links;
	private final AccountManager accounts;
	private final EconomyManager economy;
	private final LoginThrottle throttle = new LoginThrottle();
	private final Map<String, Cached> idempotent = new ConcurrentHashMap<>();
	private HttpServer server;
	private byte[] webPage;

	private record Cached(JsonObject body, long expiresAt) {
	}

	public WebServer(ClaimMapConfig config, RegionManager regions, MapRenderer maps, LinkManager links,
			AccountManager accounts, EconomyManager economy) {
		this.config = config;
		this.regions = regions;
		this.maps = maps;
		this.links = links;
		this.accounts = accounts;
		this.economy = economy;
	}

	public void start() {
		try {
			server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
			server.createContext("/", this::handle);
			server.setExecutor(Executors.newFixedThreadPool(8));
			server.start();
			ClaimMap.LOGGER.info("[claimmap] HTTP API listening on {}:{}", config.bindAddress, config.port);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not start HTTP API", e);
		}
	}

	public void stop() {
		if (server != null) server.stop(0);
	}

	// ---- routing ------------------------------------------------------------

	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String method = ex.getRequestMethod();
		boolean api = path.startsWith("/api/");
		if (api) {
			Headers h = ex.getResponseHeaders();
			h.set("Cache-Control", "no-store");
			h.set("X-Content-Type-Options", "nosniff");
			h.set("Referrer-Policy", "no-referrer");
		}
		try {
			route(ex, method, path);
		} catch (Json.ApiError e) {
			Json.sendError(ex, e);
		} catch (Exception e) {
			ClaimMap.LOGGER.error("[claimmap] API error {} {}", method, path, e);
			try {
				Json.sendError(ex, 500, "internal_error", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์");
			} catch (IOException ignored) {
				// response already committed
			}
		} finally {
			ex.close();
		}
	}

	private void route(HttpExchange ex, String method, String path) throws IOException {
		switch (path) {
			case "/", "/index.html", "/claim-map.html" -> {
				requireMethod(ex, method, "GET");
				sendWeb(ex);
			}
			case "/api/login" -> {
				requireMethod(ex, method, "POST");
				handleLogin(ex);
			}
			case "/api/me" -> {
				requireMethod(ex, method, "GET");
				handleMe(ex);
			}
			case "/api/link" -> {
				requireMethod(ex, method, "POST");
				handleLink(ex);
			}
			case "/api/command" -> {
				requireMethod(ex, method, "POST");
				handleCommand(ex);
			}
			case "/api/cells" -> {
				requireMethod(ex, method, "GET");
				handleCells(ex);
			}
			case "/api/wallet" -> {
				requireMethod(ex, method, "GET");
				handleWallet(ex);
			}
			case "/api/pay" -> {
				requireMethod(ex, method, "POST");
				handlePay(ex);
			}
			default -> {
				if (path.startsWith("/api/map/")) {
					requireMethod(ex, method, "GET");
					handleMap(ex, path);
				} else if (path.startsWith("/api/cells/")) {
					handleCellAction(ex, method, path);
				} else {
					throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
				}
			}
		}
	}

	private void requireMethod(HttpExchange ex, String actual, String expected) {
		if (!actual.equals(expected)) {
			throw new Json.ApiError(405, "method_not_allowed", "วิธีเรียกไม่ถูกต้อง ต้องใช้ " + expected)
					.header("Allow", expected);
		}
	}

	// ---- auth ---------------------------------------------------------------

	/** Authenticated caller: a web session, or the script-only master token. */
	private record Auth(String name, String uuid, boolean admin, boolean master, LinkManager.Session session) {
	}

	private String bearer(HttpExchange ex) {
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		if (auth == null) return null;
		String[] parts = auth.trim().split("\\s+");
		if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) return null;
		return parts[1];
	}

	private boolean isMaster(String token) {
		if (token == null || config.token == null || config.token.isEmpty()) return false;
		return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
				config.token.getBytes(StandardCharsets.UTF_8));
	}

	/** Requires a valid session or the master token; throws 401 otherwise. */
	private Auth requireAuth(HttpExchange ex) {
		String token = bearer(ex);
		if (token == null) throw new Json.ApiError(401, "unauthorized", "กรุณาเข้าสู่ระบบ");
		if (isMaster(token)) return new Auth("admin", null, true, true, null);
		LinkManager.Session s = links.resolve(token);
		if (s == null) throw new Json.ApiError(401, "unauthorized", "เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่");
		// The owner may remove a name from the whitelist while a session is alive.
		if (!config.isAllowed(s.name())) {
			throw new Json.ApiError(401, "unauthorized", "ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่นแล้ว");
		}
		return new Auth(s.name(), s.uuid(), config.isAdmin(s.name()), false, s);
	}

	/** Requires a real player session (master token is rejected). */
	private Auth requireSession(HttpExchange ex) {
		Auth a = requireAuth(ex);
		if (a.session() == null) {
			throw new Json.ApiError(403, "forbidden", "ต้องเข้าสู่ระบบด้วยบัญชีผู้เล่น");
		}
		return a;
	}

	// ---- handlers -----------------------------------------------------------

	/** Serves the web page exactly as bundled — never injects any token. */
	private void sendWeb(HttpExchange ex) throws IOException {
		if (webPage == null) {
			byte[] raw;
			try (InputStream in = WebServer.class.getResourceAsStream("/claimmap-web/index.html")) {
				if (in == null) {
					throw new Json.ApiError(404, "not_found", "ไม่พบหน้าเว็บในไฟล์ม็อด");
				}
				raw = in.readAllBytes();
			}
			webPage = raw;
		}
		ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		ex.sendResponseHeaders(200, webPage.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(webPage);
		}
	}

	/** POST /api/login {"name","password"} -> {"token","name","admin"}. Public. */
	private void handleLogin(HttpExchange ex) throws IOException {
		JsonObject body = Json.readObject(ex);
		String name = Json.str(body, "name");
		String password = Json.str(body, "password");
		if (name == null || name.isBlank() || name.length() > 32 || password == null || password.isEmpty()) {
			throw new Json.ApiError(400, "bad_request", "ต้องกรอกชื่อผู้เล่นและรหัสผ่าน");
		}
		name = name.trim();

		String key = LoginThrottle.key(remoteIp(ex), name);
		long now = System.currentTimeMillis();
		long retry = throttle.retryAfterMs(key, now);
		if (retry > 0) {
			long seconds = Math.max(1L, (retry + 999L) / 1000L);
			throw new Json.ApiError(429, "too_many_attempts", "ลองผิดหลายครั้งเกินไป กรุณารออีก " + seconds + " วินาที")
					.header("Retry-After", Long.toString(seconds))
					.extra("retryAfterSeconds", seconds);
		}

		// Same response and roughly the same work for every failure reason.
		AccountManager.Account account = null;
		if (config.isAllowed(name)) {
			account = accounts.verify(name, password);
		} else {
			accounts.burnVerify(password);
		}
		if (account == null) {
			throttle.fail(key, now);
			throw new Json.ApiError(401, "invalid_credentials", "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง");
		}

		throttle.success(key);
		LinkManager.Session s = links.createSession(account.uuid, account.name);
		JsonObject out = new JsonObject();
		out.addProperty("token", s.token());     // legacy field name, kept for old clients
		out.addProperty("session", s.token());
		out.addProperty("name", s.name());
		out.addProperty("admin", config.isAdmin(s.name()));
		Json.send(ex, 200, out);
	}

	/** GET /api/me -> {"name","admin"} */
	private void handleMe(HttpExchange ex) throws IOException {
		Auth a = requireAuth(ex);
		JsonObject out = new JsonObject();
		out.addProperty("name", a.name());
		out.addProperty("admin", a.admin());
		Json.send(ex, 200, out);
	}

	/** POST /api/link {"code"} — legacy in-game 6 digit code login. */
	private void handleLink(HttpExchange ex) throws IOException {
		requireAuth(ex);
		JsonObject body = Json.readObject(ex);
		String code = Json.str(body, "code");
		if (code == null || !code.matches("\\d{6}")) {
			throw new Json.ApiError(400, "bad_request", "รหัสต้องเป็นตัวเลข 6 หลัก");
		}
		LinkManager.Session s = links.exchange(code);
		if (s == null) {
			throw new Json.ApiError(403, "forbidden", "รหัสไม่ถูกต้องหรือหมดอายุ");
		}
		JsonObject out = new JsonObject();
		out.addProperty("token", s.token());     // legacy field name, kept for old clients
		out.addProperty("session", s.token());
		out.addProperty("name", s.name());
		out.addProperty("admin", config.isAdmin(s.name()));
		Json.send(ex, 200, out);
	}

	/** POST /api/command — master token only (script/console use). */
	private void handleCommand(HttpExchange ex) throws IOException {
		String token = bearer(ex);
		if (token == null) throw new Json.ApiError(401, "unauthorized", "กรุณาเข้าสู่ระบบ");
		if (!isMaster(token)) {
			throw new Json.ApiError(403, "forbidden", "คำสั่งนี้ใช้ได้เฉพาะ master token");
		}
		JsonObject body = Json.readObject(ex);
		String cmd = Json.str(body, "command");
		if (cmd == null || cmd.isBlank()) {
			throw new Json.ApiError(400, "bad_request", "ต้องระบุ command");
		}
		var server = ClaimMap.getServer();
		if (server == null) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		}
		// Commands must run on the server thread (R3); fire and forget.
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
		Json.send(ex, 200, true);
	}

	/** GET /api/cells -> {"cx,cz":"ownerName",...} */
	private void handleCells(HttpExchange ex) throws IOException {
		requireAuth(ex);
		JsonObject out = new JsonObject();
		for (Map.Entry<String, String> e : regions.ownerMap().entrySet()) {
			out.addProperty(e.getKey(), e.getValue());
		}
		Json.send(ex, 200, out);
	}

	/** GET /api/map/{cx},{cz}.png */
	private void handleMap(HttpExchange ex, String path) throws IOException {
		requireAuth(ex);
		String rest = path.substring("/api/map/".length());
		if (!rest.endsWith(".png")) {
			throw new Json.ApiError(404, "not_found", "ไม่พบแผนที่ที่เรียก");
		}
		int[] cell = parseCellId(rest.substring(0, rest.length() - 4));
		if (cell == null) {
			throw new Json.ApiError(400, "bad_request", "พิกัดช่องไม่ถูกต้อง");
		}
		byte[] png = maps.encodedPng(cell[0], cell[1]);
		if (png == null) {
			Json.sendNoContent(ex);
			return;
		}
		ex.getResponseHeaders().set("Content-Type", "image/png");
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(200, png.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(png);
		}
	}

	/** /api/cells/{x},{z}[/claim|/members] and DELETE /api/cells/{x},{z} */
	private void handleCellAction(HttpExchange ex, String method, String path) throws IOException {
		String rest = path.substring("/api/cells/".length());
		String id;
		String action = "";
		if (rest.endsWith("/claim")) {
			id = rest.substring(0, rest.length() - "/claim".length());
			action = "claim";
		} else if (rest.endsWith("/members")) {
			id = rest.substring(0, rest.length() - "/members".length());
			action = "members";
		} else {
			id = rest;
		}
		if (parseCellId(id) == null) {
			throw new Json.ApiError(400, "bad_request", "พิกัดช่องไม่ถูกต้อง");
		}

		switch (action) {
			case "claim" -> {
				requireMethod(ex, method, "POST");
				Auth a = requireSession(ex);
				int[] cell = parseCellId(id);
				RegionManager.ClaimResult r = regions.claim(cell[0], cell[1], a.uuid(), a.name());
				switch (r) {
					case OK -> Json.send(ex, 200, true);
					case ALREADY_CLAIMED -> throw new Json.ApiError(409, "already_claimed", "ช่องนี้ถูกจองแล้ว");
					case COMMON_AREA -> throw new Json.ApiError(403, "forbidden", "เป็นพื้นที่ส่วนกลาง จองไม่ได้");
					case LIMIT_REACHED -> throw new Json.ApiError(409, "limit_reached", "จองช่องครบจำนวนที่กำหนดแล้ว");
				}
			}
			case "members" -> {
				requireMethod(ex, method, "POST");
				Auth a = requireSession(ex);
				JsonObject body = Json.readObject(ex);
				String mUuid = Json.str(body, "uuid");
				String mName = Json.str(body, "name");
				if (mUuid == null || mUuid.isBlank()) {
					throw new Json.ApiError(400, "bad_request", "ต้องระบุ uuid ของสมาชิก");
				}
				boolean ok = regions.addMember(id, a.uuid(), mUuid, mName == null ? "" : mName);
				if (!ok) throw new Json.ApiError(403, "forbidden", "คุณไม่ใช่เจ้าของช่องนี้");
				Json.send(ex, 200, true);
			}
			default -> {
				requireMethod(ex, method, "DELETE");
				Auth a = requireAuth(ex);
				boolean admin = a.master() || a.admin();
				String byUuid = a.master() ? "admin" : a.uuid();
				boolean ok = regions.unclaim(id, byUuid, admin);
				if (!ok) throw new Json.ApiError(403, "forbidden", "ลบช่องนี้ไม่ได้");
				Json.send(ex, 200, true);
			}
		}
	}

	// ---- economy ------------------------------------------------------------

	/** GET /api/wallet -> balance + currency + the 50 newest movements. */
	private void handleWallet(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireMarketEnabled();
		UUID uuid = sessionUuid(a);
		economy.ensureAccount(uuid);

		JsonObject out = new JsonObject();
		out.addProperty("balance", economy.balance(uuid));
		out.addProperty("currency", config.currencyName);
		JsonArray history = new JsonArray();
		for (EconomyManager.TxView v : economy.history(uuid, EconomyManager.HISTORY_PER_PLAYER)) {
			JsonObject o = new JsonObject();
			o.addProperty("id", v.id());
			o.addProperty("t", v.t());
			o.addProperty("dir", v.dir());
			o.addProperty("other", otherName(v.other()));
			o.addProperty("amount", v.amount());
			o.addProperty("reason", v.reason());
			history.add(o);
		}
		out.add("history", history);
		Json.send(ex, 200, out);
	}

	/** POST /api/pay {"to","amount"} — server decides everything (R1). */
	private void handlePay(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireMarketEnabled();
		JsonObject body = Json.readObject(ex);

		Long amount = Json.integer(body, "amount");
		if (amount == null || amount < 1 || amount > EconomyManager.MAX_MONEY) {
			throw new Json.ApiError(400, "invalid_amount", "จำนวนเงินไม่ถูกต้อง");
		}
		String to = Json.str(body, "to");
		if (to == null || to.isBlank() || to.trim().length() > 32) {
			throw new Json.ApiError(400, "bad_request", "ชื่อผู้รับไม่ถูกต้อง");
		}
		to = to.trim();
		UUID sender = sessionUuid(a);

		if (!config.isAllowed(to)) throw new Json.ApiError(404, "not_found", "ไม่พบผู้เล่นชื่อนี้");
		Optional<UUID> target = accounts.uuidOf(to);
		if (target.isEmpty() || target.get().equals(sender)) {
			if (target.isPresent()) throw new Json.ApiError(400, "bad_request", "โอนให้ตัวเองไม่ได้");
			throw new Json.ApiError(404, "not_found", "ไม่พบผู้เล่นชื่อนี้ (ผู้เล่นยังไม่เคยเข้าเกม)");
		}

		String requestId = ex.getRequestHeaders().getFirst("X-Request-Id");
		if (requestId != null) {
			requestId = requestId.trim();
			if (requestId.isEmpty()) {
				requestId = null;
			} else if (requestId.length() > 64) {
				throw new Json.ApiError(400, "bad_request", "X-Request-Id ยาวเกิน 64 ตัวอักษร");
			}
		}
		long now = System.currentTimeMillis();
		idempotent.values().removeIf(c -> c.expiresAt() <= now);
		String cacheKey = sender + "|" + requestId;
		if (requestId != null) {
			Cached cached = idempotent.get(cacheKey);
			if (cached != null) {
				Json.send(ex, 200, cached.body());
				return;
			}
		}

		economy.ensureAccount(sender);
		TxResult r = economy.transfer(sender, target.get(), amount, "pay", "");
		switch (r) {
			case OK -> {
			}
			case INSUFFICIENT -> throw new Json.ApiError(409, "insufficient_funds", "ยอดเงินไม่พอ");
			case FROZEN -> throw new Json.ApiError(409, "account_frozen", "บัญชีนี้ถูกระงับการโอน");
			case NO_ACCOUNT -> throw new Json.ApiError(404, "not_found", "ผู้รับยังไม่เคยเข้าเกม");
			case SAME_ACCOUNT -> throw new Json.ApiError(400, "bad_request", "โอนให้ตัวเองไม่ได้");
			case INVALID_AMOUNT, OVERFLOW -> throw new Json.ApiError(400, "invalid_amount", "จำนวนเงินไม่ถูกต้อง");
			default -> throw new Json.ApiError(500, "internal_error", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์");
		}

		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("balance", economy.balance(sender));
		if (requestId != null) {
			idempotent.put(cacheKey, new Cached(out.deepCopy(), now + 10 * 60_000L));
		}
		Json.send(ex, 200, out);
	}

	private void requireMarketEnabled() {
		if (economy.isDisabled()) {
			throw new Json.ApiError(503, "market_disabled", "ระบบเงินถูกปิดชั่วคราวเพราะไฟล์ข้อมูลเสีย กรุณาแจ้งแอดมิน");
		}
	}

	private UUID sessionUuid(Auth a) {
		try {
			return UUID.fromString(a.uuid());
		} catch (RuntimeException e) {
			throw new Json.ApiError(401, "unauthorized", "เซสชันไม่ถูกต้อง กรุณาเข้าสู่ระบบใหม่");
		}
	}

	private String otherName(Acct other) {
		if (other == null) return "ระบบ";
		if (other.isSystem()) return "ระบบ";
		if (other.isEscrow()) return "ประมูล";
		return accounts.nameOf(other.uuid()).orElse("ผู้เล่น");
	}

	// ---- helpers ------------------------------------------------------------

	private String remoteIp(HttpExchange ex) {
		var addr = ex.getRemoteAddress();
		if (addr == null) return "?";
		if (addr.getAddress() != null) return addr.getAddress().getHostAddress();
		return addr.getHostString();
	}

	private static int[] parseCellId(String id) {
		if (id == null) return null;
		int comma = id.lastIndexOf(',');
		if (comma <= 0 || comma >= id.length() - 1) return null;
		try {
			int cx = Integer.parseInt(id.substring(0, comma));
			int cz = Integer.parseInt(id.substring(comma + 1));
			return new int[]{cx, cz};
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
