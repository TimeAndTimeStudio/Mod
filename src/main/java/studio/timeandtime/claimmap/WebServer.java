package studio.timeandtime.claimmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server on 127.0.0.1:8085 (behind Nginx reverse proxy + TLS).
 * Every request requires "Authorization: Bearer <token>" (plan.md: API).
 */
public final class WebServer {
	private final ClaimMapConfig config;
	private final RegionManager regions;
	private final MapRenderer maps;
	private final LinkManager links;
	private HttpServer server;

	public WebServer(ClaimMapConfig config, RegionManager regions, MapRenderer maps, LinkManager links) {
		this.config = config;
		this.regions = regions;
		this.maps = maps;
		this.links = links;
	}

	public void start() {
		try {
			server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
			server.createContext("/api", this::handle);
			server.setExecutor(Executors.newCachedThreadPool());
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
		try {
			switch (method + " " + path) {
				case "GET /api/me" -> handleMe(ex);
				case "GET /api/cells" -> handleCells(ex);
				case "POST /api/link" -> handleLink(ex);
				case "POST /api/command" -> handleCommand(ex);
				default -> {
					if (method.equals("GET") && path.startsWith("/api/map/")) handleMap(ex, path);
					else if (path.startsWith("/api/cells/")) handleCellAction(ex, method, path);
					else send(ex, 404, "Not found");
				}
			}
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[claimmap] API error {} {} -> {}", method, path, e.toString());
			send(ex, 500, "Internal error: " + e);
		}
	}

	// ---- auth ---------------------------------------------------------------

	private String bearer(HttpExchange ex) {
		Headers h = ex.getRequestHeaders();
		String auth = h.getFirst("Authorization");
		if (auth == null) return null;
		String[] parts = auth.split("\\s+");
		if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) return null;
		return parts[1];
	}

	private String requireBearer(HttpExchange ex) throws IOException {
		String token = bearer(ex);
		if (token == null) {
			send(ex, 401, "Missing Authorization: Bearer <token>");
			return null;
		}
		return token;
	}

	private boolean isMaster(String token) {
		return token != null && token.equals(config.token);
	}

	// ---- handlers -----------------------------------------------------------

	private void handleMe(HttpExchange ex) throws IOException {
		String token = requireBearer(ex);
		if (token == null) return;
		if (isMaster(token)) {
			sendJson(ex, 200, "{\"name\":\"admin\"}");
			return;
		}
		LinkManager.Session s = links.resolve(token);
		if (s == null) {
			send(ex, 401, "Invalid token");
			return;
		}
		sendJson(ex, 200, json(s.name()));
	}

	private void handleCells(HttpExchange ex) throws IOException {
		String token = requireBearer(ex);
		if (token == null) return;
		if (!authorized(token)) {
			send(ex, 401, "Invalid token");
			return;
		}
		Map<String, String> owners = regions.ownerMap();
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> e : owners.entrySet()) {
			if (!first) sb.append(',');
			first = false;
			sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
		}
		sb.append('}');
		sendJson(ex, 200, sb.toString());
	}

	private void handleLink(HttpExchange ex) throws IOException {
		String token = requireBearer(ex);
		if (token == null) return;
		if (!authorized(token)) {
			send(ex, 401, "Invalid token");
			return;
		}
		JsonObject body = readJson(ex);
		String code = body == null ? null : body.get("code") == null ? null : body.get("code").getAsString();
		if (code == null || !code.matches("\\d{6}")) {
			send(ex, 400, "Expected {\"code\":\"123456\"}");
			return;
		}
		LinkManager.Session s = links.exchange(code);
		if (s == null) {
			send(ex, 403, "Invalid or expired code");
			return;
		}
		sendJson(ex, 200, "{\"token\": " + quote(s.token()) + ", \"name\": " + quote(s.name()) + "}");
	}

	private void handleCommand(HttpExchange ex) throws IOException {
		String token = requireBearer(ex);
		if (token == null) return;
		if (!isMaster(token)) {
			send(ex, 403, "Admin (master token) only");
			return;
		}
		JsonObject body = readJson(ex);
		String cmd = body == null ? null : body.get("command") == null ? null : body.get("command").getAsString();
		if (cmd == null || cmd.isEmpty()) {
			send(ex, 400, "Expected {\"command\":\"say hi\"}");
			return;
		}
		var server = ClaimMap.getServer();
		if (server == null) {
			send(ex, 503, "Server not running");
			return;
		}
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
		sendJson(ex, 200, "{\"ok\":true}");
	}

	private void handleMap(HttpExchange ex, String path) throws IOException {
		String token = requireBearer(ex);
		if (token == null) return;
		if (!authorized(token)) {
			send(ex, 401, "Invalid token");
			return;
		}
		// /api/map/{x},{z}.png  (x,z may be negative)
		String rest = path.substring("/api/map/".length());
		if (!rest.endsWith(".png")) {
			send(ex, 404, "Not found");
			return;
		}
		String id = rest.substring(0, rest.length() - 4);
		int[] cell = parseCellId(id);
		if (cell == null) {
			send(ex, 400, "Bad cell id");
			return;
		}
		byte[] png = maps.encodedPng(cell[0], cell[1]);
		if (png == null) {
			sendCode(ex, 204);
			return;
		}
		ex.getResponseHeaders().set("Content-Type", "image/png");
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(200, png.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(png);
		}
	}

	private void handleCellAction(HttpExchange ex, String method, String path) throws IOException {
		// /api/cells/{x},{z}[/claim|/members or DELETE]
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
		int[] cell;
		try {
			cell = parseCellId(id);
		} catch (Exception e) {
			cell = null;
		}
		if (cell == null) {
			send(ex, 400, "Bad cell id");
			return;
		}
		String token = requireBearer(ex);
		if (token == null) return;
		if (!authorized(token)) {
			send(ex, 401, "Invalid token");
			return;
		}
		LinkManager.Session sess = isMaster(token) ? null : links.resolve(token);

		switch (action) {
			case "claim" -> {
				if (method.equals("POST")) {
					if (sess == null) {
						send(ex, 403, "Please enter a /link code first (session token required)");
						return;
					}
					RegionManager.ClaimResult r = regions.claim(cell[0], cell[1], sess.uuid(), sess.name());
					switch (r) {
						case OK -> sendJson(ex, 200, "{\"ok\":true}");
						case ALREADY_CLAIMED -> send(ex, 409, "That cell is already claimed");
						case COMMON_AREA -> send(ex, 403, "This is the protected spawn area, cannot claim");
						case LIMIT_REACHED -> send(ex, 403, "Cell claim limit reached");
					}
				} else {
					send(ex, 405, "Method not allowed");
				}
			}
			case "members" -> {
				if (!method.equals("POST")) {
					send(ex, 405, "Method not allowed");
					return;
				}
				if (sess == null) {
					send(ex, 403, "Admin: master token");
					return;
				}
				JsonObject body = readJson(ex);
				String mUuid = body == null ? null : body.has("uuid") ? body.get("uuid").getAsString() : null;
				String mName = body == null ? null : body.has("name") ? body.get("name").getAsString() : "<member>";
				if (mUuid == null) {
					send(ex, 400, "Expected {\"uuid\":\"...\",\"name\":\"...\"}");
					return;
				}
				boolean ok = regions.addMember(id, sess.uuid(), mUuid, mName);
				sendJson(ex, ok ? 200 : 403, ok ? "{\"ok\":true}" : "{\"ok\":false}");
			}
			default -> {
				if (method.equals("DELETE")) {
					boolean admin = isMaster(token);
					String byUuid = admin ? "admin" : sess == null ? "" : sess.uuid();
					if (!admin && sess == null) {
						send(ex, 403, "Please enter a /link code first");
						return;
					}
					boolean ok = regions.unclaim(id, byUuid, admin);
					sendJson(ex, ok ? 200 : 403, ok ? "{\"ok\":true}" : "{\"ok\":false}");
				} else {
					send(ex, 405, "Method not allowed");
				}
			}
		}
	}

	// ---- helpers ------------------------------------------------------------

	private boolean authorized(String token) {
		return isMaster(token) || links.resolve(token) != null;
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

	private static JsonObject readJson(HttpExchange ex) throws IOException {
		byte[] body = ex.getRequestBody().readAllBytes();
		if (body.length == 0) return null;
		try {
			JsonElement el = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String json(String name) {
		return "{\"name\": " + quote(name == null ? "" : name) + "}";
	}

	private static String quote(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"' || c == '\\') sb.append('\\');
			sb.append(c);
		}
		return sb.append('"').toString();
	}

	private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
		byte[] b = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, b.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(b);
		}
	}

	private static void send(HttpExchange ex, int code, String text) throws IOException {
		byte[] b = text.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		ex.sendResponseHeaders(code, b.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(b);
		}
	}

	private static void sendCode(HttpExchange ex, int code) throws IOException {
		ex.sendResponseHeaders(code, -1);
		ex.close();
	}
}
