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
	 * Fetches signed textures property from cache or Mojang API, falling back to Ely.by (TML Client skin system).
	 */
	public static Property fetchSkinProperty(String username) {
		if (username == null || username.isBlank()) return null;
		String key = username.toLowerCase();

		Property cached = SKIN_CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		// 1. Try Mojang API first
		Property mojangProp = fetchMojangSkin(username);
		if (mojangProp != null) {
			SKIN_CACHE.put(key, mojangProp);
			return mojangProp;
		}

		// 2. Fallback to Ely.by skinsystem (used by TML Client for offline skins)
		Property elyByProp = fetchElyBySkin(username);
		if (elyByProp != null) {
			SKIN_CACHE.put(key, elyByProp);
			return elyByProp;
		}

		return null;
	}

	private static Property fetchMojangSkin(String username) {
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
						LOGGER.info("[timesmp] Loaded Mojang skin for {}", username);
						return new Property("textures", val, sig);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not fetch Mojang skin for {}: {}", username, e.getMessage());
		}
		return null;
	}

	/**
	 * Fetches offline skin from Ely.by skinsystem (TML Client offline skin provider).
	 * Builds a base64 Minecraft textures property compatible with TML Client and vanilla clients.
	 */
	public static Property fetchElyBySkin(String username) {
		try {
			URL url = URI.create("https://skinsystem.ely.by/textures/" + username).toURL();
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("User-Agent", "TimeSMP-Server/1.0");
			conn.setConnectTimeout(3000);
			conn.setReadTimeout(3000);

			if (conn.getResponseCode() != 200) {
				return null;
			}

			JsonObject root;
			try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
				root = JsonParser.parseReader(reader).getAsJsonObject();
			}

			if (!root.has("SKIN")) {
				return null;
			}

			JsonObject skinObj = root.getAsJsonObject("SKIN");
			String skinUrl = skinObj.get("url").getAsString();
			boolean isSlim = false;
			if (skinObj.has("metadata")) {
				JsonObject meta = skinObj.getAsJsonObject("metadata");
				if (meta.has("model") && "slim".equalsIgnoreCase(meta.get("model").getAsString())) {
					isSlim = true;
				}
			}

			// Format into standard Minecraft textures JSON
			// { "textures": { "SKIN": { "url": "..." (, "metadata": {"model": "slim"}) } } }
			JsonObject skinEntry = new JsonObject();
			skinEntry.addProperty("url", skinUrl);
			if (isSlim) {
				JsonObject meta = new JsonObject();
				meta.addProperty("model", "slim");
				skinEntry.add("metadata", meta);
			}

			JsonObject texturesObj = new JsonObject();
			texturesObj.add("SKIN", skinEntry);

			if (root.has("CAPE")) {
				JsonObject capeObj = root.getAsJsonObject("CAPE");
				if (capeObj.has("url")) {
					JsonObject capeEntry = new JsonObject();
					capeEntry.addProperty("url", capeObj.get("url").getAsString());
					texturesObj.add("CAPE", capeEntry);
				}
			}

			JsonObject payload = new JsonObject();
			payload.addProperty("timestamp", System.currentTimeMillis());
			payload.addProperty("profileName", username);
			payload.add("textures", texturesObj);

			String jsonString = payload.toString();
			String base64Value = java.util.Base64.getEncoder().encodeToString(jsonString.getBytes(StandardCharsets.UTF_8));

			LOGGER.info("[timesmp] Loaded Ely.by / TML offline skin for {}", username);
			return new Property("textures", base64Value, null);
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not fetch Ely.by skin for {}: {}", username, e.getMessage());
		}
		return null;
	}
}
