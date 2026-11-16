package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manages player saved homes persisted in config/timesmp/homes.json.
 */
public class HomeManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(HomeManager.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,24}$");
	public static final int MAX_HOMES = 27;

	public static class HomeEntry {
		public String name;
		public String world;
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float pitch;
		public long createdAt;

		public HomeEntry() {}

		public HomeEntry(String name, String world, double x, double y, double z, float yaw, float pitch, long createdAt) {
			this.name = name;
			this.world = world;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.createdAt = createdAt;
		}
	}

	private final Path file;
	private final Object lock = new Object();
	// UUID string -> Map of lower-case name -> HomeEntry
	private final Map<String, Map<String, HomeEntry>> data = new HashMap<>();

	public HomeManager(Path file) {
		this.file = file;
	}

	public void load() {
		synchronized (lock) {
			if (!Files.exists(file)) return;
			try (Reader r = Files.newBufferedReader(file)) {
				Type type = new TypeToken<Map<String, Map<String, HomeEntry>>>() {}.getType();
				Map<String, Map<String, HomeEntry>> loaded = GSON.fromJson(r, type);
				data.clear();
				if (loaded != null) {
					data.putAll(loaded);
				}
				LOGGER.info("[HomeManager] Loaded homes for {} players", data.size());
			} catch (Exception e) {
				LOGGER.error("[HomeManager] Failed to load homes from {}: {}", file, e.getMessage());
			}
		}
	}

	public void save() {
		synchronized (lock) {
			try {
				if (file.getParent() != null) Files.createDirectories(file.getParent());
				try (Writer w = Files.newBufferedWriter(file)) {
					GSON.toJson(data, w);
				}
			} catch (Exception e) {
				LOGGER.error("[HomeManager] Failed to save homes to {}: {}", file, e.getMessage());
			}
		}
	}

	public boolean isValidName(String name) {
		return name != null && SAFE_NAME.matcher(name).matches();
	}

	public Map<String, HomeEntry> getHomes(UUID uuid) {
		synchronized (lock) {
			Map<String, HomeEntry> userHomes = data.get(uuid.toString());
			if (userHomes == null) return Collections.emptyMap();
			return new LinkedHashMap<>(userHomes);
		}
	}

	public HomeEntry getHome(UUID uuid, String name) {
		if (name == null) return null;
		synchronized (lock) {
			Map<String, HomeEntry> userHomes = data.get(uuid.toString());
			if (userHomes == null) return null;
			return userHomes.get(name.toLowerCase(Locale.ROOT));
		}
	}

	public boolean setHome(UUID uuid, String name, ServerLevel level, Vec3 pos, float yaw, float pitch) {
		if (!isValidName(name)) return false;
		String key = name.toLowerCase(Locale.ROOT);
		String worldId = level.dimension().identifier().toString();
		HomeEntry entry = new HomeEntry(name, worldId, pos.x, pos.y, pos.z, yaw, pitch, System.currentTimeMillis());
		synchronized (lock) {
			Map<String, HomeEntry> userHomes = data.computeIfAbsent(uuid.toString(), k -> new LinkedHashMap<>());
			if (!userHomes.containsKey(key) && userHomes.size() >= MAX_HOMES) {
				return false;
			}
			userHomes.put(key, entry);
			save();
			return true;
		}
	}

	public boolean deleteHome(UUID uuid, String name) {
		if (name == null) return false;
		String key = name.toLowerCase(Locale.ROOT);
		synchronized (lock) {
			Map<String, HomeEntry> userHomes = data.get(uuid.toString());
			if (userHomes == null) return false;
			HomeEntry removed = userHomes.remove(key);
			if (removed != null) {
				save();
				return true;
			}
			return false;
		}
	}

	public void teleport(ServerPlayer player, HomeEntry home) {
		MinecraftServer srv = ClaimMap.getServer();
		if (srv == null) {
			player.sendSystemMessage(Component.literal("§c[Home] เซิร์ฟเวอร์ยังไม่พร้อม"));
			return;
		}
		ServerLevel targetLevel = null;
		Identifier loc = Identifier.tryParse(home.world);
		if (loc != null) {
			ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, loc);
			targetLevel = srv.getLevel(key);
		}
		if (targetLevel == null) {
			targetLevel = srv.overworld();
		}

		TeleportTransition transition = new TeleportTransition(targetLevel,
				new Vec3(home.x, home.y, home.z),
				Vec3.ZERO, home.yaw, home.pitch, Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
		ServerPlayer moved = player.teleport(transition);
		if (moved != null) {
			moved.sendSystemMessage(Component.literal("§6[Home] §aวาร์ปมายังบ้าน §f" + home.name + " §aแล้ว"));
		}
	}
}
