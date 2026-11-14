package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small HTTP/JSON helper shared by every endpoint (PHASES.md section E):
 * bounded body reading, uniform {@code {"error","message"}} responses and
 * integer-only number parsing.
 */
final class Json {
	/** Maximum accepted request body size (E). */
	static final int MAX_BODY_BYTES = 16 * 1024;

	private static final Gson GSON = new Gson();

	/** Error carrying the HTTP status + machine readable code. */
	static final class ApiError extends RuntimeException {
		final int status;
		final String code;
		final Map<String, String> headers = new LinkedHashMap<>();
		final Map<String, Object> extra = new LinkedHashMap<>();

		ApiError(int status, String code, String message) {
			super(message);
			this.status = status;
			this.code = code;
		}

		ApiError header(String name, String value) {
			headers.put(name, value);
			return this;
		}

		ApiError extra(String name, Object value) {
			extra.put(name, value);
			return this;
		}
	}

	private Json() {
	}

	// ---- request ------------------------------------------------------------

	/** Reads a JSON object body, enforcing the 16 KB limit while reading. */
	static JsonObject readObject(HttpExchange ex) {
		byte[] body = readLimited(ex);
		if (body.length == 0) throw new ApiError(400, "bad_request", "ไม่มีข้อมูลที่ส่งมา");
		JsonElement el;
		try {
			el = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new ApiError(400, "bad_request", "รูปแบบ JSON ไม่ถูกต้อง");
		}
		if (el == null || !el.isJsonObject()) {
			throw new ApiError(400, "bad_request", "ข้อมูลต้องเป็น JSON object");
		}
		return el.getAsJsonObject();
	}

	private static byte[] readLimited(HttpExchange ex) {
		try (InputStream in = ex.getRequestBody()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			int total = 0;
			while ((n = in.read(buf)) != -1) {
				total += n;
				if (total > MAX_BODY_BYTES) {
					throw new ApiError(413, "body_too_large", "ข้อมูลที่ส่งใหญ่เกิน 16 KB");
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} catch (ApiError e) {
			throw e;
		} catch (IOException e) {
			throw new ApiError(400, "bad_request", "อ่านข้อมูลไม่สำเร็จ");
		}
	}

	/** String field, or null when missing / not a JSON string. */
	static String str(JsonObject obj, String field) {
		JsonElement el = obj.get(field);
		if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) return null;
		return el.getAsString();
	}

	/**
	 * Integer field as {@code long}, or null when missing / not a JSON integer.
	 * Rejects floats, strings, exponents and values outside {@code long}.
	 */
	static Long integer(JsonObject obj, String field) {
		JsonElement el = obj.get(field);
		if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return null;
		String raw = el.getAsString();
		if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) return null;
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---- response -----------------------------------------------------------

	static void send(HttpExchange ex, int status, JsonElement body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	static void send(HttpExchange ex, int status, boolean ok) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("ok", ok);
		send(ex, status, o);
	}

	static JsonObject ok() {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		return o;
	}

	static void sendError(HttpExchange ex, ApiError e) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("error", e.code);
		o.addProperty("message", e.getMessage());
		for (Map.Entry<String, Object> x : e.extra.entrySet()) {
			Object v = x.getValue();
			if (v instanceof Number n) o.addProperty(x.getKey(), n);
			else o.addProperty(x.getKey(), String.valueOf(v));
		}
		for (Map.Entry<String, String> h : e.headers.entrySet()) {
			ex.getResponseHeaders().set(h.getKey(), h.getValue());
		}
		try {
			send(ex, e.status, o);
		} catch (IOException ignored) {
			// client went away
		}
	}

	static void sendError(HttpExchange ex, int status, String code, String message) throws IOException {
		sendError(ex, new ApiError(status, code, message));
	}

	static void sendNoContent(HttpExchange ex) throws IOException {
		ex.sendResponseHeaders(204, -1);
		ex.close();
	}

	static String urlEncode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
