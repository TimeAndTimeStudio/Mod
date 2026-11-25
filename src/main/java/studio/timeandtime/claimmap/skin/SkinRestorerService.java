package studio.timeandtime.claimmap.skin;

import com.google.gson.JsonObject;
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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
	/**
	 * A player-info property value is written with a 32,767-character limit.
	 * Base64 grows by 4/3, and the textures JSON adds a little, so a raw PNG
	 * above this size can make the packet unencodable.
	 */
	private static final int MAX_SKIN_BYTES = 23 * 1024;
	private static final Map<String, Property> SKIN_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, String> CUSTOM_SKIN_MAP = new ConcurrentHashMap<>();
	/** Per-player arm model: true = Alex (slim), false = Steve (classic). Persisted as {name}.model. */
	private static final Map<String, Boolean> MODEL_CACHE = new ConcurrentHashMap<>();

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

	/** True when the player chose the Alex (slim arms) model; Steve (classic) is the default. */
	public static boolean isSlim(String username) {
		if (username == null || username.isBlank()) return false;
		String key = username.toLowerCase();
		Boolean cached = MODEL_CACHE.get(key);
		if (cached != null) return cached;
		boolean slim = false;
		Path file = SKINS_DIR.resolve(key + ".model");
		try {
			if (Files.isRegularFile(file)) {
				slim = "slim".equalsIgnoreCase(Files.readString(file, StandardCharsets.UTF_8).trim());
			}
		} catch (IOException e) {
			LOGGER.warn("[timesmp] Could not read skin model file {}: {}", file, e.getMessage());
		}
		MODEL_CACHE.put(key, slim);
		return slim;
	}

	/** Remembers the arm model (Alex = slim, Steve = classic) for a player and saves it next to the skin. */
	public static void setSlim(String username, boolean slim) {
		if (username == null || username.isBlank()) return;
		String key = username.toLowerCase();
		MODEL_CACHE.put(key, slim);
		try {
			Files.createDirectories(SKINS_DIR);
			writeAtomically(SKINS_DIR.resolve(key + ".model"),
					(slim ? "slim" : "classic").getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.warn("[timesmp] Could not save skin model for {}: {}", username, e.getMessage());
		}
	}

	/**
	 * Switches the model of the player's already saved skin and re-applies it live.
	 *
	 * @return false when the player has no saved skin yet.
	 */
	public static boolean setModelAndReapply(ServerPlayer player, boolean slim) {
		if (player == null) return false;
		String username = player.getGameProfile().name();
		Path file = SKINS_DIR.resolve(username.toLowerCase() + ".png");
		if (!Files.isRegularFile(file)) return false;
		setSlim(username, slim);
		Property property = loadSkinFromFile(file, username);
		if (property == null) return false;
		SKIN_CACHE.put(username.toLowerCase(), property);
		updatePlayerSkinLive(player, property);
		return true;
	}

	/** A PNG the client can decode and the packet can carry. */
	private static boolean isValidSkin(byte[] bytes) {
		if (bytes == null || bytes.length < 8 || bytes.length > MAX_SKIN_BYTES) {
			return false;
		}
		return (bytes[0] & 0xFF) == 0x89
				&& bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
	}

	/** Write next to the target and rename, so a dropped download never leaves a half file. */
	private static void writeAtomically(Path target, byte[] bytes) throws IOException {
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temp, bytes);
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Reads a skin response, rejecting anything the texture packet cannot carry. */
	private static byte[] readSkin(HttpURLConnection conn) throws IOException {
		try (InputStream in = conn.getInputStream()) {
			// One byte past the limit is enough to reject without buffering a
			// hostile or mistakenly large response in full.
			byte[] bytes = in.readNBytes(MAX_SKIN_BYTES + 1);
			return bytes.length > MAX_SKIN_BYTES ? null : bytes;
		}
	}

	/**
	 * Downloads a skin from a URL and saves it directly to config/timesmp/skins/{username}.png
	 * without requiring an online ServerPlayer entity (e.g. during Configuration phase).
	 */
	public static boolean downloadAndSaveSkinForUser(String username, String urlString) {
		return downloadAndSaveSkinForUser(username, urlString, isSlim(username));
	}

	/** Same as above, also saving the chosen arm model (Alex = slim, Steve = classic). */
	public static boolean downloadAndSaveSkinForUser(String username, String urlString, boolean slim) {
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

			byte[] bytes = readSkin(conn);
			if (!isValidSkin(bytes)) {
				LOGGER.warn("[timesmp] Rejected skin for {}: not a PNG in 1..{} bytes", username, MAX_SKIN_BYTES);
				return false;
			}

			Files.createDirectories(SKINS_DIR);
			Path targetFile = SKINS_DIR.resolve(lowerName + ".png");
			writeAtomically(targetFile, bytes);
			setSlim(username, slim);

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
		return downloadAndSetSkinFromUrl(player, urlString,
				player != null && isSlim(player.getGameProfile().name()));
	}

	/** Same as above, also saving the chosen arm model (Alex = slim, Steve = classic). */
	public static boolean downloadAndSetSkinFromUrl(ServerPlayer player, String urlString, boolean slim) {
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

			byte[] bytes = readSkin(conn);
			if (!isValidSkin(bytes)) {
				LOGGER.warn("[timesmp] Rejected skin for {}: not a PNG in 1..{} bytes", username, MAX_SKIN_BYTES);
				return false;
			}

			Files.createDirectories(SKINS_DIR);
			Path targetFile = SKINS_DIR.resolve(lowerName + ".png");
			writeAtomically(targetFile, bytes);
			setSlim(username, slim);

			// Clear cache so new skin is fetched fresh
			SKIN_CACHE.remove(lowerName);

			// Load the newly downloaded file as texture Property. The client
			// only ever receives the local PNG (data URI), never a remote URL.
			Property newProperty = loadSkinFromFile(targetFile, username);
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
	 * Fetches the textures property from the server's local skin file only:
	 * {@code config/timesmp/skins/<username>.png}. No Mojang or Ely.by lookup:
	 * TimeSMP serves local skins exclusively.
	 */
	public static Property fetchSkinProperty(String username) {
		if (username == null || username.isBlank()) return null;
		String key = username.toLowerCase();

		Path localFile = SKINS_DIR.resolve(key + ".png");
		if (Files.isRegularFile(localFile)) {
			Property localProp = loadSkinFromFile(localFile, username);
			if (localProp != null) {
				SKIN_CACHE.put(key, localProp);
				return localProp;
			}
		}
		return null;
	}

	/**
	 * Reads local PNG skin file and encapsulates into standard Minecraft base64 textures property.
	 */
	private static Property loadSkinFromFile(Path file, String username) {
		try {
			byte[] bytes = Files.readAllBytes(file);
			if (!isValidSkin(bytes)) {
				LOGGER.warn("[timesmp] Ignoring local skin {}: not a PNG in 1..{} bytes", file, MAX_SKIN_BYTES);
				return null;
			}

			LOGGER.info("[timesmp] Loaded local skin {} ({} bytes)", file.getFileName(), bytes.length);

			// Embed image data as Base64 data URI in standard textures JSON structure
			String dataUri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes);
			return buildTexturePropertyFromUrl(dataUri, username, isSlim(username));
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
