package studio.timeandtime.claimmap.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.serialization.Dynamic;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.timeandtime.claimmap.ClaimMap;
import studio.timeandtime.claimmap.ClaimMapConfig;
import studio.timeandtime.claimmap.WorldSpawnProtection;
import studio.timeandtime.claimmap.mixin.ServerLevelsAccessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Discovers complete saves under {@code worlds/}, mounts them as additional
 * {@link ServerLevel}s on demand, and teleports players with the world name as
 * the command.
 *
 * <p>Inventory policy is deliberately <b>shared</b>: dimensions are changed but
 * the player object is not replaced, so inventory, ender chest, experience,
 * health, effects, advancements and statistics are the same in every world.
 * This is documented in {@link #inventoryPolicy()}.
 *
 * <p>All operations that touch the live level map or command tree run on the
 * server thread. HTTP callers are scheduled onto that thread and wait briefly.
 */
public final class WorldManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-worlds");
	private static final String DIMENSION_NAMESPACE = "timesmp";

	private final ClaimMapConfig config;
	private final WorldScanner scanner = new WorldScanner();
	private final Map<String, WorldDefinition> worlds = new LinkedHashMap<>();
	private volatile MinecraftServer server;
	private volatile Path directory;
	private volatile LevelStorageSource storageSource;
	private volatile boolean stopping;
	private volatile studio.timeandtime.claimmap.TeleportManager teleports;
	private int tickCounter;

	public void attachTeleports(studio.timeandtime.claimmap.TeleportManager teleports) {
		this.teleports = teleports;
	}

	public record MainWorldPos(double x, double y, double z, float yaw, float pitch) {
	}

	private final Map<UUID, MainWorldPos> mainWorldPositions = new ConcurrentHashMap<>();
	private volatile Path positionsFile;

	public record WorldInfo(String name, String folder, WorldState state, boolean loaded, int players,
			boolean present, boolean commandAvailable, String commandConflict, String error) {
	}

	public WorldManager(ClaimMapConfig config) {
		this.config = config;
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			registerAdminCommand(dispatcher);
			registerPlayerCommands(dispatcher);
		});
	}

	public String inventoryPolicy() {
		return "shared";
	}

	public static boolean isLobby(Level level) {
		if (level == null) return false;
		return level.dimension().identifier().getPath().equalsIgnoreCase("lobby")
				&& level.dimension().identifier().getNamespace().equalsIgnoreCase(DIMENSION_NAMESPACE);
	}

	public boolean hasLobbyWorld() {
		synchronized (worlds) {
			WorldDefinition def = worlds.get("lobby");
			return def != null && def.present();
		}
	}

	public ServerLevel getLobbyLevel() {
		synchronized (worlds) {
			WorldDefinition def = worlds.get("lobby");
			if (def != null && def.present()) {
				if (!def.loaded()) {
					try {
						loadLocked(def);
					} catch (Exception e) {
						LOGGER.error("[worlds] Could not auto-load lobby: {}", e.getMessage());
					}
				}
				return def.level;
			}
			return null;
		}
	}

	private void checkAndPreloadLobby() {
		synchronized (worlds) {
			WorldDefinition def = worlds.get("lobby");
			if (def != null && def.present() && !def.loaded()) {
				try {
					loadLocked(def);
					LOGGER.info("[worlds] Found 'lobby' world - loaded and ready as starting lobby!");
				} catch (Exception e) {
					LOGGER.error("[worlds] Failed to auto-load 'lobby' world: {}", e.getMessage());
				}
			}
		}
	}

	public String directory() {
		Path dir = directory;
		return dir == null ? config.worldDirectory : dir.toString();
	}

	public int scanSeconds() {
		return config.worldScanSeconds;
	}

	public int idleSeconds() {
		return config.worldIdleUnloadSeconds;
	}

	public void start(MinecraftServer server) {
		this.server = server;
		this.stopping = false;
		this.directory = resolveDirectory(server);
		this.storageSource = LevelStorageSource.createDefault(server.getServerDirectory());
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			LOGGER.error("[worlds] Could not create {}: {}", directory, e.getMessage());
		}
		this.positionsFile = directory.resolve(".main_world_positions.json");
		loadMainWorldPositions();
		scanLocked();
		if (worlds.isEmpty()) {
			tryCreateDefaultWorld("resource");
			scanLocked();
		}
		checkAndPreloadLobby();
		LOGGER.info("[worlds] Using {} ({} world(s), shared inventory)", directory, worlds.size());
	}

	private void tryCreateDefaultWorld(String name) {
		try {
			Path worldFolder = directory.resolve(name);
			if (Files.exists(worldFolder)) return;
			Files.createDirectories(worldFolder);
			Path mainLevelDat = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("level.dat");
			if (Files.exists(mainLevelDat)) {
				Files.copy(mainLevelDat, worldFolder.resolve("level.dat"));
				LOGGER.info("[worlds] Created default testing world '{}'", name);
			}
		} catch (Exception e) {
			LOGGER.warn("[worlds] Could not create default world '{}': {}", name, e.getMessage());
		}
	}

	private void loadMainWorldPositions() {
		if (positionsFile == null || !Files.exists(positionsFile)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(positionsFile, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				JsonObject o = e.getValue().getAsJsonObject();
				UUID u = UUID.fromString(e.getKey());
				mainWorldPositions.put(u, new MainWorldPos(
						o.get("x").getAsDouble(),
						o.get("y").getAsDouble(),
						o.get("z").getAsDouble(),
						o.has("yaw") ? o.get("yaw").getAsFloat() : 0f,
						o.has("pitch") ? o.get("pitch").getAsFloat() : 0f
				));
			}
		} catch (Exception e) {
			LOGGER.warn("[worlds] Could not read main world positions: {}", e.getMessage());
		}
	}

	private void saveMainWorldPositions() {
		if (positionsFile == null) return;
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<UUID, MainWorldPos> e : mainWorldPositions.entrySet()) {
				JsonObject o = new JsonObject();
				o.addProperty("x", e.getValue().x());
				o.addProperty("y", e.getValue().y());
				o.addProperty("z", e.getValue().z());
				o.addProperty("yaw", e.getValue().yaw());
				o.addProperty("pitch", e.getValue().pitch());
				root.add(e.getKey().toString(), o);
			}
			Files.writeString(positionsFile, root.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.warn("[worlds] Could not save main world positions: {}", e.getMessage());
		}
	}

	public void tick() {
		MinecraftServer s = server;
		if (s == null || stopping) return;
		tickCounter++;
		int interval = Math.max(1, config.worldScanSeconds) * 20;
		if (tickCounter >= interval) {
			tickCounter = 0;
			scanLocked();
		}
		int now = s.getTickCount();
		for (WorldDefinition def : new ArrayList<>(worlds.values())) {
			ServerLevel level = def.level;
			if (!def.loaded() || level == null) continue;
			if ("lobby".equalsIgnoreCase(def.name())) continue;
			if (!level.players().isEmpty()) {
				def.lastEmptyTick = 0L;
				continue;
			}
			if (config.worldIdleUnloadSeconds <= 0) {
				continue;
			}
			if (def.lastEmptyTick == 0L) {
				def.lastEmptyTick = now;
			} else if (now - def.lastEmptyTick >= Math.max(600, config.worldIdleUnloadSeconds * 20)) {
				try {
					unloadLocked(def);
				} catch (Exception e) {
					LOGGER.warn("[worlds] Auto unload of empty world {} failed: {}", def.name(), e.getMessage());
				}
			}
		}
	}

	public void shutdown() {
		stopping = true;
		saveWorldData(server);
	}

	/** Called after MinecraftServer has closed all levels. */
	public void closeStorage() {
		for (WorldDefinition def : worlds.values()) {
			LevelStorageSource.LevelStorageAccess access = def.storageAccess;
			if (access != null) {
				try {
					WorldStoragePaths.unmarkManaged(access);
					access.close();
				} catch (IOException e) {
					LOGGER.warn("[worlds] Could not close storage for {}: {}", def.name(), e.getMessage());
				}
				def.storageAccess = null;
				def.level = null;
				def.state = WorldState.UNLOADED;
			}
		}
		this.server = null;
	}

	/**
	 * Writes each loaded world's own {@code level.dat}. Called from the server
	 * save hook after chunks and the primary level.dat have been saved.
	 */
	public void saveWorldData(MinecraftServer s) {
		if (s == null) return;
		for (WorldDefinition def : new ArrayList<>(worlds.values())) {
			WorldData data = def.worldData;
			LevelStorageSource.LevelStorageAccess access = def.storageAccess;
			if (data == null || access == null) continue;
			if (def.state != WorldState.LOADED && def.state != WorldState.UNLOADING) continue;
			try {
				access.saveDataTag(data, null);
			} catch (Exception e) {
				LOGGER.warn("[worlds] Could not save level.dat for {}: {}", def.name(), e.getMessage());
			}
		}
	}

	public List<WorldInfo> snapshot() {
		List<WorldInfo> out = new ArrayList<>();
		synchronized (worlds) {
			for (WorldDefinition def : worlds.values()) {
				ServerLevel level = def.level;
				out.add(new WorldInfo(def.name(), def.folder().toString(), def.state(),
						def.loaded(), level == null ? 0 : level.players().size(),
						def.present(), def.commandAvailable(), def.commandConflict(), def.error()));
			}
		}
		out.sort(Comparator.comparing(WorldInfo::name));
		return out;
	}

	public List<WorldInfo> scanNow() throws Exception {
		return onServer(() -> {
			scanLocked();
			return snapshot();
		});
	}

	public WorldInfo load(String name) throws Exception {
		return onServer(() -> {
			WorldDefinition def = require(name);
			if (def.loaded()) return info(def);
			if (def.state == WorldState.LOADING) throw new IllegalStateException("โลกนี้กำลังโหลดอยู่");
			loadLocked(def);
			return info(def);
		});
	}

	public WorldInfo unload(String name) throws Exception {
		return onServer(() -> {
			WorldDefinition def = require(name);
			unloadLocked(def);
			return info(def);
		});
	}

	/**
	 * Loads if needed and moves one player. A web request has no player, so the
	 * caller supplies the player's name; in-game commands pass the sender.
	 */
	public WorldInfo teleport(String worldName, String playerName) throws Exception {
		return onServer(() -> {
			WorldDefinition def = require(worldName);
			MinecraftServer s = server;
			if (s == null) throw new IllegalStateException("เซิร์ฟเวอร์เกมยังไม่พร้อม");
			ServerPlayer player = s.getPlayerList().getPlayerByName(playerName);
			if (player == null) throw new IllegalArgumentException("ไม่พบผู้เล่นออนไลน์ชื่อ " + playerName);
			loadLocked(def);
			teleportLocked(player, def);
			return info(def);
		});
	}

	// ---- discovery ---------------------------------------------------------

	private void scanLocked() {
		Path dir = directory;
		if (dir == null) return;
		List<WorldScanner.Candidate> candidates = scanner.scan(dir);
		Set<String> seen = new HashSet<>();
		for (WorldScanner.Candidate candidate : candidates) {
			if (!candidate.valid()) {
				LOGGER.warn("[worlds] Skipping {}: {}", candidate.folder(), candidate.reason());
				continue;
			}
			if (!seen.add(candidate.name())) {
				LOGGER.warn("[worlds] Skipping {}: duplicate world name '{}'", candidate.folder(), candidate.name());
				continue;
			}
			WorldDefinition def;
			synchronized (worlds) {
				def = worlds.get(candidate.name());
				if (def == null) {
					def = new WorldDefinition(candidate.name(), candidate.folderName(), candidate.folder(),
							ResourceKey.create(Registries.DIMENSION,
									Identifier.fromNamespaceAndPath(DIMENSION_NAMESPACE, candidate.name())));
					worlds.put(def.name(), def);
				}
			}
			def.present = true;
			if (!def.loaded() && def.state != WorldState.LOADING) {
				def.error = null;
				if (def.state == WorldState.ERROR) def.state = WorldState.DISCOVERED;
			}
			registerWorldCommand(def);
		}
		// A folder removed from disk must not break an already loaded world.
		List<WorldDefinition> removed = new ArrayList<>();
		synchronized (worlds) {
			for (WorldDefinition def : new ArrayList<>(worlds.values())) {
				if (seen.contains(def.name())) continue;
				def.present = false;
				if (def.loaded() || def.state == WorldState.LOADING) {
					LOGGER.warn("[worlds] Folder for loaded world {} disappeared or became invalid; keeping it loaded", def.name());
					continue;
				}
				removed.add(def);
			}
			for (WorldDefinition def : removed) worlds.remove(def.name());
		}
		for (WorldDefinition def : removed) {
			unregisterWorldCommand(def);
			LOGGER.info("[worlds] Removed missing world {}", def.name());
		}
		checkAndPreloadLobby();
	}

	private WorldDefinition require(String name) {
		synchronized (worlds) {
			WorldDefinition def = worlds.get(name.toLowerCase(java.util.Locale.ROOT));
			if (def == null) throw new IllegalArgumentException("ไม่พบโลกชื่อ " + name);
			return def;
		}
	}

	private WorldInfo info(WorldDefinition def) {
		ServerLevel level = def.level;
		return new WorldInfo(def.name(), def.folder().toString(), def.state(), def.loaded(),
				level == null ? 0 : level.players().size(), def.present(), def.commandAvailable(),
				def.commandConflict(), def.error());
	}

	// ---- loading / unloading ----------------------------------------------

	private void loadLocked(WorldDefinition def) {
		if (def.loaded()) return;
		MinecraftServer s = server;
		if (s == null) throw new IllegalStateException("เซิร์ฟเวอร์เกมยังไม่พร้อม");
		if (!Files.isDirectory(def.folder())) {
			def.present = false;
			def.state = WorldState.ERROR;
			def.error = "ไม่พบโฟลเดอร์โลก";
			throw new IllegalStateException(def.error);
		}
		String invalid = scanner.validate(def.folder());
		if (invalid != null) {
			def.state = WorldState.ERROR;
			def.error = invalid;
			throw new IllegalStateException(invalid);
		}
		if (s.getLevel(def.dimensionKey()) != null) {
			def.state = WorldState.ERROR;
			def.error = "มีโลกที่ใช้ key นี้อยู่แล้ว";
			throw new IllegalStateException(def.error);
		}

		def.state = WorldState.LOADING;
		def.error = null;
		LevelStorageSource.LevelStorageAccess access = null;
		try {
			access = storageSource.createAccess("worlds/" + def.folderName());
			WorldStoragePaths.markManaged(access);

			Path datapacksDir = def.folder().resolve("datapacks");
			if (!Files.exists(datapacksDir)) {
				try {
					Files.createDirectories(datapacksDir);
				} catch (Exception ignored) {}
			}

			List<String> discoveredPacks = new ArrayList<>();
			if (Files.isDirectory(datapacksDir)) {
				try (var stream = Files.list(datapacksDir)) {
					for (Path p : stream.toList()) {
						String fname = p.getFileName().toString();
						if (Files.isDirectory(p) && Files.exists(p.resolve("pack.mcmeta"))) {
							discoveredPacks.add("file/" + fname);
						} else if (Files.isRegularFile(p) && fname.toLowerCase(Locale.ROOT).endsWith(".zip")) {
							discoveredPacks.add("file/" + fname);
						}
					}
				} catch (Exception e) {
					LOGGER.warn("[worlds] Could not scan datapacks in {}: {}", datapacksDir, e.getMessage());
				}
			}

			Dynamic<?> tag = access.getUnfixedDataTagWithFallback();
			int dataVersion = NbtUtils.getDataVersion(tag);
			if (DataFixers.getFileFixer().requiresFileFixing(dataVersion)) {
				access.fixAndGetSummary();
				tag = access.getUnfixedDataTagWithFallback();
			}
			Registry<LevelStem> stems = s.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
			WorldDataConfiguration dataConfig = LevelStorageSource.readDataConfig(tag);
			if (!discoveredPacks.isEmpty()) {
				List<String> enabled = new ArrayList<>(dataConfig.dataPacks().getEnabled());
				for (String packName : discoveredPacks) {
					if (!enabled.contains(packName)) {
						enabled.add(packName);
					}
				}
				dataConfig = new WorldDataConfiguration(
						new DataPackConfig(enabled, dataConfig.dataPacks().getDisabled()),
						dataConfig.enabledFeatures()
				);
				LOGGER.info("[worlds] Discovered {} custom datapack(s) for world '{}'", discoveredPacks.size(), def.name());
			}

			LevelDataAndDimensions loaded = LevelStorageSource.getLevelDataAndDimensions(access, tag,
					dataConfig, stems, s.registryAccess());
			WorldData data = loaded.worldDataAndGenSettings().data();
			LevelStem stem = loaded.dimensions().dimensions().getValue(LevelStem.OVERWORLD);
			if (stem == null) stem = stems.getValue(LevelStem.OVERWORLD);
			if (stem == null) throw new IllegalStateException("ไม่พบ LevelStem ของโลกหลัก");

			long seed = BiomeManager.obfuscateSeed(
					loaded.worldDataAndGenSettings().genSettings().options().seed());
			ServerLevel level = new ServerLevel(s, ((ServerLevelsAccessor) s).claimmap$executor(), access,
					data.overworldData(),
					def.dimensionKey(), stem, false, seed, List.of(), true);

			((ServerLevelsAccessor) s).claimmap$levels().put(def.dimensionKey(), level);
			def.storageAccess = access;
			def.worldData = data;
			def.level = level;
			def.state = WorldState.LOADED;
			def.lastEmptyTick = s.getTickCount();

			// Prepare the spawn chunk before the first teleport request.
			BlockPos spawn = spawnPos(level);
			level.getChunkSource().addTicketWithRadius(
					net.minecraft.server.level.TicketType.PLAYER_SPAWN,
					new net.minecraft.world.level.ChunkPos(spawn.getX() >> 4, spawn.getZ() >> 4), 2);
			LOGGER.info("[worlds] Loaded {} from {}", def.name(), def.folder());
		} catch (Throwable e) {
			def.state = WorldState.ERROR;
			def.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			def.level = null;
			def.worldData = null;
			def.storageAccess = null;
			if (access != null) {
				try {
					WorldStoragePaths.unmarkManaged(access);
					access.close();
				} catch (IOException ignored) {
					// best effort cleanup
				}
			}
			LOGGER.error("[worlds] Failed to load {}: {}", def.name(), def.error, e);
			throw new IllegalStateException("โหลดโลก " + def.name() + " ไม่สำเร็จ: " + def.error, e);
		}
	}

	private void unloadLocked(WorldDefinition def) {
		if (!def.loaded()) return;
		ServerLevel level = def.level;
		if (level == null) return;
		if (!level.players().isEmpty()) {
			throw new IllegalStateException("ยังมีผู้เล่นอยู่ในโลกนี้");
		}
		def.state = WorldState.UNLOADING;
		try {
			level.save(null, true, false);
			if (def.worldData != null && def.storageAccess != null) {
				def.storageAccess.saveDataTag(def.worldData, null);
			}
			((ServerLevelsAccessor) server).claimmap$levels().remove(def.dimensionKey(), level);
			level.close();
			if (def.storageAccess != null) {
				WorldStoragePaths.unmarkManaged(def.storageAccess);
				def.storageAccess.close();
			}
			def.level = null;
			def.storageAccess = null;
			def.worldData = null;
			def.state = WorldState.UNLOADED;
			LOGGER.info("[worlds] Unloaded {}", def.name());
		} catch (Exception e) {
			def.state = WorldState.ERROR;
			def.error = "ปิดโลกไม่สำเร็จ: " + e.getMessage();
			LOGGER.error("[worlds] Failed to unload {}", def.name(), e);
			throw new IllegalStateException(def.error, e);
		}
	}

	private void teleportLocked(ServerPlayer player, WorldDefinition def) {
		if (teleports != null) {
			teleports.startWarmup(player, Component.literal("โลก " + def.name()), () -> executeTeleportLocked(player, def));
		} else {
			executeTeleportLocked(player, def);
		}
	}

	public void recordMainWorldPosition(ServerPlayer player) {
		if (server != null && player != null && player.level() == server.overworld()) {
			mainWorldPositions.put(player.getUUID(), new MainWorldPos(
					player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
			saveMainWorldPositions();
		}
	}

	public void clearMainWorldPosition(UUID uuid) {
		if (uuid != null) {
			mainWorldPositions.remove(uuid);
			saveMainWorldPositions();
		}
	}

	public boolean hasMainWorldPosition(UUID uuid) {
		return uuid != null && mainWorldPositions.containsKey(uuid);
	}

	private void executeTeleportLocked(ServerPlayer player, WorldDefinition def) {
		if (def.level == null) {
			try {
				loadLocked(def);
			} catch (Exception e) {
				throw new IllegalStateException("โหลดโลกไม่สำเร็จ: " + e.getMessage(), e);
			}
		}
		ServerLevel destination = def.level;
		if (destination == null) throw new IllegalStateException("โลกยังโหลดไม่เสร็จ");

		// If player is leaving the main overworld, remember where they were so they return here
		recordMainWorldPosition(player);

		if ("lobby".equalsIgnoreCase(def.name())) {
			BlockPos sp = spawnPos(destination);
			Vec3 target = new Vec3(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5);
			TeleportTransition transition = new TeleportTransition(destination, target, Vec3.ZERO,
					player.getYRot(), player.getXRot(), Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
			ServerPlayer moved = player.teleport(transition);
			if (moved == null) {
				player.teleportTo(destination, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), true);
				moved = player;
			}
			if (!moved.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
				moved.setGameMode(GameType.ADVENTURE);
			}
			moved.sendSystemMessage(Component.literal("ย้ายไปโลก Lobby (พิมพ์ /main เพื่อไปโลกหลัก) แล้ว"));
			return;
		}

		BlockPos sp = spawnPos(destination);
		int rx = sp.getX();
		int rz = sp.getZ();
		int ry = sp.getY();
		boolean isRandom = "resource".equalsIgnoreCase(def.name());
		if (isRandom) {
			BlockPos safePos = WorldSpawnProtection.findSafeSpawnLocation(destination, -2500, 2500, -2500, 2500);
			if (safePos != null) {
				rx = safePos.getX();
				ry = safePos.getY();
				rz = safePos.getZ();
			}
		}

		Vec3 target = new Vec3(rx + 0.5, Math.max(ry, destination.getMinY() + 1), rz + 0.5);
		TeleportTransition transition = new TeleportTransition(destination, target, Vec3.ZERO,
				player.getYRot(), player.getXRot(), Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
		ServerPlayer moved = player.teleport(transition);
		if (moved == null) {
			player.teleportTo(destination, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), true);
			moved = player;
		}
		if (moved.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
			moved.setGameMode(GameType.SURVIVAL);
		}
		if (isRandom) {
			moved.sendSystemMessage(Component.literal("ย้ายไปโลก " + def.name() + " (พิกัดสุ่ม " + rx + ", " + ry + ", " + rz + ") แล้ว"));
		} else {
			moved.sendSystemMessage(Component.literal("ย้ายไปโลก " + def.name() + " แล้ว"));
		}
	}

	public void teleportToMainWorld(ServerPlayer player) {
		MinecraftServer s = server;
		if (s == null) throw new IllegalStateException("เซิร์ฟเวอร์ยังไม่พร้อม");
		ServerLevel mainWorld = s.overworld();
		if (player.level() == mainWorld) {
			player.sendSystemMessage(Component.literal("คุณอยู่ในโลกหลักอยู่แล้ว"));
			return;
		}
		if (teleports != null) {
			teleports.startWarmup(player, Component.literal("โลกหลัก"), () -> executeTeleportToMainWorld(player));
		} else {
			executeTeleportToMainWorld(player);
		}
	}

	private void executeTeleportToMainWorld(ServerPlayer player) {
		MinecraftServer s = server;
		if (s == null) return;
		ServerLevel mainWorld = s.overworld();
		MainWorldPos saved = mainWorldPositions.get(player.getUUID());
		Vec3 target;
		float yaw = 0f;
		float pitch = 0f;
		boolean firstTime = (saved == null);
		if (saved != null) {
			target = new Vec3(saved.x(), saved.y(), saved.z());
			yaw = saved.yaw();
			pitch = saved.pitch();
		} else {
			BlockPos safePos = WorldSpawnProtection.findSafeSpawnLocation(mainWorld, -5000, 5000, -5000, 5000);
			target = new Vec3(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
			mainWorldPositions.put(player.getUUID(), new MainWorldPos(target.x, target.y, target.z, 0f, 0f));
			saveMainWorldPositions();
		}
		TeleportTransition transition = new TeleportTransition(mainWorld, target, Vec3.ZERO,
				yaw, pitch, Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
		ServerPlayer moved = player.teleport(transition);
		if (moved == null) throw new IllegalStateException("ย้ายผู้เล่นไม่สำเร็จ");
		if (moved.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
			moved.setGameMode(GameType.SURVIVAL);
		}
		if (ClaimMap.instance() != null && ClaimMap.instance().spawnProtection() != null) {
			ClaimMap.instance().spawnProtection().grantProtection(moved);
		}
		moved.sendSystemMessage(Component.literal(firstTime
				? "ยินดีต้อนรับสู่โลกหลัก (พิกัดสุ่ม " + (int)target.x + ", " + (int)target.y + ", " + (int)target.z + ")"
				: "กลับสู่โลกหลักที่ตำแหน่งเดิมแล้ว (พิกัด " + (int)target.x + ", " + (int)target.y + ", " + (int)target.z + ")"));
	}

	public WorldInfo createWorld(String name) throws Exception {
		return onServer(() -> {
			String cleanName = name.toLowerCase(Locale.ROOT).trim();
			if (!WorldScanner.SAFE_NAME.matcher(cleanName).matches()) {
				throw new IllegalArgumentException("ชื่อโลกไม่ถูกต้อง (ต้องเป็น a-z, 0-9, _ หรือ - และยาวไม่เกิน 32)");
			}
			Path worldFolder = directory.resolve(cleanName);
			if (Files.exists(worldFolder)) {
				throw new IllegalStateException("มีโฟลเดอร์โลกชื่อ " + cleanName + " อยู่แล้ว");
			}
			Files.createDirectories(worldFolder);
			Path mainLevelDat = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("level.dat");
			if (Files.exists(mainLevelDat)) {
				Files.copy(mainLevelDat, worldFolder.resolve("level.dat"));
			}
			Path mainSettings = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/minecraft/world_gen_settings.dat");
			if (Files.exists(mainSettings)) {
				Path destSettingsDir = worldFolder.resolve("data/minecraft");
				Files.createDirectories(destSettingsDir);
				Files.copy(mainSettings, destSettingsDir.resolve("world_gen_settings.dat"));
			}
			scanLocked();
			WorldDefinition def = require(cleanName);
			return info(def);
		});
	}

	private BlockPos spawnPos(ServerLevel level) {
		BlockPos pos = level.getRespawnData().pos();
		if (pos == null) pos = BlockPos.ZERO;
		int x = pos.getX();
		int z = pos.getZ();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
		return new BlockPos(x, Math.max(y, level.getMinY() + 1), z);
	}

	// ---- commands ----------------------------------------------------------

	private void registerAdminCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		if (dispatcher.getRoot().getChild("worlds") != null) {
			LOGGER.warn("[worlds] /worlds is already registered by another mod; admin UI remains available");
			return;
		}
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("worlds")
				.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN));
		root.then(Commands.literal("list").executes(ctx -> listCommand(ctx.getSource())));
		root.then(Commands.literal("scan").executes(ctx -> scanCommand(ctx.getSource())));
		dispatcher.register(root);
	}

	private void registerWorldCommand(WorldDefinition def) {
		MinecraftServer s = server;
		if (s == null || !config.worldPlayerCommands) return;
		if (def.commandAvailable && s.getCommands().getDispatcher().getRoot().getChild(def.name()) != null) {
			return;
		}
		CommandDispatcher<CommandSourceStack> dispatcher = s.getCommands().getDispatcher();
		CommandNode<CommandSourceStack> existing = dispatcher.getRoot().getChild(def.name());
		if (existing != null) {
			if (studio.timeandtime.claimmap.CommandFilter.ALLOWED_MOD_COMMANDS.contains(def.name().toLowerCase(Locale.ROOT))
					|| "worlds".equalsIgnoreCase(def.name())) {
				boolean alreadyReported = def.commandConflict != null;
				def.commandAvailable = false;
				def.commandConflict = "ชื่อซ้ำกับคำสั่งหลักของเซิร์ฟเวอร์";
				if (!alreadyReported) {
					LOGGER.warn("[worlds] /{} conflicts with a core TimeSMP command", def.name());
				}
				return;
			}
			removeCommandNode(dispatcher.getRoot(), def.name());
		}
		dispatcher.register(Commands.literal(def.name()).executes(ctx -> {
			ServerPlayer player = ctx.getSource().getPlayer();
			if (player == null) {
				ctx.getSource().sendFailure(Component.literal("คำสั่งนี้ใช้ได้เฉพาะผู้เล่นในเกม"));
				return 0;
			}
			try {
				loadLocked(def);
				teleportLocked(player, def);
				return 1;
			} catch (Exception e) {
				ctx.getSource().sendFailure(Component.literal(e.getMessage()));
				return 0;
			}
		}));
		def.commandAvailable = true;
		def.commandConflict = null;
		sendCommands();
	}

	public static void removeCommandNode(CommandNode<?> node, String name) {
		try {
			java.lang.reflect.Field childrenField = CommandNode.class.getDeclaredField("children");
			childrenField.setAccessible(true);
			((Map<?, ?>) childrenField.get(node)).remove(name);

			java.lang.reflect.Field literalsField = CommandNode.class.getDeclaredField("literals");
			literalsField.setAccessible(true);
			((Map<?, ?>) literalsField.get(node)).remove(name);

			java.lang.reflect.Field argumentsField = CommandNode.class.getDeclaredField("arguments");
			argumentsField.setAccessible(true);
			((Map<?, ?>) argumentsField.get(node)).remove(name);
		} catch (Exception e) {
			LOGGER.warn("[worlds] Failed to remove command node {}: {}", name, e.getMessage());
		}
	}

	private void registerPlayerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("main").executes(ctx -> {
			ServerPlayer p = ctx.getSource().getPlayer();
			if (p == null) {
				ctx.getSource().sendFailure(Component.literal("คำสั่งนี้ใช้ได้เฉพาะผู้เล่น"));
				return 0;
			}
			teleportToMainWorld(p);
			return 1;
		}));
	}

	private int createCommand(CommandSourceStack source, String name) {
		try {
			WorldInfo info = createWorld(name);
			source.sendSuccess(() -> Component.literal("สร้างโลก " + info.name() + " สำเร็จแล้ว พิมพ์ /" + info.name() + " เพื่อย้ายไป"), true);
			return 1;
		} catch (Exception e) {
			source.sendFailure(Component.literal("สร้างโลกไม่สำเร็จ: " + e.getMessage()));
			return 0;
		}
	}

	private void unregisterWorldCommand(WorldDefinition def) {
		MinecraftServer s = server;
		if (s == null || !def.commandAvailable) return;
		CommandDispatcher<CommandSourceStack> dispatcher = s.getCommands().getDispatcher();
		removeCommandNode(dispatcher.getRoot(), def.name());
		def.commandAvailable = false;
		sendCommands();
	}

	private void sendCommands() {
		MinecraftServer s = server;
		if (s == null || s.getPlayerList() == null) return;
		for (ServerPlayer player : s.getPlayerList().getPlayers()) {
			s.getCommands().sendCommands(player);
		}
	}

	private int listCommand(CommandSourceStack source) {
		List<WorldInfo> infos = snapshot();
		if (infos.isEmpty()) {
			source.sendSuccess(() -> Component.literal("ไม่พบโลกใน " + directory()), false);
			return 0;
		}
		for (WorldInfo info : infos) {
			source.sendSuccess(() -> Component.literal(String.format("%s | %s | %s | ผู้เล่น %d",
					info.name(), info.state(), info.loaded() ? "โหลดแล้ว" : "ยังไม่โหลด",
					info.players())), false);
		}
		return infos.size();
	}

	private int scanCommand(CommandSourceStack source) {
		MinecraftServer s = server;
		if (s == null) {
			source.sendFailure(Component.literal("เซิร์ฟเวอร์เกมยังไม่พร้อม"));
			return 0;
		}
		// Registering roots touches the command tree; do it after this command
		// returns instead of mutating the tree while Brigadier is executing it.
		s.execute(() -> {
			try {
				scanLocked();
				source.sendSuccess(() -> Component.literal("สแกน worlds แล้ว: " + worlds.size() + " โลก"), false);
			} catch (Exception e) {
				source.sendFailure(Component.literal(e.getMessage()));
			}
		});
		return 1;
	}

	private int loadCommand(CommandSourceStack source, String name) {
		try {
			load(name);
			source.sendSuccess(() -> Component.literal("โหลดโลก " + name + " แล้ว"), false);
			return 1;
		} catch (Exception e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}

	private int unloadCommand(CommandSourceStack source, String name) {
		try {
			unload(name);
			source.sendSuccess(() -> Component.literal("ปิดโลก " + name + " แล้ว"), false);
			return 1;
		} catch (Exception e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}

	// ---- utilities ---------------------------------------------------------

	private Path resolveDirectory(MinecraftServer s) {
		Path configured = Path.of(config.worldDirectory);
		return configured.isAbsolute() ? configured : s.getServerDirectory().resolve(configured);
	}

	private <T> T onServer(Callable<T> action) throws Exception {
		MinecraftServer s = server;
		if (s == null) throw new IllegalStateException("เซิร์ฟเวอร์เกมยังไม่พร้อม");
		if (s.isSameThread()) return action.call();
		CompletableFuture<T> future = new CompletableFuture<>();
		s.execute(() -> {
			try {
				future.complete(action.call());
			} catch (Throwable e) {
				future.completeExceptionally(e);
			}
		});
		try {
			return future.get(30, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("เซิร์ฟเวอร์เกมใช้เวลานานเกินไป");
		}
	}
}
