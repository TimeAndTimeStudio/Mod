package studio.timeandtime.claimmap.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.timeandtime.claimmap.ClaimMapConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pulls real-world data from the internet so the lobby can mirror the outside
 * world instead of the host machine:
 *
 * <ul>
 *   <li><b>Time</b> is read from a JSON time API (default
 *       {@code timeapi.io}) and, when that fails, from the HTTP
 *       {@code Date} header of a normal website. The external epoch is then
 *       advanced locally with {@link System#nanoTime()}, so the lobby clock
 *       does not depend on the machine's wall clock at all. Only if every
 *       source has failed since startup does it fall back to the host clock.</li>
 *   <li><b>Weather</b> is read from Open-Meteo (no API key) for the configured
 *       coordinates and mapped to a Minecraft weather state.</li>
 * </ul>
 *
 * <p>All HTTP work runs on a daemon scheduler, never on the server thread.
 * Failures are logged once per streak and simply keep the last known value.
 */
public final class RealWorldSync {
	public enum Weather {
		CLEAR, RAIN, THUNDER
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-realworld");
	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

	private final ClaimMapConfig config;
	private final ZoneId zone;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(6))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private volatile long timeBaseMillis;
	private volatile long timeBaseNanos;
	private volatile String timeSource = "host clock";
	private volatile boolean timeKnown;

	private volatile Weather weather = Weather.CLEAR;
	private volatile boolean weatherKnown;
	private volatile int weatherCode = -1;

	private ScheduledExecutorService executor;
	private boolean timeFailureLogged;
	private boolean weatherFailureLogged;

	public RealWorldSync(ClaimMapConfig config) {
		this.config = config;
		this.zone = resolveZone(config.lobbyTimeZone);
	}

	private static ZoneId resolveZone(String raw) {
		String value = raw == null ? "" : raw.trim();
		try {
			return ZoneId.of(value);
		} catch (Exception e) {
			LOGGER.warn("[realworld] Unknown lobbyTimeZone '{}', using UTC+7", value);
			return ZoneId.of("UTC+7");
		}
	}

	/** Zone used to convert the epoch into a lobby time-of-day. */
	public ZoneId zone() {
		return zone;
	}

	/**
	 * Current real-world epoch millis. Advances from the last external reading
	 * using a monotonic clock; falls back to the host clock before the first
	 * successful fetch or when real time is disabled.
	 */
	public long epochMillis() {
		if (!config.lobbyRealTime || !timeKnown) {
			return System.currentTimeMillis();
		}
		long baseNanos = timeBaseNanos;
		long baseMillis = timeBaseMillis;
		return baseMillis + (System.nanoTime() - baseNanos) / 1_000_000L;
	}

	/** Current real time as a zoned date-time in the configured lobby zone. */
	public ZonedDateTime now() {
		return Instant.ofEpochMilli(epochMillis()).atZone(zone());
	}

	public boolean timeKnown() {
		return timeKnown;
	}

	public String timeSource() {
		return timeSource;
	}

	public Weather weather() {
		return weather;
	}

	public boolean weatherKnown() {
		return weatherKnown;
	}

	public int weatherCode() {
		return weatherCode;
	}

	/** Starts the background pollers. Safe to call more than once. */
	public synchronized void start() {
		if (executor != null) return;
		executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "timesmp-realworld");
			t.setDaemon(true);
			return t;
		});
		if (config.lobbyRealTime) {
			executor.execute(this::syncTime);
			executor.scheduleWithFixedDelay(this::syncTime, config.lobbyTimeSyncSeconds,
					config.lobbyTimeSyncSeconds, TimeUnit.SECONDS);
		}
		if (config.lobbyWeather) {
			executor.execute(this::syncWeather);
			executor.scheduleWithFixedDelay(this::syncWeather, config.lobbyWeatherSyncSeconds,
					config.lobbyWeatherSyncSeconds, TimeUnit.SECONDS);
		}
	}

	public synchronized void stop() {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	/** Forces an immediate time refresh (used by tests and the startup path). */
	public void syncTimeNow() {
		syncTime();
	}

	/** Forces an immediate weather refresh. */
	public void syncWeatherNow() {
		syncWeather();
	}

	private void syncTime() {
		try {
			long epochMillis = fetchExternalEpochMillis();
			long nowNanos = System.nanoTime();
			timeBaseMillis = epochMillis;
			timeBaseNanos = nowNanos;
			boolean wasKnown = timeKnown;
			timeKnown = true;
			timeFailureLogged = false;
			if (!wasKnown) {
				LOGGER.info("[realworld] Real time source: {} (epoch {})", timeSource,
						Instant.ofEpochMilli(epochMillis));
			}
		} catch (Exception e) {
			if (!timeFailureLogged) {
				timeFailureLogged = true;
				LOGGER.warn("[realworld] Could not read external time, keeping {}: {}", timeSource, e.toString());
			}
		}
	}

	private void syncWeather() {
		if (!config.lobbyWeather) return;
		try {
			int code = fetchWeatherCode();
			Weather mapped = mapWeather(code);
			int previous = weatherCode;
			weatherCode = code;
			weather = mapped;
			weatherKnown = true;
			weatherFailureLogged = false;
			if (previous != code) {
				LOGGER.info("[realworld] Weather at {},{}: code {} -> {}",
						config.lobbyWeatherLatitude, config.lobbyWeatherLongitude, code, mapped);
			}
		} catch (Exception e) {
			if (!weatherFailureLogged) {
				weatherFailureLogged = true;
				LOGGER.warn("[realworld] Could not read real weather, keeping {}: {}", weather, e.toString());
			}
		}
	}

	// ---- time sources ------------------------------------------------------

	private long fetchExternalEpochMillis() throws Exception {
		StringBuilder errors = new StringBuilder();
		String primary = config.lobbyTimeUrl == null ? "" : config.lobbyTimeUrl.trim();
		if (!primary.isEmpty()) {
			try {
				long millis = fetchJsonTime(primary);
				timeSource = "JSON " + hostOf(primary);
				return millis;
			} catch (Exception e) {
				errors.append("primary: ").append(e.getMessage()).append("; ");
			}
		}
		String fallback = config.lobbyTimeFallbackUrl == null ? "" : config.lobbyTimeFallbackUrl.trim();
		if (!fallback.isEmpty()) {
			try {
				long millis = fetchDateHeader(fallback);
				timeSource = "HTTP Date " + hostOf(fallback);
				return millis;
			} catch (Exception e) {
				errors.append("date header: ").append(e.getMessage()).append("; ");
			}
		}
		throw new IllegalStateException(errors.length() == 0 ? "no time source configured" : errors.toString());
	}

	/** Reads a JSON time API. Supports {@code unixtime} (seconds), ISO datetimes and date fields. */
	private long fetchJsonTime(String url) throws Exception {
		String body = get(url, "application/json");
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();

		JsonElement unix = root.get("unixtime");
		if (unix != null && unix.isJsonPrimitive() && unix.getAsJsonPrimitive().isNumber()) {
			long raw = unix.getAsLong();
			return raw > 100_000_000_000L ? raw : raw * 1000L;
		}
		for (String key : new String[]{"datetime", "utc_datetime", "utcDateTime", "dateTime"}) {
			JsonElement iso = root.get(key);
			if (iso != null && iso.isJsonPrimitive()) {
				try {
					return Instant.parse(iso.getAsString()).toEpochMilli();
				} catch (Exception ignored) {
					try {
						return ZonedDateTime.parse(iso.getAsString()).toInstant().toEpochMilli();
					} catch (Exception ignoredToo) {
						// try the next field
					}
				}
			}
		}
		if (root.has("year") && root.has("month") && root.has("day")) {
			int year = root.get("year").getAsInt();
			int month = root.get("month").getAsInt();
			int day = root.get("day").getAsInt();
			int hour = root.has("hour") ? root.get("hour").getAsInt() : 0;
			int minute = root.has("minute") ? root.get("minute").getAsInt() : 0;
			int second = root.has("seconds") ? root.get("seconds").getAsInt()
					: root.has("second") ? root.get("second").getAsInt() : 0;
			int milli = root.has("milliSeconds") ? root.get("milliSeconds").getAsInt() : 0;
			ZoneId apiZone = zone();
			JsonElement tz = root.get("timeZone");
			if (tz != null && tz.isJsonPrimitive()) {
				try {
					apiZone = ZoneId.of(tz.getAsString());
				} catch (Exception ignored) {
					// keep the configured lobby zone
				}
			}
			return ZonedDateTime.of(year, month, day, hour, minute, second, milli * 1_000_000, apiZone)
					.toInstant().toEpochMilli();
		}
		throw new IllegalStateException("no timestamp field in response");
	}

	/** Reads the standard HTTP {@code Date} response header (1 second resolution). */
	private long fetchDateHeader(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(8))
				.header("User-Agent", "TimeSMP/1.0 (+Minecraft server)")
				.GET()
				.build();
		HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
		if (response.statusCode() < 200 || response.statusCode() >= 400) {
			throw new IllegalStateException("HTTP " + response.statusCode());
		}
		String date = response.headers().firstValue("date")
				.orElseThrow(() -> new IllegalStateException("missing Date header"));
		return ZonedDateTime.parse(date, HTTP_DATE).toInstant().toEpochMilli();
	}

	// ---- weather source ----------------------------------------------------

	private int fetchWeatherCode() throws Exception {
		String base = config.lobbyWeatherUrl.trim();
		StringBuilder url = new StringBuilder(base);
		url.append(base.contains("?") ? '&' : '?');
		url.append("latitude=").append(config.lobbyWeatherLatitude)
				.append("&longitude=").append(config.lobbyWeatherLongitude)
				.append("&current=weather_code,precipitation");
		String body = get(url.toString(), "application/json");
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		JsonObject current = root.has("current") && root.get("current").isJsonObject()
				? root.getAsJsonObject("current")
				: root.has("current_weather") && root.get("current_weather").isJsonObject()
				? root.getAsJsonObject("current_weather")
				: root;
		JsonElement code = current.get("weather_code");
		if (code == null) code = current.get("weathercode");
		if (code == null) throw new IllegalStateException("no weather_code in response");
		return code.getAsInt();
	}

	/** Maps a WMO weather code to a Minecraft weather state. */
	static Weather mapWeather(int code) {
		if (code == 0 || code == 1 || code == 2 || code == 3 || code == 45 || code == 48) {
			return Weather.CLEAR;
		}
		if (code == 95 || code == 96 || code == 99) {
			return Weather.THUNDER;
		}
		// Drizzle, rain, freezing rain, showers and snow all render as rain.
		return Weather.RAIN;
	}

	// ---- helpers -----------------------------------------------------------

	private String get(String url, String accept) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("Accept", accept)
				.header("User-Agent", "TimeSMP/1.0 (+Minecraft server)")
				.GET()
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	private static String hostOf(String url) {
		try {
			return URI.create(url).getHost().toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			return url;
		}
	}
}
