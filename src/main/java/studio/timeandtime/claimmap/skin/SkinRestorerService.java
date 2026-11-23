package studio.timeandtime.claimmap.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SkinRestorerService for Time SMP.
 * Fetches and applies official signed skin textures from Mojang Session Server
 * for offline-mode / cracked players based on their username or set skin.
 */
public final class SkinRestorerService {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-skin");
	private static final Map<String, Property> SKIN_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, String> CUSTOM_SKIN_MAP = new ConcurrentHashMap<>();

	private SkinRestorerService() {}

	/**
	 * Sets a custom skin target for a player name (e.g. player "Steve" wants skin of "Notch").
	 */
	public static void setSkin(String playerName, String skinTarget) {
		CUSTOM_SKIN_MAP.put(playerName.toLowerCase(), skinTarget);
	}

	public static String getSkinTarget(String playerName) {
		return CUSTOM_SKIN_MAP.getOrDefault(playerName.toLowerCase(), playerName);
	}

	/**
	 * Looks up and applies skin textures to the given GameProfile.
	 * If the profile already has textures (e.g., from an authenticating online session),
	 * it is returned untouched.
	 */
	public static GameProfile applySkin(GameProfile profile) {
		if (profile == null) return null;
		if (profile.properties().containsKey("textures") && !profile.properties().get("textures").isEmpty()) {
			return profile;
		}

		String targetSkinName = getSkinTarget(profile.name());
		Property textures = fetchSkinProperty(targetSkinName);
		if (textures == null) {
			return profile;
		}

		com.google.common.collect.Multimap<String, Property> map = com.google.common.collect.ArrayListMultimap.create();
		map.putAll(profile.properties());
		map.put("textures", textures);
		PropertyMap newProps = new PropertyMap(map);

		return new GameProfile(profile.id(), profile.name(), newProps);
	}

	/**
	 * Fetches signed textures property from cache or Mojang API.
	 */
	public static Property fetchSkinProperty(String username) {
		if (username == null || username.isBlank()) return null;
		String key = username.toLowerCase();

		Property cached = SKIN_CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		try {
			// Step 1: Resolve UUID from username
			URL uuidUrl = URI.create("https://api.mojang.com/users/profiles/minecraft/" + username).toURL();
			HttpURLConnection conn1 = (HttpURLConnection) uuidUrl.openConnection();
			conn1.setRequestMethod("GET");
			conn1.setRequestProperty("User-Agent", "TimeSMP-Server/1.0");
			conn1.setConnectTimeout(3000);
			conn1.setReadTimeout(3000);

			if (conn1.getResponseCode() != 200) {
				return null;
			}

			JsonObject uuidJson;
			try (InputStreamReader reader = new InputStreamReader(conn1.getInputStream(), StandardCharsets.UTF_8)) {
				uuidJson = JsonParser.parseReader(reader).getAsJsonObject();
			}

			if (!uuidJson.has("id")) return null;
			String uuid = uuidJson.get("id").getAsString();

			// Step 2: Fetch profile properties with signed texture
			URL profileUrl = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false").toURL();
			HttpURLConnection conn2 = (HttpURLConnection) profileUrl.openConnection();
			conn2.setRequestMethod("GET");
			conn2.setRequestProperty("User-Agent", "TimeSMP-Server/1.0");
			conn2.setConnectTimeout(3000);
			conn2.setReadTimeout(3000);

			if (conn2.getResponseCode() != 200) {
				return null;
			}

			JsonObject profileJson;
			try (InputStreamReader reader = new InputStreamReader(conn2.getInputStream(), StandardCharsets.UTF_8)) {
				profileJson = JsonParser.parseReader(reader).getAsJsonObject();
			}

			if (profileJson.has("properties")) {
				JsonArray props = profileJson.getAsJsonArray("properties");
				for (JsonElement elem : props) {
					JsonObject obj = elem.getAsJsonObject();
					if ("textures".equals(obj.get("name").getAsString())) {
						String val = obj.get("value").getAsString();
						String sig = obj.has("signature") ? obj.get("signature").getAsString() : null;
						Property prop = new Property("textures", val, sig);
						SKIN_CACHE.put(key, prop);
						LOGGER.info("[timesmp] Loaded and cached skin for {}", username);
						return prop;
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not fetch skin for {}: {}", username, e.getMessage());
		}

		return null;
	}
}
