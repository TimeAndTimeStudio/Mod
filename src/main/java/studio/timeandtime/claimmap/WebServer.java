package studio.timeandtime.claimmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
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
	private final PriceService prices;
	private final MailboxManager mailbox;
	private final ShopManager shop;
	private final AuctionManager auctions;
	private final AdminService admin;
	private static final Pattern STATIC_PATH =
			Pattern.compile("^/(css|js|icons|img)/[A-Za-z0-9_./-]+$|^/favicon\\.png$");
	private final LoginThrottle throttle = new LoginThrottle();
	private final Map<String, Cached> idempotent = new ConcurrentHashMap<>();
	private HttpServer server;
	private byte[] webPage;

	private record Cached(JsonObject body, long expiresAt) {
	}

	public WebServer(ClaimMapConfig config, RegionManager regions, MapRenderer maps, LinkManager links,
			AccountManager accounts, EconomyManager economy, PriceService prices, MailboxManager mailbox,
			ShopManager shop, AuctionManager auctions, AdminService admin) {
		this.config = config;
		this.regions = regions;
		this.maps = maps;
		this.links = links;
		this.accounts = accounts;
		this.economy = economy;
		this.prices = prices;
		this.mailbox = mailbox;
		this.shop = shop;
		this.auctions = auctions;
		this.admin = admin;
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
			case "/api/health" -> {
				requireMethod(ex, method, "GET");
				handleHealth(ex);
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
			case "/api/prices" -> {
				requireMethod(ex, method, "GET");
				handlePrices(ex);
			}
			case "/api/shop" -> {
				requireMethod(ex, method, "GET");
				handleShop(ex);
			}
			case "/api/shop/buy" -> {
				requireMethod(ex, method, "POST");
				handleShopBuy(ex);
			}
			case "/api/shop/sell" -> {
				requireMethod(ex, method, "POST");
				handleShopSell(ex);
			}
			case "/api/mailbox" -> {
				requireMethod(ex, method, "GET");
				handleMailbox(ex);
			}
			case "/api/mailbox/claim" -> {
				requireMethod(ex, method, "POST");
				handleMailboxClaim(ex);
			}
			case "/api/auctions" -> {
				if (method.equals("POST")) handleCreateAuction(ex);
				else {
					requireMethod(ex, method, "GET");
					handleAuctions(ex);
				}
			}
			case "/api/sources" -> {
				requireMethod(ex, method, "GET");
				handleSources(ex);
			}
			case "/api/admin/overview" -> {
				requireMethod(ex, method, "GET");
				handleAdminOverview(ex);
			}
			case "/api/admin/money" -> {
				requireMethod(ex, method, "POST");
				handleAdminMoney(ex);
			}
			case "/api/admin/prices/override" -> {
				requireMethod(ex, method, "POST");
				handleAdminPriceOverride(ex);
			}
			case "/api/admin/prices/block" -> {
				requireMethod(ex, method, "POST");
				handleAdminPriceBlock(ex);
			}
			case "/api/admin/prices/refresh" -> {
				requireMethod(ex, method, "POST");
				handleAdminPriceRefresh(ex);
			}
			case "/api/admin/auctions" -> {
				requireMethod(ex, method, "GET");
				handleAdminAuctions(ex);
			}
			case "/api/admin/config/reload" -> {
				requireMethod(ex, method, "POST");
				handleAdminReload(ex);
			}
			case "/api/admin/audit" -> {
				requireMethod(ex, method, "GET");
				handleAdminAudit(ex);
			}
			default -> {
				if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/icons/")) {
					requireMethod(ex, method, "GET");
					handleStatic(ex, path);
				} else if (path.startsWith("/api/admin/")) {
					handleAdminDynamic(ex, method, path);
				} else if (path.startsWith("/api/auctions/")) {
					handleAuctionAction(ex, method, path);
				} else if (path.startsWith("/api/map/")) {
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

	/**
	 * Serves the bundled CSS/JS/icon files. Only the three asset folders are
	 * reachable, and encoded or dotted traversal segments are rejected before
	 * the classpath lookup (PHASES.md 8).
	 */
	private void handleStatic(HttpExchange ex, String path) throws IOException {
		String raw = ex.getRequestURI().getRawPath() == null ? path : ex.getRequestURI().getRawPath();
		if (!STATIC_PATH.matcher(path).matches() || !STATIC_PATH.matcher(raw).matches()
				|| path.contains("..") || raw.contains("..")
				|| path.contains("//") || raw.contains("//")
				|| path.indexOf('\\') >= 0 || raw.indexOf('\\') >= 0
				|| path.contains("%") || hasHiddenSegment(path)) {
			throw new Json.ApiError(404, "not_found", "ไม่พบไฟล์ที่เรียก");
		}
		String rel = path.substring(1);
		byte[] data;
		try (InputStream in = WebServer.class.getResourceAsStream("/claimmap-web/" + rel)) {
			if (in == null) throw new Json.ApiError(404, "not_found", "ไม่พบไฟล์ที่เรียก");
			data = in.readAllBytes();
		} catch (IOException e) {
			throw new Json.ApiError(404, "not_found", "ไม่พบไฟล์ที่เรียก");
		}
		String type = contentType(rel);
		if (type == null) throw new Json.ApiError(404, "not_found", "ไม่พบไฟล์ที่เรียก");
		ex.getResponseHeaders().set("Content-Type", type);
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		ex.getResponseHeaders().set("Cache-Control", rel.startsWith("icons/") ? "max-age=86400" : "no-cache");
		ex.sendResponseHeaders(200, data.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(data);
		}
	}

	private static boolean hasHiddenSegment(String path) {
		for (String segment : path.split("/")) {
			if (segment.startsWith(".")) return true;
		}
		return false;
	}

	private static String contentType(String rel) {
		if (rel.endsWith(".css")) return "text/css; charset=utf-8";
		if (rel.endsWith(".js")) return "text/javascript; charset=utf-8";
		if (rel.endsWith(".png")) return "image/png";
		if (rel.endsWith(".svg")) return "image/svg+xml";
		if (rel.endsWith(".html")) return "text/html; charset=utf-8";
		return null;
	}

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

	/** GET /api/health -> {"status":"ok","name":"Time SMP","time":...}. Public. */
	private void handleHealth(HttpExchange ex) throws IOException {
		JsonObject out = new JsonObject();
		out.addProperty("status", "ok");
		out.addProperty("name", "Time SMP");
		out.addProperty("time", System.currentTimeMillis());
		Json.send(ex, 200, out);
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

	/** GET /api/me -> {"name","admin","x","z","hasPos"} */
	private void handleMe(HttpExchange ex) throws IOException {
		Auth a = requireAuth(ex);
		JsonObject out = new JsonObject();
		out.addProperty("name", a.name());
		out.addProperty("admin", a.admin());
		try {
			UUID uuid = sessionUuid(a);
			if (uuid != null) {
				var s = ClaimMap.getServer();
				if (s != null) {
					var sp = s.getPlayerList().getPlayer(uuid);
					if (sp != null) {
						out.addProperty("x", sp.getBlockX());
						out.addProperty("z", sp.getBlockZ());
						out.addProperty("hasPos", true);
					}
				}
				if (!out.has("hasPos")) {
					net.minecraft.core.BlockPos p = PlayerPositions.get(uuid);
					if (p != null) {
						out.addProperty("x", p.getX());
						out.addProperty("z", p.getZ());
						out.addProperty("hasPos", true);
					}
				}
			}
		} catch (Exception ignored) {
		}
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
		} else if (rest.endsWith("/transfer")) {
			id = rest.substring(0, rest.length() - "/transfer".length());
			action = "transfer";
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
			case "transfer" -> {
				requireMethod(ex, method, "POST");
				Auth a = requireAuth(ex);
				boolean admin = a.master() || a.admin();
				JsonObject body = Json.readObject(ex);
				String to = Json.str(body, "to");
				if (to == null || to.isBlank() || to.trim().length() > 32) {
					throw new Json.ApiError(400, "bad_request", "ต้องระบุชื่อผู้รับโอน");
				}
				to = to.trim();
				UUID targetUuid = accounts.uuidOf(to).orElse(null);
				String targetName = to;
				if (targetUuid == null) {
					var s = ClaimMap.getServer();
					if (s != null) {
						var sp = s.getPlayerList().getPlayerByName(to);
						if (sp != null) {
							targetUuid = sp.getUUID();
							targetName = sp.getGameProfile().name();
						}
					}
				}
				if (targetUuid == null) {
					throw new Json.ApiError(404, "not_found", "ไม่พบผู้เล่นปลายทาง (ต้องเคยเข้าเกมหรือออนไลน์)");
				}
				String actorUuid = a.master() ? "admin" : a.uuid();
				boolean ok = regions.transfer(id, actorUuid, targetUuid.toString(), targetName, admin);
				if (!ok) {
					throw new Json.ApiError(403, "forbidden", "คุณไม่ใช่เจ้าของช่องนี้หรือไม่สามารถโอนได้");
				}
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("id", id);
				out.addProperty("ownerName", targetName);
				Json.send(ex, 200, out);
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
		out.addProperty("mailboxCount", mailbox.count(uuid));
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

	// ---- shop / mailbox -----------------------------------------------------

	/** GET /api/shop -> prices + labels + mailbox count. */
	private void handleShop(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireShopEnabled();
		UUID uuid = sessionUuid(a);
		JsonObject out = new JsonObject();
		out.addProperty("currency", config.currencyName);
		out.addProperty("stale", prices.staleForApi());
		JsonObject items = new JsonObject();
		for (Map.Entry<String, PriceService.PricePair> e : prices.snapshot().entrySet()) {
			if (!ItemRegistryAdapter.exists(e.getKey())) continue;
			JsonObject o = new JsonObject();
			o.addProperty("buy", e.getValue().buy());
			o.addProperty("sell", e.getValue().sell());
			o.addProperty("label", ItemRegistryAdapter.label(e.getKey()));
			items.add(e.getKey(), o);
		}
		out.add("items", items);
		out.addProperty("mailboxCount", mailbox.count(uuid));
		Json.send(ex, 200, out);
	}

	/** POST /api/shop/buy {item, qty} — items always go to the mailbox. */
	private void handleShopBuy(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireShopEnabled();
		UUID uuid = sessionUuid(a);
		JsonObject body = Json.readObject(ex);
		String item = Json.str(body, "item");
		Long qtyValue = Json.integer(body, "qty");
		if (item == null || item.isBlank()) throw new Json.ApiError(400, "invalid_item", "ต้องระบุไอเท็ม");
		if (qtyValue == null || qtyValue < 1 || qtyValue > config.shopMaxQtyPerTx) {
			throw new Json.ApiError(400, "invalid_quantity", "จำนวนไม่ถูกต้อง (1-" + config.shopMaxQtyPerTx + ")");
		}
		int qty = qtyValue.intValue();
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");

		String ref = "w" + Long.toString(System.currentTimeMillis(), 36) + "_" + Integer.toString(uuid.hashCode(), 36);
		ShopManager.BuyPlan plan;
		try {
			plan = GameBridge.call(server, () -> shop.prepareBuy(uuid, item, qty, ref, server.registryAccess()),
					GameBridge.DEFAULT_TIMEOUT_MS);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (plan.result() != ShopManager.Result.OK) throw shopError(plan.result());

		ShopManager.BuyOutcome outcome = shop.commitBuy(uuid, plan);
		if (outcome.result() != ShopManager.Result.OK) throw shopError(outcome.result());
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("bought", outcome.bought());
		out.addProperty("spent", outcome.spent());
		out.addProperty("balance", outcome.balance());
		out.addProperty("mailboxCount", outcome.mailboxCount());
		Json.send(ex, 200, out);
	}

	/** POST /api/shop/sell {item, qty} — requires the player to be online. */
	private void handleShopSell(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireShopEnabled();
		UUID uuid = sessionUuid(a);
		JsonObject body = Json.readObject(ex);
		String item = Json.str(body, "item");
		Long qtyValue = Json.integer(body, "qty");
		if (item == null || item.isBlank()) throw new Json.ApiError(400, "invalid_item", "ต้องระบุไอเท็ม");
		if (qtyValue == null || qtyValue < 1 || qtyValue > config.shopMaxQtyPerTx) {
			throw new Json.ApiError(400, "invalid_quantity", "จำนวนไม่ถูกต้อง (1-" + config.shopMaxQtyPerTx + ")");
		}
		int qty = qtyValue.intValue();
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		if (server.getPlayerList().getPlayer(uuid) == null) {
			throw new Json.ApiError(409, "player_offline", "ต้องเข้าเกมก่อนจึงจะขายของได้");
		}

		String ref = "w" + Long.toString(System.currentTimeMillis(), 36);
		ShopManager.SellOutcome outcome;
		try {
			outcome = GameBridge.call(server, () -> {
				var player = server.getPlayerList().getPlayer(uuid);
				if (player == null) return new ShopManager.SellOutcome(ShopManager.Result.OFFLINE, 0, 0, 0);
				return shop.sell(player, item, qty, ref);
			}, GameBridge.DEFAULT_TIMEOUT_MS);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (outcome.result() != ShopManager.Result.OK) throw shopError(outcome.result());
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("sold", outcome.sold());
		out.addProperty("earned", outcome.earned());
		out.addProperty("balance", outcome.balance());
		Json.send(ex, 200, out);
	}

	/** GET /api/mailbox */
	private void handleMailbox(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		if (mailbox.isDisabled()) {
			throw new Json.ApiError(503, "market_disabled", "กล่องของถูกปิดชั่วคราวเพราะไฟล์ข้อมูลเสีย");
		}
		UUID uuid = sessionUuid(a);
		JsonArray entries = new JsonArray();
		for (MailboxManager.MailEntry entry : mailbox.list(uuid)) {
			JsonObject o = new JsonObject();
			o.addProperty("id", entry.id());
			o.addProperty("itemId", entry.itemId());
			o.addProperty("count", entry.count());
			o.addProperty("label", entry.label());
			o.addProperty("from", entry.from());
			o.addProperty("t", entry.t());
			entries.add(o);
		}
		JsonObject out = new JsonObject();
		out.add("entries", entries);
		out.addProperty("count", entries.size());
		Json.send(ex, 200, out);
	}

	/** POST /api/mailbox/claim — requires the player to be online. */
	private void handleMailboxClaim(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireShopEnabled();
		UUID uuid = sessionUuid(a);
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		if (server.getPlayerList().getPlayer(uuid) == null) {
			throw new Json.ApiError(409, "player_offline", "ต้องเข้าเกมก่อนจึงรับของได้");
		}
		ShopManager.ClaimOutcome outcome;
		try {
			outcome = GameBridge.call(server, () -> {
				var player = server.getPlayerList().getPlayer(uuid);
				return player == null ? null : shop.claim(player);
			}, GameBridge.DEFAULT_TIMEOUT_MS);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (outcome == null) throw new Json.ApiError(409, "player_offline", "ต้องเข้าเกมก่อนจึงรับของได้");
		if (outcome.claimed() == 0 && outcome.remaining() > 0) {
			throw new Json.ApiError(409, "inventory_full", "ช่องเก็บของในตัวไม่พอ กรุณาเคลียร์ช่องในกระเป๋าก่อนรับของ");
		}
		JsonObject out = new JsonObject();
		out.addProperty("claimed", outcome.claimed());
		out.addProperty("remaining", outcome.remaining());
		out.addProperty("skipped", outcome.skipped());
		out.addProperty("inventoryFull", outcome.inventoryFull());
		Json.send(ex, 200, out);
	}

	// ---- auctions -----------------------------------------------------------

	/** GET /api/auctions — active listings plus the caller's 7-day history. */
	private void handleAuctions(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireAuctionEnabled();
		UUID uuid = sessionUuid(a);
		JsonArray list = new JsonArray();
		for (AuctionManager.Auction au : auctions.all()) {
			if (!au.isActive()) continue;
			list.add(auctionView(au, uuid));
		}
		JsonArray mine = new JsonArray();
		for (AuctionManager.Auction au : auctions.recentForSeller(uuid.toString())) {
			mine.add(auctionView(au, uuid));
		}
		JsonObject out = new JsonObject();
		out.addProperty("currency", config.currencyName);
		out.add("auctions", list);
		out.add("mine", mine);
		Json.send(ex, 200, out);
	}

	private JsonObject auctionView(AuctionManager.Auction au, UUID viewer) {
		JsonObject o = new JsonObject();
		o.addProperty("id", au.id);
		o.addProperty("sellerName", au.sellerName);
		o.addProperty("itemId", au.itemId);
		o.addProperty("count", au.count);
		o.addProperty("label", au.label);
		o.addProperty("startPrice", au.startPrice);
		o.addProperty("buyout", au.buyout);
		o.addProperty("highBid", au.highBid);
		o.addProperty("highBidderName", au.highBidderName);
		o.addProperty("endsAt", au.endsAt);
		o.addProperty("status", au.status);
		o.addProperty("minBid", auctions.minBid(au));
		o.addProperty("finalPrice", au.finalPrice);
		o.addProperty("winner", au.winner == null ? null : au.winner.equals(viewer.toString()) ? "คุณ" : null);
		boolean mine = au.seller.equals(viewer.toString());
		o.addProperty("mine", mine);
		o.addProperty("canBid", au.isActive() && !mine && !viewer.toString().equals(au.highBidder));
		o.addProperty("canCancel", au.isActive() && mine && au.highBidder == null);
		return o;
	}

	/** POST /api/auctions/{id}/{bid|buyout|cancel} */
	private void handleAuctionAction(HttpExchange ex, String method, String path) throws IOException {
		requireMethod(ex, method, "POST");
		Auth a = requireSession(ex);
		requireAuctionEnabled();
		String rest = path.substring("/api/auctions/".length());
		int slash = rest.indexOf('/');
		if (slash <= 0 || slash == rest.length() - 1) {
			throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
		}
		String id = rest.substring(0, slash);
		String action = rest.substring(slash + 1);
		UUID uuid = sessionUuid(a);
		switch (action) {
			case "bid" -> {
				JsonObject body = Json.readObject(ex);
				Long amount = Json.integer(body, "amount");
				if (amount == null || amount < 1 || amount > EconomyManager.MAX_MONEY) {
					throw new Json.ApiError(400, "invalid_amount", "จำนวนเงินไม่ถูกต้อง");
				}
				AuctionManager.BidOutcome o = auctions.bid(uuid, a.name(), id, amount);
				if (o.result() != AuctionManager.Result.OK) throw auctionError(o.result(), o.minBid());
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("highBid", o.highBid());
				out.addProperty("endsAt", o.endsAt());
				out.addProperty("balance", economy.balance(uuid));
				Json.send(ex, 200, out);
			}
			case "buyout" -> {
				Json.readObject(ex);   // must still be a JSON object, even if empty
				AuctionManager.Result r = auctions.buyout(uuid, a.name(), id);
				if (r != AuctionManager.Result.OK) throw auctionError(r, 0);
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("balance", economy.balance(uuid));
				out.addProperty("mailboxCount", mailbox.count(uuid));
				Json.send(ex, 200, out);
			}
			case "cancel" -> {
				Json.readObject(ex);
				AuctionManager.Result r = auctions.cancel(uuid, a.admin(), id);
				if (r != AuctionManager.Result.OK) throw auctionError(r, 0);
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("mailboxCount", mailbox.count(uuid));
				Json.send(ex, 200, out);
			}
			default -> throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
		}
	}

	/** GET /api/sources — sellable stacks in inventory, ender chest and claimed containers. */
	private void handleSources(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		UUID uuid = sessionUuid(a);
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		List<ItemSources.Entry> entries;
		try {
			entries = GameBridge.call(server, () -> {
				var player = server.getPlayerList().getPlayer(uuid);
				if (player == null) return null;
				return ItemSources.list(player, regions);
			}, 8000L);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (entries == null) throw new Json.ApiError(409, "player_offline", "ต้องเข้าเกมก่อนจึงจะดึงของได้");
		entries.sort((x, y) -> {
			int c = x.area().compareTo(y.area());
			return c != 0 ? c : x.label().compareTo(y.label());
		});
		JsonArray arr = new JsonArray();
		for (ItemSources.Entry e : entries) {
			JsonObject o = new JsonObject();
			o.addProperty("ref", e.ref());
			o.addProperty("area", e.area());
			o.addProperty("itemId", e.itemId());
			o.addProperty("label", e.label());
			o.addProperty("count", e.count());
			arr.add(o);
		}
		JsonObject out = new JsonObject();
		out.add("items", arr);
		out.addProperty("note", "เฉพาะกล่องในพื้นที่ที่โหลดอยู่ และเฉพาะพื้นที่ที่คุณเป็นเจ้าของหรือสมาชิก");
		Json.send(ex, 200, out);
	}

	/** POST /api/auctions {ref,count,startPrice,buyout,hours} — web listing. */
	private void handleCreateAuction(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireAuctionEnabled();
		UUID uuid = sessionUuid(a);
		JsonObject body = Json.readObject(ex);
		String ref = Json.str(body, "ref");
		Long countValue = Json.integer(body, "count");
		Long startValue = Json.integer(body, "startPrice");
		Long buyoutValue = Json.integer(body, "buyout");
		Long hoursValue = Json.integer(body, "hours");
		if (ref == null || ref.isBlank()) throw new Json.ApiError(400, "invalid_source", "ต้องเลือกไอเท็มที่จะขาย");
		if (countValue == null || countValue < 1 || countValue > 2304) {
			throw new Json.ApiError(400, "invalid_quantity", "จำนวนไม่ถูกต้อง");
		}
		if (startValue == null || startValue < 1 || startValue > EconomyManager.MAX_MONEY) {
			throw new Json.ApiError(400, "invalid_amount", "ราคาเริ่มต้นไม่ถูกต้อง");
		}
		Long buyout = buyoutValue == null || buyoutValue <= 0 ? null : buyoutValue;
		int hours = hoursValue == null ? 0 : (int) Math.max(0, Math.min(24 * 7, hoursValue));

		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		if (server.getPlayerList().getPlayer(uuid) == null) {
			throw new Json.ApiError(409, "player_offline", "ต้องเข้าเกมก่อนจึงจะลงขายได้");
		}
		String[] result;
		try {
			result = GameBridge.call(server, () -> {
				var player = server.getPlayerList().getPlayer(uuid);
				if (player == null) return new String[]{"player_offline", null};
				ItemSources.Taken taken = ItemSources.take(player, regions, ref, null, countValue.intValue());
				if (taken == null) return new String[]{"invalid_source", null};
				String itemId = ItemCodecs.idOf(taken.stack());
				if (prices.isBlocked(itemId) || !ItemRegistryAdapter.exists(itemId)) {
					taken.restore().run();
					return new String[]{"invalid_item", null};
				}
				String json = ItemCodecs.encode(taken.stack(), server.registryAccess());
				String label = ItemRegistryAdapter.label(itemId);
				AuctionManager.CreatePlan plan = new AuctionManager.CreatePlan(uuid.toString(), a.name(), json, itemId,
						taken.stack().getCount(), label, startValue, buyout, hours);
				AuctionManager.CreateOutcome check = auctions.planCreate(plan);
				if (check.result() != AuctionManager.Result.OK) {
					taken.restore().run();
					return new String[]{check.result().name(), null};
				}
				AuctionManager.CreateOutcome created = auctions.create(plan);
				if (created.result() != AuctionManager.Result.OK) {
					taken.restore().run();
					return new String[]{created.result().name(), null};
				}
				return new String[]{null, created.auctionId()};
			}, GameBridge.DEFAULT_TIMEOUT_MS);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (result[0] != null) {
			if (result[0].equals("invalid_source")) throw new Json.ApiError(409, "invalid_source", "ไอเท็มในช่องนั้นเปลี่ยนแล้ว กรุณารีเฟรช");
			if (result[0].equals("invalid_item")) throw new Json.ApiError(400, "invalid_item", "ไอเท็มนี้ลงขายไม่ได้");
			if (result[0].equals("player_offline")) throw new Json.ApiError(409, "player_offline", "ต้องเข้าเกมก่อนจึงจะลงขายได้");
			throw auctionError(AuctionManager.Result.valueOf(result[0]), 0);
		}
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("id", result[1]);
		Json.send(ex, 200, out);
	}

	private void requireAuctionEnabled() {
		requireMarketEnabled();
		if (auctions.isDisabled()) {
			throw new Json.ApiError(503, "market_disabled", "ระบบตลาดถูกปิดชั่วคราวเพราะไฟล์ข้อมูลเสีย กรุณาแจ้งแอดมิน");
		}
	}

	private Json.ApiError auctionError(AuctionManager.Result r, long minBid) {
		return switch (r) {
			case NOT_FOUND -> new Json.ApiError(404, "not_found", "ไม่พบรายการประมูลนี้");
			case CLOSED -> new Json.ApiError(409, "auction_closed", "รายการนี้ปิดแล้ว");
			case OWN_AUCTION -> new Json.ApiError(409, "own_auction", "ประมูลรายการของตัวเองไม่ได้");
			case ALREADY_HIGHEST -> new Json.ApiError(409, "already_highest", "คุณเป็นผู้ประมูลสูงสุดอยู่แล้ว");
			case BID_TOO_LOW -> new Json.ApiError(409, "bid_too_low", "ต้องประมูลอย่างน้อย " + minBid);
			case USE_BUYOUT -> new Json.ApiError(409, "use_buyout", "ราคานี้ถึงขั้นซื้อทันที กรุณาใช้ปุ่มซื้อทันที");
			case LIMIT_REACHED -> new Json.ApiError(409, "limit_reached", "เกินจำนวนที่อนุญาต หรือกล่องของเต็ม");
			case INSUFFICIENT -> new Json.ApiError(409, "insufficient_funds", "ยอดเงินไม่พอ");
			case FROZEN -> new Json.ApiError(409, "account_frozen", "บัญชีนี้ถูกระงับการค้า");
			case HAS_BIDS -> new Json.ApiError(409, "has_bids", "มีผู้ประมูลแล้ว ยกเลิกไม่ได้");
			case FORBIDDEN -> new Json.ApiError(403, "forbidden", "ไม่มีสิทธิ์กับรายการนี้");
			case BAD_REQUEST -> new Json.ApiError(400, "invalid_amount", "ข้อมูลไม่ถูกต้อง");
			case DISABLED -> new Json.ApiError(503, "market_disabled", "ระบบตลาดถูกปิดชั่วคราว");
			default -> new Json.ApiError(500, "internal_error", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์");
		};
	}

	// ---- admin --------------------------------------------------------------

	private Auth requireAdmin(HttpExchange ex, String path) {
		Auth a = requireSession(ex);
		if (!a.admin()) {
			adminDeny(a.name(), ex, path);
			throw new Json.ApiError(403, "forbidden", "ต้องเป็นแอดมินเท่านั้น");
		}
		return a;
	}

	private void adminDeny(String actor, HttpExchange ex, String path) {
		admin.auditDenied(actor, clientIp(ex), path);
	}

	private String clientIp(HttpExchange ex) {
		try {
			return ex.getRemoteAddress().getAddress().getHostAddress();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private int queryLimit(HttpExchange ex, int def) {
		String q = ex.getRequestURI().getQuery();
		if (q == null) return def;
		for (String part : q.split("&")) {
			if (part.startsWith("limit=")) {
				try {
					return Math.max(1, Math.min(200, Integer.parseInt(part.substring(6))));
				} catch (NumberFormatException ignored) {
					return def;
				}
			}
		}
		return def;
	}

	private void handleAdminOverview(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/overview");
		Json.send(ex, 200, admin.overview(a.name(), clientIp(ex)));
	}

	private void handleAdminMoney(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/money");
		JsonObject body = Json.readObject(ex);
		String player = Json.str(body, "player");
		String op = Json.str(body, "op");
		Long amount = Json.integer(body, "amount");
		String reason = Json.str(body, "reason");
		if (player == null || player.isBlank() || amount == null) {
			throw new Json.ApiError(400, "bad_request", "ต้องระบุ player และ amount");
		}
		if (!AdminService.validReason(reason)) {
			throw new Json.ApiError(400, "bad_request", "ต้องระบุเหตุผล 3-200 ตัวอักษร");
		}
		AdminService.MoneyOutcome o = admin.money(a.name(), clientIp(ex), player, op, amount, reason);
		if (o.result() != AdminService.Result.OK) throw adminError(o.result());
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("balance", o.balance());
		Json.send(ex, 200, out);
	}

	private void handleAdminPriceOverride(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/prices/override");
		JsonObject body = Json.readObject(ex);
		String item = Json.str(body, "item");
		String reason = Json.str(body, "reason");
		Long base = null;
		if (body.has("base") && !body.get("base").isJsonNull()) {
			base = Json.integer(body, "base");
			if (base == null) throw new Json.ApiError(400, "bad_request", "base ต้องเป็นจำนวนเต็ม หรือ null");
		}
		if (item == null || item.isBlank()) {
			throw new Json.ApiError(400, "invalid_item", "ต้องระบุไอเท็ม");
		}
		if (!AdminService.validReason(reason)) throw new Json.ApiError(400, "bad_request", "ต้องระบุเหตุผล 3-200 ตัวอักษร");
		AdminService.PriceOutcome o = admin.overridePrice(a.name(), clientIp(ex), item, base, reason);
		if (o.result() != AdminService.Result.OK) throw adminError(o.result());
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("base", o.base());
		Json.send(ex, 200, out);
	}

	private void handleAdminPriceBlock(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/prices/block");
		JsonObject body = Json.readObject(ex);
		String item = Json.str(body, "item");
		String reason = Json.str(body, "reason");
		JsonElement el = body.get("blocked");
		if (item == null || item.isBlank()) throw new Json.ApiError(400, "invalid_item", "ต้องระบุไอเท็ม");
		if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
			throw new Json.ApiError(400, "bad_request", "blocked ต้องเป็น true/false");
		}
		if (!AdminService.validReason(reason)) throw new Json.ApiError(400, "bad_request", "ต้องระบุเหตุผล 3-200 ตัวอักษร");
		AdminService.Result r = admin.blockItem(a.name(), clientIp(ex), item, el.getAsBoolean(), reason);
		if (r != AdminService.Result.OK) throw adminError(r);
		Json.send(ex, 200, Json.ok());
	}

	private void handleAdminPriceRefresh(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/prices/refresh");
		Json.readObject(ex);
		boolean ok = admin.refreshPrices(a.name(), clientIp(ex));
		JsonObject out = new JsonObject();
		out.addProperty("ok", ok);
		if (!ok) out.addProperty("message", "ดึงราคาไม่สำเร็จ ยังใช้ข้อมูลเดิม");
		Json.send(ex, 200, out);
	}

	private void handleAdminAuctions(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/auctions");
		JsonObject out = new JsonObject();
		out.add("auctions", admin.auctionsForAdmin(a.name(), clientIp(ex)));
		Json.send(ex, 200, out);
	}

	private void handleAdminReload(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/config/reload");
		Json.readObject(ex);
		boolean noRestartNeeded = admin.reloadConfig(a.name(), clientIp(ex));
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("restartNeeded", !noRestartNeeded);
		if (!noRestartNeeded) out.addProperty("message", "port/bindAddress ต้องรีสตาร์ทเซิร์ฟเวอร์");
		Json.send(ex, 200, out);
	}

	private void handleAdminAudit(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/audit");
		int limit = queryLimit(ex, 50);
		JsonArray lines = new JsonArray();
		for (String line : admin.auditTail(a.name(), clientIp(ex), limit)) {
			try {
				lines.add(JsonParser.parseString(line));
			} catch (RuntimeException ignored) {
				// skip a torn line; the file itself is append-only
			}
		}
		JsonObject out = new JsonObject();
		out.add("lines", lines);
		Json.send(ex, 200, out);
	}

	/** GET /api/admin/wallet/{name}, POST /api/admin/auctions/{id}/cancel, ... */
	private void handleAdminDynamic(HttpExchange ex, String method, String path) throws IOException {
		String rest = path.substring("/api/admin/".length());
		if (rest.startsWith("wallet/")) {
			requireMethod(ex, method, "GET");
			Auth a = requireAdmin(ex, path);
			String name = rest.substring("wallet/".length());
			if (name.isBlank() || name.indexOf('/') >= 0) throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
			int limit = queryLimit(ex, 50);
			var history = admin.wallet(a.name(), clientIp(ex), name, limit);
			JsonArray arr = new JsonArray();
			for (EconomyManager.TxView t : history) {
				JsonObject o = new JsonObject();
				o.addProperty("seq", t.seq());
				o.addProperty("t", t.t());
				o.addProperty("id", t.id());
				o.addProperty("dir", t.dir());
				o.addProperty("other", otherName(t.other()));
				o.addProperty("amount", t.amount());
				o.addProperty("reason", t.reason());
				arr.add(o);
			}
			JsonObject out = new JsonObject();
			out.addProperty("player", name);
			out.add("history", arr);
			Json.send(ex, 200, out);
			return;
		}
		if (rest.startsWith("auctions/") && rest.endsWith("/cancel")) {
			requireMethod(ex, method, "POST");
			Auth a = requireAdmin(ex, path);
			String id = rest.substring("auctions/".length(), rest.length() - "/cancel".length());
			JsonObject body = Json.readObject(ex);
			String reason = Json.str(body, "reason");
			if (!AdminService.validReason(reason)) throw new Json.ApiError(400, "bad_request", "ต้องระบุเหตุผล 3-200 ตัวอักษร");
			AdminService.Result r = admin.cancelAuction(a.name(), clientIp(ex), id, reason);
			if (r != AdminService.Result.OK) throw adminError(r);
			Json.send(ex, 200, Json.ok());
			return;
		}
		if (rest.startsWith("accounts/") && rest.endsWith("/freeze")) {
			requireMethod(ex, method, "POST");
			Auth a = requireAdmin(ex, path);
			String name = rest.substring("accounts/".length(), rest.length() - "/freeze".length());
			JsonObject body = Json.readObject(ex);
			JsonElement el = body.get("frozen");
			String reason = Json.str(body, "reason");
			if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
				throw new Json.ApiError(400, "bad_request", "frozen ต้องเป็น true/false");
			}
			if (!AdminService.validReason(reason)) throw new Json.ApiError(400, "bad_request", "ต้องระบุเหตุผล 3-200 ตัวอักษร");
			AdminService.Result r = admin.freeze(a.name(), clientIp(ex), name, el.getAsBoolean(), reason);
			if (r != AdminService.Result.OK) throw adminError(r);
			JsonObject out = new JsonObject();
			out.addProperty("ok", true);
			out.addProperty("frozen", el.getAsBoolean());
			Json.send(ex, 200, out);
			return;
		}
		if (rest.startsWith("accounts/") && rest.endsWith("/reset-password")) {
			requireMethod(ex, method, "POST");
			Auth a = requireAdmin(ex, path);
			String name = rest.substring("accounts/".length(), rest.length() - "/reset-password".length());
			JsonObject body = Json.readObject(ex);
			String reason = Json.str(body, "reason");
			if (!AdminService.validReason(reason)) throw new Json.ApiError(400, "bad_request", "ต้องระบุเหตุผล 3-200 ตัวอักษร");
			AdminService.Result r = admin.resetPassword(a.name(), clientIp(ex), name, reason);
			if (r != AdminService.Result.OK) throw adminError(r);
			JsonObject out = new JsonObject();
			out.addProperty("ok", true);
			out.addProperty("message", "ลบรหัสผ่านแล้ว ผู้เล่นต้องเข้าเกมและตั้งรหัสในหน้าต่างที่ขึ้นให้ทันที");
			Json.send(ex, 200, out);
			return;
		}
		throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
	}

	private Json.ApiError adminError(AdminService.Result r) {
		return switch (r) {
			case BAD_REQUEST -> new Json.ApiError(400, "bad_request", "ข้อมูลไม่ถูกต้อง");
			case NO_ACCOUNT -> new Json.ApiError(404, "player_not_found", "ไม่พบบัญชีผู้เล่นนี้");
			case INSUFFICIENT -> new Json.ApiError(409, "insufficient_funds", "ยอดเงินไม่พอ (ห้ามติดลบ)");
			case NOT_FOUND -> new Json.ApiError(404, "not_found", "ไม่พบรายการ");
			case DISABLED -> new Json.ApiError(503, "market_disabled", "ตลาดถูกปิดชั่วคราว");
			default -> new Json.ApiError(500, "internal_error", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์");
		};
	}

	private void requireShopEnabled() {
		if (shop.isDisabled()) {
			throw new Json.ApiError(503, "market_disabled", "ระบบตลาดถูกปิดชั่วคราวเพราะไฟล์ข้อมูลเสีย กรุณาแจ้งแอดมิน");
		}
	}

	private Json.ApiError shopError(ShopManager.Result r) {
		return switch (r) {
			case INVALID_ITEM -> new Json.ApiError(400, "invalid_item", "ไอเท็มไม่ถูกต้องหรือไม่มีในเกมนี้");
			case INVALID_QTY -> new Json.ApiError(400, "invalid_quantity", "จำนวนไม่ถูกต้อง");
			case NO_PRICE -> new Json.ApiError(409, "no_price", "ไอเท็มนี้ยังไม่มีราคา");
			case BLOCKED -> new Json.ApiError(409, "item_blocked", "ไอเท็มนี้ถูกห้ามซื้อขาย");
			case INSUFFICIENT -> new Json.ApiError(409, "insufficient_funds", "ยอดเงินไม่พอ");
			case NOT_ENOUGH_ITEMS -> new Json.ApiError(409, "not_enough_items", "ของในกระเป๋าไม่พอ");
			case LIMIT_REACHED -> new Json.ApiError(409, "limit_reached", "เกินจำนวนที่อนุญาต");
			case MAILBOX_FULL -> new Json.ApiError(409, "limit_reached", "กล่องของเต็ม กรุณารับของก่อน");
			case FROZEN -> new Json.ApiError(409, "account_frozen", "บัญชีนี้ถูกระงับการค้า");
			case OFFLINE -> new Json.ApiError(409, "player_offline", "ต้องเข้าเกมก่อน");
			case OVERFLOW -> new Json.ApiError(400, "invalid_amount", "ยอดเงินเกินขอบเขต");
			case DISABLED -> new Json.ApiError(503, "market_disabled", "ระบบตลาดถูกปิดชั่วคราว");
			default -> new Json.ApiError(500, "internal_error", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์");
		};
	}

	/** GET /api/prices — never exposes the URL, key or raw base prices. */
	private void handlePrices(HttpExchange ex) throws IOException {
		requireSession(ex);
		JsonObject out = new JsonObject();
		out.addProperty("currency", config.currencyName);
		out.addProperty("stale", prices.staleForApi());
		out.addProperty("updatedAt", prices.updatedAt());
		JsonObject items = new JsonObject();
		for (Map.Entry<String, PriceService.PricePair> e : prices.snapshot().entrySet()) {
			if (!ItemRegistryAdapter.exists(e.getKey())) continue;   // unknown to this game version
			JsonObject p = new JsonObject();
			p.addProperty("buy", e.getValue().buy());
			p.addProperty("sell", e.getValue().sell());
			items.add(e.getKey(), p);
		}
		out.add("items", items);
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
