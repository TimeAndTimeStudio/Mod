package studio.timeandtime.claimmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import studio.timeandtime.claimmap.world.WorldManager;

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
import java.util.concurrent.ExecutorService;
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
	private final PlayerMarket playerMarket;
	private final ConsignmentManager consignments;
	private final AdminService admin;
	private final PlayerItemStore playerItems;
	private final WorldManager worlds;
	private final SalesTracker salesTracker;
	private LandPriceService landPrices;

	public void attachLandPrices(LandPriceService landPrices) {
		this.landPrices = landPrices;
	}
	private static final Pattern STATIC_PATH =
			Pattern.compile("^/(css|js|icons|img)/[A-Za-z0-9_./-]+$|^/favicon\\.png$");
	private final LoginThrottle throttle = new LoginThrottle();
	private final Map<String, Cached> idempotent = new ConcurrentHashMap<>();
	private HttpServer server;
	private ExecutorService httpExecutor;
	private byte[] webPage;

	private record Cached(JsonObject body, long expiresAt) {
	}

	public WebServer(ClaimMapConfig config, RegionManager regions, MapRenderer maps, LinkManager links,
			AccountManager accounts, EconomyManager economy, PriceService prices, MailboxManager mailbox,
			ShopManager shop, AuctionManager auctions, PlayerMarket playerMarket,
			ConsignmentManager consignments, AdminService admin, PlayerItemStore playerItems,
			WorldManager worlds) {
		this(config, regions, maps, links, accounts, economy, prices, mailbox, shop, auctions,
				playerMarket, consignments, admin, playerItems, worlds, null);
	}

	public WebServer(ClaimMapConfig config, RegionManager regions, MapRenderer maps, LinkManager links,
			AccountManager accounts, EconomyManager economy, PriceService prices, MailboxManager mailbox,
			ShopManager shop, AuctionManager auctions, PlayerMarket playerMarket,
			ConsignmentManager consignments, AdminService admin, PlayerItemStore playerItems,
			WorldManager worlds, SalesTracker salesTracker) {
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
		this.playerMarket = playerMarket;
		this.consignments = consignments;
		this.admin = admin;
		this.playerItems = playerItems;
		this.worlds = worlds;
		this.salesTracker = salesTracker;
	}

	public void start() {
		try {
			server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
			server.createContext("/", this::handle);
			httpExecutor = Executors.newFixedThreadPool(8);
			server.setExecutor(httpExecutor);
			server.start();
			ClaimMap.LOGGER.info("[timesmp] HTTP API listening on {}:{}", config.bindAddress, config.port);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not start HTTP API", e);
		}
	}

	public void stop() {
		if (server != null) server.stop(0);
		if (httpExecutor != null) httpExecutor.shutdownNow();
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
			ClaimMap.LOGGER.error("[timesmp] API error {} {}", method, path, e);
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
			case "/api/names" -> {
				requireMethod(ex, method, "GET");
				handleNames(ex);
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
			case "/api/cells/price" -> {
				requireMethod(ex, method, "GET");
				handleCellPrice(ex);
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
			case "/api/prices/trends" -> {
				requireMethod(ex, method, "GET");
				handlePriceTrends(ex);
			}
			case "/api/admin/prices" -> {
				requireMethod(ex, method, "GET");
				handleAdminPricesList(ex);
			}
			case "/api/admin/sales" -> {
				requireMethod(ex, method, "GET");
				handleAdminSales(ex);
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
				if (method.equals("POST")) {
					throw new Json.ApiError(403, "forbidden", "เปิดประมูลได้เฉพาะเซิร์ฟเวอร์/แอดมินเท่านั้น");
				}
				requireMethod(ex, method, "GET");
				handleAuctions(ex);
			}
			case "/api/market" -> {
				if (method.equals("POST")) handleCreateListing(ex);
				else {
					requireMethod(ex, method, "GET");
					handleMarket(ex);
				}
			}
			case "/api/consign" -> {
				if (method.equals("POST")) handleConsign(ex);
				else {
					requireMethod(ex, method, "GET");
					handleConsignList(ex);
				}
			}
			case "/api/sources" -> {
				requireMethod(ex, method, "GET");
				handleSources(ex);
			}
			case "/api/items" -> {
				requireMethod(ex, method, "GET");
				handlePlayerItems(ex);
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
				if (method.equals("POST")) handleAdminCreateAuction(ex);
				else {
					requireMethod(ex, method, "GET");
					handleAdminAuctions(ex);
				}
			}
			case "/api/admin/cell-auctions" -> {
				if (method.equals("POST")) handleAdminCreateCellAuction(ex);
				else {
					requireMethod(ex, method, "GET");
					handleAdminCellAuctions(ex);
				}
			}
			case "/api/admin/consignments" -> {
				requireMethod(ex, method, "GET");
				handleAdminConsignments(ex);
			}
			case "/api/admin/cells" -> {
				requireMethod(ex, method, "GET");
				handleAdminCells(ex);
			}
			case "/api/admin/config/reload" -> {
				requireMethod(ex, method, "POST");
				handleAdminReload(ex);
			}
			case "/api/admin/audit" -> {
				requireMethod(ex, method, "GET");
				handleAdminAudit(ex);
			}
			case "/api/admin/worlds" -> {
				requireMethod(ex, method, "GET");
				handleAdminWorlds(ex);
			}
			case "/api/admin/worlds/scan" -> {
				requireMethod(ex, method, "POST");
				handleAdminWorldsScan(ex);
			}
			default -> {
				if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/icons/") || path.startsWith("/img/") || path.equals("/favicon.png")) {
					requireMethod(ex, method, "GET");
					handleStatic(ex, path);
				} else if (path.startsWith("/api/admin/")) {
					handleAdminDynamic(ex, method, path);
				} else if (path.startsWith("/api/auctions/")) {
					handleAuctionAction(ex, method, path);
				} else if (path.startsWith("/api/market/")) {
					handleMarketAction(ex, method, path);
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
		InputStream resourceStream = WebServer.class.getResourceAsStream("/timesmp-web/" + rel);
		if (resourceStream == null) {
			resourceStream = WebServer.class.getResourceAsStream("/claimmap-web/" + rel);
		}
		try (InputStream in = resourceStream) {
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
			InputStream htmlStream = WebServer.class.getResourceAsStream("/timesmp-web/index.html");
			if (htmlStream == null) {
				htmlStream = WebServer.class.getResourceAsStream("/claimmap-web/index.html");
			}
			try (InputStream in = htmlStream) {
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

	/** GET /api/names -> names registered in config or with an account. Public. */
	private void handleNames(HttpExchange ex) throws IOException {
		java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		addNames(names, config.players);
		addNames(names, config.admins);
		for (AccountManager.Identity id : accounts.identities()) {
			if (id.name() != null && !id.name().isBlank()) names.add(id.name());
		}
		JsonArray arr = new JsonArray();
		for (String name : names) arr.add(name);
		JsonObject out = new JsonObject();
		out.add("names", arr);
		Json.send(ex, 200, out);
	}

	private static void addNames(java.util.TreeSet<String> names, String csv) {
		if (csv == null) return;
		for (String part : csv.split(",")) {
			String name = part.trim();
			if (!name.isEmpty()) names.add(name);
		}
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

	public long currentLandPrice() {
		if (landPrices != null) return landPrices.currentPrice();
		int totalClaimed = regions == null ? 0 : regions.ownerMap().size();
		long price = 100_000L + ((long) totalClaimed * 15_000L);
		return Math.min(1_000_000L, Math.max(100_000L, price));
	}

	private void handleCellPrice(HttpExchange ex) throws IOException {
		long current = currentLandPrice();
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("price", current);
		out.addProperty("basePrice", 100_000L);
		out.addProperty("maxPrice", 1_000_000L);
		out.addProperty("totalClaimed", regions == null ? 0 : regions.ownerMap().size());
		out.addProperty("currency", config.currencyName);
		Json.send(ex, 200, out);
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
		} else if (rest.endsWith("/auction")) {
			id = rest.substring(0, rest.length() - "/auction".length());
			action = "auction";
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
				UUID playerUuid = UUID.fromString(a.uuid());
				int ownedCount = regions.countClaimedBy(a.uuid());
				boolean isFree = ownedCount < config.freeCellsPerPlayer;
				if (!isFree && config.maxCellsPerPlayer > 0 && ownedCount >= config.maxCellsPerPlayer) {
					throw new Json.ApiError(409, "limit_reached", "คุณครอบครองที่ดินครบโควตา " + config.maxCellsPerPlayer + " ช่องแล้ว");
				}
				long landPrice = isFree ? 0L : currentLandPrice();
				if (!isFree) {
					long bal = economy.balance(playerUuid);
					if (bal < landPrice) {
						throw new Json.ApiError(409, "insufficient_funds",
								"เงินไม่พอสำหรับการซื้อที่ดิน ต้องการ " + landPrice + " " + config.currencyName + " (คุณมี " + bal + " " + config.currencyName + ")");
					}
					TxResult tx = economy.withdraw(playerUuid, landPrice, "land.purchase:" + id, id);
					if (tx != TxResult.OK) {
						throw new Json.ApiError(409, "insufficient_funds", "การหักเงินไม่สำเร็จ (" + tx + ")");
					}
				}
				RegionManager.ClaimResult r = regions.claim(cell[0], cell[1], a.uuid(), a.name());
				if (r != RegionManager.ClaimResult.OK) {
					if (!isFree) {
						economy.deposit(playerUuid, landPrice, "land.refund:" + id, id);
					}
					switch (r) {
						case ALREADY_CLAIMED -> throw new Json.ApiError(409, "already_claimed", "ช่องนี้ถูกซื้อหรือมีเจ้าของแล้ว");
						case COMMON_AREA -> throw new Json.ApiError(403, "forbidden", "เป็นพื้นที่ส่วนกลาง ไม่สามารถซื้อได้");
						case LIMIT_REACHED -> throw new Json.ApiError(409, "limit_reached", "ครอบครองที่ดินครบโควตาแล้ว");
						default -> throw new Json.ApiError(500, "internal_error", "ไม่สามารถซื้อที่ดินได้");
					}
				}
				if (!isFree && landPrices != null) {
					landPrices.onCellPurchased();
				}
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("free", isFree);
				out.addProperty("pricePaid", landPrice);
				out.addProperty("balance", economy.balance(playerUuid));
				out.addProperty("nextPrice", currentLandPrice());
				Json.send(ex, 200, out);
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
				if (auctions.hasActiveCellAuction(id)) {
					throw new Json.ApiError(409, "already_listed", "ไม่สามารถโอนเขตนี้ได้เนื่องจากกำลังเปิดประมูลอยู่");
				}
				boolean admin = a.master() || a.admin();
				JsonObject body = Json.readObject(ex);
				String to = Json.str(body, "to");
				if (to == null || to.isBlank() || to.trim().length() > 32) {
					throw new Json.ApiError(400, "bad_request", "ต้องระบุชื่อผู้รับโอน");
				}
				to = to.trim();
				UUID targetUuid = accounts.anyUuidOf(to).orElse(null);
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
			case "auction" -> {
				requireMethod(ex, method, "POST");
				Auth a = requireSession(ex);
				requireAuctionEnabled();
				JsonObject body = Json.readObject(ex);
				Long startPrice = Json.integer(body, "startPrice");
				Long buyoutValue = Json.integer(body, "buyout");
				Long hoursValue = Json.integer(body, "hours");
				if (startPrice == null || startPrice < 0 || startPrice > EconomyManager.MAX_MONEY) {
					throw new Json.ApiError(400, "bad_request", "ราคาเริ่มต้นต้องระหว่าง 0 ถึง " + EconomyManager.MAX_MONEY);
				}
				Long buyout = buyoutValue == null || buyoutValue <= 0 ? null : buyoutValue;
				if (buyout != null && buyout < startPrice) {
					throw new Json.ApiError(400, "bad_request", "ราคาซื้อทันทีต้องมากกว่าหรือเท่ากับราคาเริ่มต้น");
				}
				int hours = hoursValue == null || hoursValue <= 0 ? 24 : (int) Math.min(24 * 7, Math.max(1, hoursValue));
				boolean admin = a.master() || a.admin();
				boolean asServer = admin && body.has("asServer") && body.get("asServer").getAsBoolean();
				String sellerUuid = asServer ? null : a.uuid();
				String sellerName = asServer ? "เซิร์ฟเวอร์" : a.name();
				AuctionManager.CreateOutcome o = auctions.createCellAuction(id, sellerUuid, sellerName, startPrice, buyout, hours);
				if (o.result() != AuctionManager.Result.OK) {
					throw auctionError(o.result(), 0);
				}
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("id", o.auctionId());
				Json.send(ex, 200, out);
			}
			default -> {
				requireMethod(ex, method, "DELETE");
				Auth a = requireAuth(ex);
				if (auctions.hasActiveCellAuction(id)) {
					throw new Json.ApiError(409, "already_listed", "ไม่สามารถปล่อยเขตนี้ได้เนื่องจากกำลังเปิดประมูลอยู่");
				}
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

	/** POST /api/shop/buy {item, qty} — disabled: server is pure sell-to-server. */
	private void handleShopBuy(HttpExchange ex) throws IOException {
		throw new Json.ApiError(403, "server_buy_disabled", "ระบบเปิดรับซื้อไอเท็มจากผู้เล่นเท่านั้น เซิร์ฟเวอร์ไม่มีการขายไอเท็มให้ผู้เล่น");
	}

	/**
	 * POST /api/shop/sell {item, qty, ref?} — sells from the inventory or from
	 * one resolved claim/chest source. Works while the player is offline.
	 */
	private void handleShopSell(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireShopEnabled();
		UUID uuid = sessionUuid(a);
		JsonObject body = Json.readObject(ex);
		String item = Json.str(body, "item");
		String ref = Json.str(body, "ref");
		if (ref != null && ref.isBlank()) ref = null;
		Long qtyValue = Json.integer(body, "qty");
		if (item == null || item.isBlank()) throw new Json.ApiError(400, "invalid_item", "ต้องระบุไอเท็ม");
		if (qtyValue == null || qtyValue < 1 || qtyValue > config.shopMaxQtyPerTx) {
			throw new Json.ApiError(400, "invalid_quantity", "จำนวนไม่ถูกต้อง (1-" + config.shopMaxQtyPerTx + ")");
		}
		int qty = qtyValue.intValue();
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");

		String refId = "w" + Long.toString(System.currentTimeMillis(), 36);
		final String sourceRef = ref;
		final String itemId = item;
		SellTask task;
		try {
			task = GameBridge.call(server, () -> {
				ItemEnv env = openItemEnv(server, uuid, a.name());
				if (env == null) return null;
				try {
					ShopManager.SellOutcome outcome;
					if (sourceRef != null) {
						ItemSources.Source source = ItemSources.find(env.context(), regions, sourceRef);
						outcome = shop.sellFromSource(uuid, source, qty, refId);
					} else if (env.session() != null) {
						outcome = shop.sell(env.session().inventory(), uuid, itemId, qty, refId);
					} else {
						outcome = shop.sell(server.getPlayerList().getPlayer(uuid), itemId, qty, refId);
					}
					if (outcome.result() == ShopManager.Result.OK) refreshCache(env, uuid, a.name());
					return new SellTask(outcome, env.session() != null);
				} finally {
					flushItemEnv(env, server.registryAccess());
				}
			}, 15000L);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (task == null) throw new Json.ApiError(404, "not_found", "ไม่พบข้อมูลผู้เล่น (ต้องเคยเข้าเกมอย่างน้อยหนึ่งครั้ง)");
		ShopManager.SellOutcome outcome = task.outcome();
		if (outcome.result() != ShopManager.Result.OK) throw shopError(outcome.result());
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("sold", outcome.sold());
		out.addProperty("earned", outcome.earned());
		out.addProperty("balance", outcome.balance());
		out.addProperty("offline", task.offline());
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

	/** POST /api/mailbox/claim — delivers into the live or saved inventory. */
	private void handleMailboxClaim(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requireShopEnabled();
		UUID uuid = sessionUuid(a);
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		ClaimTask task;
		try {
			task = GameBridge.call(server, () -> {
				ServerPlayer player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					ShopManager.ClaimOutcome outcome = shop.claim(player);
					if (outcome.claimed() > 0) {
						playerItems.mergeCarried(uuid, a.name(), ItemSources.listCarried(player));
					}
					return new ClaimTask(outcome, false);
				}
				RegistryAccess access = server.registryAccess();
				var session = OfflinePlayers.open(server, uuid, a.name(), access);
				if (session.isEmpty()) return null;
				try {
					ShopManager.ClaimOutcome outcome = shop.claimInto(uuid, session.get().inventory(), access);
					if (outcome.claimed() > 0) {
						playerItems.mergeCarried(uuid, a.name(), ItemSources.listCarried(
								ItemSources.Context.offline(uuid, server.overworld(), session.get().inventory(),
										session.get().ender(), PlayerPositions.get(uuid) == null
												? BlockPos.ZERO : PlayerPositions.get(uuid))));
					}
					return new ClaimTask(outcome, true);
				} finally {
					session.get().flush(access);
				}
			}, 15000L);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (task == null) throw new Json.ApiError(404, "not_found", "ไม่พบข้อมูลผู้เล่น (ต้องเคยเข้าเกมอย่างน้อยหนึ่งครั้ง)");
		ShopManager.ClaimOutcome outcome = task.outcome();
		if (outcome.claimed() == 0 && outcome.remaining() > 0) {
			throw new Json.ApiError(409, "inventory_full", "ช่องเก็บของในตัวไม่พอ กรุณาเคลียร์ช่องในกระเป๋าก่อนรับของ");
		}
		JsonObject out = new JsonObject();
		out.addProperty("claimed", outcome.claimed());
		out.addProperty("remaining", outcome.remaining());
		out.addProperty("skipped", outcome.skipped());
		out.addProperty("inventoryFull", outcome.inventoryFull());
		out.addProperty("offline", task.offline());
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
		boolean cell = au.isCellAuction();
		String viewerId = viewer.toString();
		RegionManager.CellData cellData = cell ? regions.get(au.cellId) : null;
		String cellOwner = cellData == null ? null : cellData.ownerName;
		boolean viewerOwnsCell = cellData != null && viewerId.equals(cellData.ownerUuid);
		boolean mine = au.seller.equals(viewerId);
		boolean highest = viewerId.equals(au.highBidder);
		boolean open = auctions.isOpen(au);
		o.addProperty("id", au.id);
		o.addProperty("kind", cell ? "CELL" : "ITEM");
		o.addProperty("cellId", au.cellId);
		o.addProperty("cellOwnerName", cellOwner);
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
		o.addProperty("winner", au.winner == null ? null : au.winner.equals(viewerId) ? "คุณ" : null);
		o.addProperty("mine", mine);
		o.addProperty("highest", highest);
		o.addProperty("canBid", open && !mine && !highest && !viewerOwnsCell);
		o.addProperty("canBuyout", open && au.buyout != null && !mine && !viewerOwnsCell);
		o.addProperty("canCancel", open && mine && au.highBidder == null);
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

	/** GET /api/sources — sellable stacks, online or from the saved offline data. */
	private void handleSources(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		UUID uuid = sessionUuid(a);
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		SourceResult result;
		try {
			result = GameBridge.call(server, () -> {
				ItemEnv env = openItemEnv(server, uuid, a.name());
				if (env == null) return null;
				try {
					return new SourceResult(ItemSources.list(env.context(), regions), env.session() != null);
				} finally {
					flushItemEnv(env, server.registryAccess());
				}
			}, 20000L);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (result == null) throw new Json.ApiError(404, "not_found", "ไม่พบข้อมูลผู้เล่น (ต้องเคยเข้าเกมอย่างน้อยหนึ่งครั้ง)");
		List<ItemSources.Entry> entries = result.entries();
		entries.sort((x, y) -> {
			int c = x.area().compareTo(y.area());
			return c != 0 ? c : x.label().compareTo(y.label());
		});
		// Persist the exact stack locations for the web and for later decrements.
		PlayerItemStore.Snapshot snapshot = playerItems.record(uuid, a.name(),
				result.offline() ? "offline" : "online", entries);
		JsonArray arr = new JsonArray();
		for (ItemSources.Entry e : entries) arr.add(itemEntryView(e));
		JsonObject out = new JsonObject();
		out.add("items", arr);
		out.addProperty("offline", result.offline());
		out.addProperty("updatedAt", snapshot.updatedAt());
		out.addProperty("source", snapshot.source());
		out.addProperty("stacks", entries.size());
		out.addProperty("totalCount", snapshot.totalCount());
		out.addProperty("note", result.offline()
				? "โหมดออฟไลน์: อ่านจากข้อมูลที่บันทึกไว้ และโหลด chunk ในเขตแบบจำกัดจำนวน"
				: "เฉพาะกล่องในพื้นที่ที่โหลดอยู่ และเฉพาะพื้นที่ที่คุณเป็นเจ้าของหรือสมาชิก");
		Json.send(ex, 200, out);
	}

	/**
	 * GET /api/items — the cached player-items/<uuid>.json projection. This is
	 * intentionally read-only and fast; the authoritative refs are re-resolved
	 * when a sale or listing is committed.
	 */
	private void handlePlayerItems(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		UUID uuid = sessionUuid(a);
		JsonObject out = new JsonObject();
		Optional<PlayerItemStore.Snapshot> found = playerItems.get(uuid);
		out.addProperty("present", found.isPresent());
		out.addProperty("offline", found.map(s -> "offline".equals(s.source())).orElse(false));
		JsonArray arr = new JsonArray();
		if (found.isPresent()) {
			PlayerItemStore.Snapshot s = found.get();
			out.addProperty("updatedAt", s.updatedAt());
			out.addProperty("source", s.source());
			out.addProperty("name", s.name());
			out.addProperty("stacks", s.items().size());
			out.addProperty("totalCount", s.totalCount());
			for (ItemSources.Entry e : s.items()) arr.add(itemEntryView(e));
		} else {
			out.addProperty("updatedAt", 0L);
			out.addProperty("source", "none");
			out.addProperty("stacks", 0);
			out.addProperty("totalCount", 0);
		}
		out.add("items", arr);
		out.addProperty("note", found.isPresent()
				? "แคชจาก player-items/<uuid>.json"
				: "ยังไม่มีแคช กรุณาโหลดรายการของอีกครั้ง");
		Json.send(ex, 200, out);
	}

	/** Converts one resolved source (including its exact position) for the web. */
	private static JsonObject itemEntryView(ItemSources.Entry e) {
		JsonObject o = new JsonObject();
		o.addProperty("ref", e.ref());
		o.addProperty("area", e.area());
		o.addProperty("container", e.container());
		o.addProperty("slot", e.slot());
		o.addProperty("dimension", e.dimension());
		if (e.x() != null) o.addProperty("x", e.x());
		if (e.y() != null) o.addProperty("y", e.y());
		if (e.z() != null) o.addProperty("z", e.z());
		o.addProperty("itemId", e.itemId());
		o.addProperty("label", e.label());
		o.addProperty("count", e.count());
		return o;
	}

	// ---- offline item access helpers ----------------------------------------

	private record ItemEnv(ItemSources.Context context, OfflinePlayers.Session session) {
	}

	private record SourceResult(List<ItemSources.Entry> entries, boolean offline) {
	}

	private record SellTask(ShopManager.SellOutcome outcome, boolean offline) {
	}

	private record ClaimTask(ShopManager.ClaimOutcome outcome, boolean offline) {
	}

	/** Runs on the server thread. Opens the live player or the saved player data. */
	private ItemEnv openItemEnv(net.minecraft.server.MinecraftServer server, UUID uuid, String name) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player != null) return new ItemEnv(ItemSources.Context.online(player), null);
		var session = OfflinePlayers.open(server, uuid, name, server.registryAccess());
		if (session.isEmpty()) return null;
		OfflinePlayers.Session s = session.get();
		ServerLevel level = server.overworld();
		BlockPos near = PlayerPositions.get(uuid);
		if (near == null) near = BlockPos.ZERO;
		return new ItemEnv(ItemSources.Context.offline(uuid, level, s.inventory(), s.ender(), near), s);
	}

	private void flushItemEnv(ItemEnv env, RegistryAccess access) {
		if (env != null && env.session() != null) env.session().flush(access);
	}

	/**
	 * Refreshes the carried rows in the per-player JSON after a mutation. Older
	 * container rows are kept; a later /api/sources full scan updates those.
	 * Runs on the server thread, like the mutation itself.
	 */
	private void refreshCache(ItemEnv env, UUID uuid, String name) {
		if (env == null) return;
		try {
			playerItems.mergeCarried(uuid, name, ItemSources.listCarried(env.context()));
		} catch (RuntimeException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not refresh item cache for {}: {}", name, e.getMessage());
		}
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
			case ALREADY_LISTED -> new Json.ApiError(409, "already_listed", "เขตนี้มีรายการประมูลเปิดอยู่แล้ว");
			case ALREADY_OWNER -> new Json.ApiError(409, "already_owner", "คุณเป็นเจ้าของเขตนี้อยู่แล้ว");
			case BAD_REQUEST -> new Json.ApiError(400, "invalid_amount", "ข้อมูลไม่ถูกต้อง");
			case DISABLED -> new Json.ApiError(503, "market_disabled", "ระบบตลาดถูกปิดชั่วคราว");
			default -> new Json.ApiError(500, "internal_error", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์");
		};
	}

	// ---- player fixed-price market (separate from auctions) -----------------

	private void requirePlayerMarketEnabled() {
		requireMarketEnabled();
		if (playerMarket.isDisabled()) {
			throw new Json.ApiError(503, "market_disabled", "ตลาดผู้เล่นถูกปิดชั่วคราวเพราะไฟล์ข้อมูลเสีย");
		}
	}

	/** GET /api/market — active player listings plus the caller's own history. */
	private void handleMarket(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requirePlayerMarketEnabled();
		UUID uuid = sessionUuid(a);
		JsonArray listings = new JsonArray();
		for (PlayerMarket.Listing l : playerMarket.all()) {
			if (l.isActive()) listings.add(marketView(l, uuid));
		}
		JsonArray mine = new JsonArray();
		for (PlayerMarket.Listing l : playerMarket.recentForSeller(uuid.toString())) {
			mine.add(marketView(l, uuid));
		}
		JsonObject out = new JsonObject();
		out.addProperty("currency", config.currencyName);
		out.add("listings", listings);
		out.add("mine", mine);
		Json.send(ex, 200, out);
	}

	private JsonObject marketView(PlayerMarket.Listing l, UUID viewer) {
		JsonObject o = new JsonObject();
		o.addProperty("id", l.id);
		o.addProperty("sellerName", l.sellerName);
		o.addProperty("itemId", l.itemId);
		o.addProperty("count", l.count);
		o.addProperty("label", l.label);
		o.addProperty("price", l.price);
		o.addProperty("createdAt", l.createdAt);
		o.addProperty("status", l.status);
		boolean mine = l.seller.equals(viewer.toString());
		o.addProperty("mine", mine);
		o.addProperty("canBuy", l.isActive() && !mine);
		o.addProperty("canCancel", l.isActive() && mine);
		o.addProperty("boughtByYou", PlayerMarket.SOLD.equals(l.status) && buyerIs(l, viewer));
		return o;
	}

	private static boolean buyerIs(PlayerMarket.Listing l, UUID viewer) {
		return l.buyer != null && l.buyer.equals(viewer.toString());
	}

	/** POST /api/market {ref,count,price} — fixed-price listing from any source. */
	private void handleCreateListing(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		requirePlayerMarketEnabled();
		UUID uuid = sessionUuid(a);
		JsonObject body = Json.readObject(ex);
		String ref = Json.str(body, "ref");
		Long countValue = Json.integer(body, "count");
		Long priceValue = Json.integer(body, "price");
		if (ref == null || ref.isBlank()) throw new Json.ApiError(400, "invalid_source", "ต้องเลือกไอเท็มที่จะขาย");
		if (countValue == null || countValue < 1 || countValue > 2304) {
			throw new Json.ApiError(400, "invalid_quantity", "จำนวนไม่ถูกต้อง");
		}
		if (priceValue == null || priceValue < 1 || priceValue > EconomyManager.MAX_MONEY) {
			throw new Json.ApiError(400, "invalid_amount", "ราคาไม่ถูกต้อง");
		}
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		String[] result;
		try {
			result = GameBridge.call(server, () -> {
				ItemEnv env = openItemEnv(server, uuid, a.name());
				if (env == null) return new String[]{"offline_data", null};
				try {
					ItemSources.Taken taken = ItemSources.take(env.context(), regions, ref, null, countValue.intValue());
					if (taken == null) return new String[]{"invalid_source", null};
					String itemId = ItemCodecs.idOf(taken.stack());
					if (prices.isBlocked(itemId) || !ItemRegistryAdapter.exists(itemId)) {
						taken.restore().run();
						return new String[]{"invalid_item", null};
					}
					String json = ItemCodecs.encode(taken.stack(), server.registryAccess());
					if (json == null) {
						taken.restore().run();
						return new String[]{"io_error", null};
					}
					PlayerMarket.CreateOutcome created = playerMarket.create(new PlayerMarket.CreatePlan(
							uuid.toString(), a.name(), json, itemId, taken.stack().getCount(),
							ItemRegistryAdapter.label(itemId), priceValue));
					if (created.result() != PlayerMarket.Result.OK) {
						taken.restore().run();
						return new String[]{created.result().name(), null};
					}
					refreshCache(env, uuid, a.name());
					return new String[]{null, created.listingId()};
				} finally {
					flushItemEnv(env, server.registryAccess());
				}
			}, 20000L);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (result[0] != null) {
			switch (result[0]) {
				case "invalid_source" -> throw new Json.ApiError(409, "invalid_source", "ไอเท็มในช่องนั้นเปลี่ยนแล้ว กรุณารีเฟรช");
				case "invalid_item" -> throw new Json.ApiError(400, "invalid_item", "ไอเท็มนี้ลงขายไม่ได้");
				case "offline_data" -> throw new Json.ApiError(404, "not_found", "ไม่พบข้อมูลผู้เล่น (ต้องเคยเข้าเกมอย่างน้อยหนึ่งครั้ง)");
				default -> throw marketError(PlayerMarket.Result.valueOf(result[0]));
			}
		}
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("id", result[1]);
		Json.send(ex, 200, out);
	}

	/** POST /api/market/{id}/{buy|cancel} */
	private void handleMarketAction(HttpExchange ex, String method, String path) throws IOException {
		requireMethod(ex, method, "POST");
		Auth a = requireSession(ex);
		requirePlayerMarketEnabled();
		String rest = path.substring("/api/market/".length());
		int slash = rest.indexOf('/');
		if (slash <= 0 || slash == rest.length() - 1) {
			throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
		}
		String id = rest.substring(0, slash);
		String action = rest.substring(slash + 1);
		UUID uuid = sessionUuid(a);
		switch (action) {
			case "buy" -> {
				Json.readObject(ex);
				PlayerMarket.Result r = playerMarket.buy(uuid, a.name(), id);
				if (r != PlayerMarket.Result.OK) throw marketError(r);
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("balance", economy.balance(uuid));
				out.addProperty("mailboxCount", mailbox.count(uuid));
				Json.send(ex, 200, out);
			}
			case "cancel" -> {
				Json.readObject(ex);
				PlayerMarket.Result r = playerMarket.cancel(uuid, a.admin(), id);
				if (r != PlayerMarket.Result.OK) throw marketError(r);
				JsonObject out = new JsonObject();
				out.addProperty("ok", true);
				out.addProperty("mailboxCount", mailbox.count(uuid));
				Json.send(ex, 200, out);
			}
			default -> throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
		}
	}

	private Json.ApiError marketError(PlayerMarket.Result r) {
		return switch (r) {
			case NOT_FOUND -> new Json.ApiError(404, "not_found", "ไม่พบรายการขายนี้");
			case CLOSED -> new Json.ApiError(409, "listing_closed", "รายการนี้ปิดแล้ว");
			case OWN_LISTING -> new Json.ApiError(409, "own_listing", "ซื้อรายการของตัวเองไม่ได้");
			case LIMIT_REACHED -> new Json.ApiError(409, "limit_reached", "ลงขายครบจำนวนสูงสุดแล้ว");
			case INSUFFICIENT -> new Json.ApiError(409, "insufficient_funds", "ยอดเงินไม่พอ");
			case FROZEN -> new Json.ApiError(409, "account_frozen", "บัญชีนี้ถูกระงับการค้า");
			case FORBIDDEN -> new Json.ApiError(403, "forbidden", "ไม่มีสิทธิ์กับรายการนี้");
			case BAD_REQUEST -> new Json.ApiError(400, "invalid_amount", "ข้อมูลไม่ถูกต้อง");
			case DISABLED -> new Json.ApiError(503, "market_disabled", "ตลาดผู้เล่นถูกปิดชั่วคราว");
			default -> new Json.ApiError(500, "internal_error", "เกิดข้อผิดพลาดภายในเซิร์ฟเวอร์");
		};
	}

	// ---- consignments (player hands items to the server) --------------------

	/** POST /api/consign {ref,count} — moves items into the server auction pool. */
	private void handleConsign(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		if (consignments.isDisabled()) {
			throw new Json.ApiError(503, "market_disabled", "ระบบรับฝากของถูกปิดชั่วคราว");
		}
		UUID uuid = sessionUuid(a);
		JsonObject body = Json.readObject(ex);
		String ref = Json.str(body, "ref");
		Long countValue = Json.integer(body, "count");
		if (ref == null || ref.isBlank()) throw new Json.ApiError(400, "invalid_source", "ต้องเลือกไอเท็มที่จะส่งให้เซิร์ฟเวอร์");
		if (countValue == null || countValue < 1 || countValue > 2304) {
			throw new Json.ApiError(400, "invalid_quantity", "จำนวนไม่ถูกต้อง");
		}
		var server = ClaimMap.getServer();
		if (server == null) throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมยังไม่พร้อม");
		String[] result;
		try {
			result = GameBridge.call(server, () -> {
				ItemEnv env = openItemEnv(server, uuid, a.name());
				if (env == null) return new String[]{"offline_data", null};
				try {
					ItemSources.Taken taken = ItemSources.take(env.context(), regions, ref, null, countValue.intValue());
					if (taken == null) return new String[]{"invalid_source", null};
					String itemId = ItemCodecs.idOf(taken.stack());
					if (prices.isBlocked(itemId) || !ItemRegistryAdapter.exists(itemId)) {
						taken.restore().run();
						return new String[]{"invalid_item", null};
					}
					String json = ItemCodecs.encode(taken.stack(), server.registryAccess());
					if (json == null) {
						taken.restore().run();
						return new String[]{"io_error", null};
					}
					ConsignmentManager.Entry entry = consignments.add(new ConsignmentManager.Plan(
							uuid.toString(), a.name(), json, itemId, taken.stack().getCount(),
							ItemRegistryAdapter.label(itemId)));
					if (entry == null) {
						taken.restore().run();
						return new String[]{"io_error", null};
					}
					refreshCache(env, uuid, a.name());
					return new String[]{null, entry.id};
				} finally {
					flushItemEnv(env, server.registryAccess());
				}
			}, 20000L);
		} catch (GameBridge.GameUnavailableException e) {
			throw new Json.ApiError(503, "game_unavailable", "เซิร์ฟเวอร์เกมไม่ตอบสนอง กรุณาลองใหม่");
		}
		if (result[0] != null) {
			switch (result[0]) {
				case "invalid_source" -> throw new Json.ApiError(409, "invalid_source", "ไอเท็มในช่องนั้นเปลี่ยนแล้ว กรุณารีเฟรช");
				case "invalid_item" -> throw new Json.ApiError(400, "invalid_item", "ไอเท็มนี้ส่งให้เซิร์ฟเวอร์ไม่ได้");
				case "offline_data" -> throw new Json.ApiError(404, "not_found", "ไม่พบข้อมูลผู้เล่น (ต้องเคยเข้าเกมอย่างน้อยหนึ่งครั้ง)");
				default -> throw new Json.ApiError(500, "internal_error", "บันทึกของฝากไม่สำเร็จ");
			}
		}
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("id", result[1]);
		Json.send(ex, 200, out);
	}

	/** GET /api/consign — the caller's items waiting for an admin auction. */
	private void handleConsignList(HttpExchange ex) throws IOException {
		Auth a = requireSession(ex);
		UUID uuid = sessionUuid(a);
		JsonArray arr = new JsonArray();
		for (ConsignmentManager.Entry e : consignments.pendingFrom(uuid.toString())) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.id);
			o.addProperty("itemId", e.itemId);
			o.addProperty("count", e.count);
			o.addProperty("label", e.label);
			o.addProperty("t", e.depositedAt);
			arr.add(o);
		}
		JsonObject out = new JsonObject();
		out.add("entries", arr);
		Json.send(ex, 200, out);
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

	private String queryParam(HttpExchange ex, String name) {
		String q = ex.getRequestURI().getQuery();
		if (q == null) return null;
		String prefix = name + "=";
		for (String part : q.split("&")) {
			if (part.startsWith(prefix)) {
				try {
					return java.net.URLDecoder.decode(part.substring(prefix.length()), java.nio.charset.StandardCharsets.UTF_8);
				} catch (Exception e) {
					return part.substring(prefix.length());
				}
			}
		}
		return null;
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
		out.add("consignments", admin.consignmentsForAdmin(a.name(), clientIp(ex)));
		Json.send(ex, 200, out);
	}

	/** POST /api/admin/auctions {consignmentId,startPrice,buyout,hours} */
	private void handleAdminCreateAuction(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/auctions");
		JsonObject body = Json.readObject(ex);
		String consignmentId = Json.str(body, "consignmentId");
		Long start = Json.integer(body, "startPrice");
		Long buyoutValue = Json.integer(body, "buyout");
		Long hoursValue = Json.integer(body, "hours");
		if (consignmentId == null || consignmentId.isBlank()) {
			throw new Json.ApiError(400, "bad_request", "ต้องเลือกของฝากจากผู้เล่น");
		}
		if (start == null || start < 0 || start > EconomyManager.MAX_MONEY) {
			throw new Json.ApiError(400, "invalid_amount", "ราคาเริ่มต้นไม่ถูกต้อง (ต้องไม่ติดลบ)");
		}
		Long buyout = buyoutValue == null || buyoutValue <= 0 ? null : buyoutValue;
		int hours = hoursValue == null ? 0 : (int) Math.max(0, Math.min(24 * 7, hoursValue));
		AdminService.ServerAuctionOutcome o = admin.createServerAuction(
				a.name(), clientIp(ex), consignmentId, start, buyout, hours);
		if (o.result() != AdminService.Result.OK) throw adminError(o.result());
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("id", o.auctionId());
		Json.send(ex, 200, out);
	}

	/** GET /api/admin/cell-auctions — active and closed cell auctions only. */
	private void handleAdminCellAuctions(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/cell-auctions");
		JsonArray arr = new JsonArray();
		for (JsonElement el : admin.auctionsForAdmin(a.name(), clientIp(ex))) {
			JsonObject o = el.getAsJsonObject();
			if (o.has("kind") && "CELL".equals(o.get("kind").getAsString())) arr.add(o);
		}
		JsonObject out = new JsonObject();
		out.add("auctions", arr);
		Json.send(ex, 200, out);
	}

	/** POST /api/admin/cell-auctions {cellId,startPrice,buyout,hours,reason} */
	private void handleAdminCreateCellAuction(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/cell-auctions");
		JsonObject body = Json.readObject(ex);
		String cellId = Json.str(body, "cellId");
		String reason = Json.str(body, "reason");
		Long start = Json.integer(body, "startPrice");
		Long buyoutValue = Json.integer(body, "buyout");
		Long hoursValue = Json.integer(body, "hours");
		if (cellId == null || cellId.isBlank()) {
			throw new Json.ApiError(400, "bad_request", "ต้องระบุเขตที่จะประมูล เช่น 0,0");
		}
		if (start == null || start < 0 || start > EconomyManager.MAX_MONEY) {
			throw new Json.ApiError(400, "invalid_amount", "ราคาเริ่มต้นไม่ถูกต้อง (ต้องไม่ติดลบ)");
		}
		if (!AdminService.validReason(reason)) {
			throw new Json.ApiError(400, "bad_request", "ต้องระบุเหตุผล 3-200 ตัวอักษร");
		}
		Long buyout = buyoutValue == null || buyoutValue <= 0 ? null : buyoutValue;
		int hours = hoursValue == null ? 0 : (int) Math.max(0, Math.min(24 * 7, hoursValue));
		AdminService.ServerAuctionOutcome o = admin.createCellAuction(
				a.name(), clientIp(ex), cellId, start, buyout, hours, reason);
		if (o.result() != AdminService.Result.OK) throw adminError(o.result());
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("id", o.auctionId());
		Json.send(ex, 200, out);
	}

	private void handleAdminConsignments(HttpExchange ex) throws IOException {
		Auth a = requireAdmin(ex, "/api/admin/consignments");
		JsonObject out = new JsonObject();
		out.add("consignments", admin.consignmentsForAdmin(a.name(), clientIp(ex)));
		Json.send(ex, 200, out);
	}

	/** GET /api/admin/cells — every claimed cell with its owner, for transfers. */
	private void handleAdminCells(HttpExchange ex) throws IOException {
		requireAdmin(ex, "/api/admin/cells");
		JsonArray arr = new JsonArray();
		for (Map.Entry<String, String> e : regions.ownerMap().entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.getKey());
			o.addProperty("owner", e.getValue());
			arr.add(o);
		}
		JsonObject out = new JsonObject();
		out.add("cells", arr);
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
		if (rest.startsWith("worlds/")) {
			handleAdminWorldAction(ex, method, path, rest.substring("worlds/".length()));
			return;
		}
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

	private void handleAdminWorlds(HttpExchange ex) throws IOException {
		requireAdmin(ex, "/api/admin/worlds");
		Json.send(ex, 200, worldsView(worlds.snapshot()));
	}

	private void handleAdminWorldsScan(HttpExchange ex) throws IOException {
		requireAdmin(ex, "/api/admin/worlds/scan");
		try {
			Json.send(ex, 200, worldsView(worlds.scanNow()));
		} catch (Exception e) {
			throw worldError(e);
		}
	}

	private void handleAdminWorldAction(HttpExchange ex, String method, String path, String rest)
			throws IOException {
		requireMethod(ex, method, "POST");
		Auth a = requireAdmin(ex, path);
		int slash = rest.indexOf('/');
		if (slash <= 0 || slash == rest.length() - 1) {
			throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
		}
		String name = rest.substring(0, slash);
		String action = rest.substring(slash + 1);
		try {
			switch (action) {
				case "load" -> Json.send(ex, 200, worldView(worlds.load(name)));
				case "unload" -> Json.send(ex, 200, worldView(worlds.unload(name)));
				case "tp" -> {
					JsonObject body = Json.readObject(ex);
					String player = Json.str(body, "player");
					if (player == null || player.isBlank()) player = a.name();
					Json.send(ex, 200, worldView(worlds.teleport(name, player)));
				}
				default -> throw new Json.ApiError(404, "not_found", "ไม่พบเส้นทางที่เรียก");
			}
		} catch (Json.ApiError e) {
			throw e;
		} catch (Exception e) {
			throw worldError(e);
		}
	}

	private JsonObject worldsView(List<WorldManager.WorldInfo> list) {
		JsonObject out = new JsonObject();
		out.addProperty("directory", worlds.directory());
		out.addProperty("inventoryPolicy", worlds.inventoryPolicy());
		out.addProperty("scanSeconds", worlds.scanSeconds());
		out.addProperty("idleSeconds", worlds.idleSeconds());
		JsonArray arr = new JsonArray();
		for (WorldManager.WorldInfo info : list) arr.add(worldView(info));
		out.add("worlds", arr);
		return out;
	}

	private JsonObject worldView(WorldManager.WorldInfo info) {
		JsonObject o = new JsonObject();
		o.addProperty("name", info.name());
		o.addProperty("folder", info.folder());
		o.addProperty("state", info.state().name());
		o.addProperty("loaded", info.loaded());
		o.addProperty("players", info.players());
		o.addProperty("present", info.present());
		o.addProperty("commandAvailable", info.commandAvailable());
		o.addProperty("commandConflict", info.commandConflict());
		o.addProperty("error", info.error());
		return o;
	}

	private Json.ApiError worldError(Exception e) {
		String message = e.getMessage() == null ? "จัดการโลกไม่สำเร็จ" : e.getMessage();
		if (e instanceof IllegalArgumentException) {
			return new Json.ApiError(404, "world_not_found", message);
		}
		return new Json.ApiError(409, "world_error", message);
	}

	private Json.ApiError adminError(AdminService.Result r) {
		return switch (r) {
			case BAD_REQUEST -> new Json.ApiError(400, "bad_request", "ข้อมูลไม่ถูกต้อง");
			case NO_ACCOUNT -> new Json.ApiError(404, "player_not_found", "ไม่พบบัญชีผู้เล่นนี้");
			case INSUFFICIENT -> new Json.ApiError(409, "insufficient_funds", "ยอดเงินไม่พอ (ห้ามติดลบ)");
			case NOT_FOUND -> new Json.ApiError(404, "not_found", "ไม่พบรายการ");
			case ALREADY_LISTED -> new Json.ApiError(409, "already_listed", "เขตนี้มีรายการประมูลเปิดอยู่แล้ว");
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

	private void handlePriceTrends(HttpExchange ex) throws IOException {
		List<PriceService.ItemPriceDetail> details = prices.detailsSnapshot();
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("currency", config.currencyName);
		JsonArray arr = new JsonArray();
		for (PriceService.ItemPriceDetail d : details) {
			if (d.blocked()) continue;
			if (!ItemRegistryAdapter.exists(d.id())) continue;
			JsonObject item = new JsonObject();
			item.addProperty("itemId", d.id());
			item.addProperty("base", d.nominalBase());
			item.addProperty("effectiveBase", d.effectiveBase());
			item.addProperty("buy", d.buyPrice());
			item.addProperty("sell", d.sellPrice());
			item.addProperty("multiplier", Math.round(d.multiplier() * 100.0) / 100.0);
			item.addProperty("sold", d.soldUnits());
			JsonArray hist = new JsonArray();
			for (PriceService.PriceHistoryPoint p : prices.getHistory(d.id())) {
				JsonObject po = new JsonObject();
				po.addProperty("t", p.t());
				po.addProperty("price", p.price());
				po.addProperty("sold", p.sold());
				hist.add(po);
			}
			item.add("history", hist);
			arr.add(item);
		}
		out.add("items", arr);
		Json.send(ex, 200, out);
	}

	private void handleAdminPricesList(HttpExchange ex) throws IOException {
		requireAdmin(ex, "/api/admin/prices");
		List<PriceService.ItemPriceDetail> details = prices.detailsSnapshot();
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("currency", config.currencyName);
		out.addProperty("stale", prices.staleForApi());
		out.addProperty("updatedAt", prices.updatedAt());
		JsonArray arr = new JsonArray();
		for (PriceService.ItemPriceDetail d : details) {
			if (!ItemRegistryAdapter.exists(d.id())) continue;
			JsonObject item = new JsonObject();
			item.addProperty("itemId", d.id());
			item.addProperty("base", d.nominalBase());
			item.addProperty("effectiveBase", d.effectiveBase());
			item.addProperty("buy", d.buyPrice());
			item.addProperty("sell", d.sellPrice());
			item.addProperty("multiplier", Math.round(d.multiplier() * 100.0) / 100.0);
			item.addProperty("sold", d.soldUnits());
			item.addProperty("blocked", d.blocked());
			item.addProperty("overridden", d.overridden());
			arr.add(item);
		}
		out.add("items", arr);
		Json.send(ex, 200, out);
	}

	private void handleAdminSales(HttpExchange ex) throws IOException {
		requireAdmin(ex, "/api/admin/sales");
		int limit = queryLimit(ex, 100);
		String fPlayer = queryParam(ex, "player");
		String fItem = queryParam(ex, "item");
		SalesTracker st = salesTracker != null ? salesTracker : admin.salesTracker();
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		if (st == null) {
			out.add("sales", new JsonArray());
			out.add("summary", new JsonObject());
			Json.send(ex, 200, out);
			return;
		}
		SalesTracker.SalesSummary summary = st.summary();
		JsonObject sumObj = new JsonObject();
		sumObj.addProperty("totalSoldItems", summary.totalSoldItems());
		sumObj.addProperty("totalCoinsPaid", summary.totalCoinsPaid());
		sumObj.addProperty("totalTransactions", summary.totalTransactions());
		com.google.gson.Gson gson = new com.google.gson.Gson();
		sumObj.add("topItems", (com.google.gson.JsonElement) gson.toJsonTree(summary.topItems()));
		sumObj.add("topSellers", (com.google.gson.JsonElement) gson.toJsonTree(summary.topSellers()));
		out.add("summary", sumObj);

		JsonArray salesArr = new JsonArray();
		for (SalesTracker.SaleRecord r : st.tail(limit, fPlayer, fItem)) {
			JsonObject s = new JsonObject();
			s.addProperty("id", r.id());
			s.addProperty("t", r.t());
			s.addProperty("player", r.player());
			s.addProperty("uuid", r.uuid());
			s.addProperty("itemId", r.itemId());
			s.addProperty("label", r.label());
			s.addProperty("count", r.count());
			s.addProperty("earned", r.earned());
			s.addProperty("unitPrice", r.unitPrice());
			s.addProperty("source", r.source());
			salesArr.add(s);
		}
		out.add("sales", salesArr);
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
