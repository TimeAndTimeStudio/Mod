package studio.timeandtime.claimmap.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.timeandtime.claimmap.mixin.PlayerEntityAccessor;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SkinRestorerService for Time SMP.
 * Fetches and applies skin textures from Mojang, Ely.by, local server files, or custom direct URLs.
 */
public final class SkinRestorerService {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-skin");
	private static final Path SKINS_DIR = Path.of("config/timesmp/skins");
	private static final Map<String, Property> SKIN_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, String> CUSTOM_SKIN_MAP = new ConcurrentHashMap<>();

	private SkinRestorerService() {}

	static {
		try {
			Files.createDirectories(SKINS_DIR);
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not create skins directory: {}", e.getMessage());
		}
	}

	public static Path getSkinsDir() {
		return SKINS_DIR;
	}

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
	 * Downloads a skin from a URL and saves it directly to config/timesmp/skins/{username}.png
	 * without requiring an online ServerPlayer entity (e.g. during Configuration phase).
	 */
	public static boolean downloadAndSaveSkinForUser(String username, String urlString) {
		if (username == null || urlString == null || urlString.isBlank()) return false;
		String lowerName = username.toLowerCase();
		try {
			URL url = URI.create(urlString.trim()).toURL();
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("User-Agent", "TimeSMP-Server/1.0 (Mozilla/5.0)");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(10000);

			int status = conn.getResponseCode();
			if (status != 200) {
				LOGGER.warn("[timesmp] Failed to download skin from URL for {}: HTTP {}", username, status);
				return false;
			}

			Files.createDirectories(SKINS_DIR);
			Path targetFile = SKINS_DIR.resolve(lowerName + ".png");

			// Write / overwrite existing file
			try (InputStream in = conn.getInputStream()) {
				Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
			}

			// Clear cache so new skin is fetched fresh
			SKIN_CACHE.remove(lowerName);
			LOGGER.info("[timesmp] Successfully downloaded and saved skin for {} from {}", username, urlString);
			return true;
		} catch (Exception e) {
			LOGGER.error("[timesmp] Error downloading skin for {} from {}: {}", username, urlString, e.getMessage());
		}
		return false;
	}

	/**
	 * Downloads a skin from a URL and saves it directly to config/timesmp/skins/{username}.png,
	 * replacing any existing skin file, clears skin cache, and re-applies skin to online player.
	 *
	 * @param player The online ServerPlayer executing or receiving the skin change.
	 * @param urlString The direct URL of the skin (PNG or texture image).
	 * @return true if successfully downloaded and applied; false otherwise.
	 */
	public static boolean downloadAndSetSkinFromUrl(ServerPlayer player, String urlString) {
		if (player == null || urlString == null || urlString.isBlank()) return false;
		String username = player.getGameProfile().name();
		String lowerName = username.toLowerCase();

		try {
			URL url = URI.create(urlString.trim()).toURL();
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("User-Agent", "TimeSMP-Server/1.0 (Mozilla/5.0)");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(10000);

			int status = conn.getResponseCode();
			if (status != 200) {
				LOGGER.warn("[timesmp] Failed to download skin from URL for {}: HTTP {}", username, status);
				return false;
			}

			Files.createDirectories(SKINS_DIR);
			Path targetFile = SKINS_DIR.resolve(lowerName + ".png");

			// Write / overwrite existing file
			try (InputStream in = conn.getInputStream()) {
				Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
			}

			// Clear cache so new skin is fetched fresh
			SKIN_CACHE.remove(lowerName);

			// Load the newly downloaded file as texture Property
			Property newProperty = loadSkinFromFile(targetFile, username);
			if (newProperty == null) {
				// Fallback: build textures property pointing directly to URL
				newProperty = buildCustomUrlProperty(urlString, username);
			}

			if (newProperty != null) {
				SKIN_CACHE.put(lowerName, newProperty);
				updatePlayerSkinLive(player, newProperty);
				LOGGER.info("[timesmp] Successfully updated and replaced skin for {} from URL", username);
				return true;
			}
		} catch (Exception e) {
			LOGGER.error("[timesmp] Error downloading skin for {} from {}: {}", username, urlString, e.getMessage());
		}
		return false;
	}

	/**
	 * Looks up and applies skin textures to the given GameProfile.
	 * Even for online / premium accounts, custom/server offline skins take precedence,
	 * ensuring all players get server-configured skins like in TML Client.
	 */
	public static GameProfile applySkin(GameProfile profile) {
		if (profile == null) return null;

		String targetSkinName = getSkinTarget(profile.name());
		Property textures = fetchSkinProperty(targetSkinName);
		if (textures == null) {
			return profile;
		}

		com.google.common.collect.Multimap<String, Property> map = com.google.common.collect.ArrayListMultimap.create();
		map.putAll(profile.properties());
		map.removeAll("textures");
		map.put("textures", textures);
		PropertyMap newProps = new PropertyMap(map);

		return new GameProfile(profile.id(), profile.name(), newProps);
	}

	/**
	 * Fetches textures property from local file, cache, Mojang API, or Ely.by.
	 */
	public static Property fetchSkinProperty(String username) {
		if (username == null || username.isBlank()) return null;
		String key = username.toLowerCase();

		// Check local server file first (config/timesmp/skins/<username>.png)
		Path localFile = SKINS_DIR.resolve(key + ".png");
		if (Files.isRegularFile(localFile)) {
			Property localProp = loadSkinFromFile(localFile, username);
			if (localProp != null) {
				SKIN_CACHE.put(key, localProp);
				return localProp;
			}
		}

		Property cached = SKIN_CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		// 1. Try Mojang API
		Property mojangProp = fetchMojangSkin(username);
		if (mojangProp != null) {
			SKIN_CACHE.put(key, mojangProp);
			return mojangProp;
		}

		// 2. Fallback to Ely.by skinsystem (TML Client offline skins)
		Property elyByProp = fetchElyBySkin(username);
		if (elyByProp != null) {
			SKIN_CACHE.put(key, elyByProp);
			return elyByProp;
		}

		return null;
	}

	/**
	 * Reads local PNG skin file and encapsulates into standard Minecraft base64 textures property.
	 */
	private static Property loadSkinFromFile(Path file, String username) {
		try {
			byte[] bytes = Files.readAllBytes(file);
			if (bytes.length < 8) return null;
			// Basic PNG magic check
			if ((bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
				return null;
			}

			// Embed image data as Base64 data URI in standard textures JSON structure
			String dataUri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes);
			return buildTexturePropertyFromUrl(dataUri, username, false);
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not load local skin file {}: {}", file, e.getMessage());
		}
		return null;
	}

	public static Property buildCustomUrlProperty(String url, String username) {
		return buildTexturePropertyFromUrl(url, username, false);
	}

	private static Property buildTexturePropertyFromUrl(String url, String username, boolean isSlim) {
		JsonObject skinEntry = new JsonObject();
		skinEntry.addProperty("url", url);
		if (isSlim) {
			JsonObject meta = new JsonObject();
			meta.addProperty("model", "slim");
			skinEntry.add("metadata", meta);
		}

		JsonObject texturesObj = new JsonObject();
		texturesObj.add("SKIN", skinEntry);

		JsonObject payload = new JsonObject();
		payload.addProperty("timestamp", System.currentTimeMillis());
		payload.addProperty("profileName", username);
		payload.add("textures", texturesObj);

		String jsonString = payload.toString();
		String base64Value = java.util.Base64.getEncoder().encodeToString(jsonString.getBytes(StandardCharsets.UTF_8));
		return new Property("textures", base64Value, null);
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

			return buildTexturePropertyFromUrl(skinUrl, username, isSlim);
		} catch (Exception e) {
			LOGGER.warn("[timesmp] Could not fetch Ely.by skin for {}: {}", username, e.getMessage());
		}
		return null;
	}

	/**
	 * Immediately updates skin on an active ServerPlayer without disconnecting them.
	 * Broadcasts remove/add tab list packets and entity respawn tracking to all online players.
	 */
	public static void updatePlayerSkinLive(ServerPlayer player, Property skinProperty) {
		if (player == null || skinProperty == null) return;

		GameProfile current = player.getGameProfile();
		com.google.common.collect.Multimap<String, Property> map = com.google.common.collect.ArrayListMultimap.create();
		map.putAll(current.properties());
		map.removeAll("textures");
		map.put("textures", skinProperty);
		PropertyMap newProps = new PropertyMap(map);
		GameProfile newProfile = new GameProfile(current.id(), current.name(), newProps);

		// Update profile on Player entity via accessor
		if (player instanceof PlayerEntityAccessor accessor) {
			accessor.setGameProfile(newProfile);
		}

		if (player.level() == null || player.level().getServer() == null) return;
		var server = player.level().getServer();
		var playerList = server.getPlayerList();

		// 1. Remove from tab list
		ClientboundPlayerInfoRemovePacket removePacket = new ClientboundPlayerInfoRemovePacket(Collections.singletonList(player.getUUID()));
		playerList.broadcastAll(removePacket);

		// 2. Re-add to tab list with new profile textures
		ClientboundPlayerInfoUpdatePacket addPacket = new ClientboundPlayerInfoUpdatePacket(
				EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
				Collections.singletonList(player)
		);
		playerList.broadcastAll(addPacket);

		// 3. Refresh player entity for tracking clients around the player
		var chunkMap = player.level().getChunkSource().chunkMap;
		chunkMap.sendToTrackingPlayers(player, new ClientboundPlayerInfoRemovePacket(Collections.singletonList(player.getUUID())));
		chunkMap.sendToTrackingPlayers(player, addPacket);
	}
}
