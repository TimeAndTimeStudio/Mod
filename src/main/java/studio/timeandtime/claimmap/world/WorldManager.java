package studio.timeandtime.claimmap.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.serialization.Dynamic;
import studio.timeandtime.claimmap.ActionBar;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
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
	/**
	 * Real-world rate for the lobby clock: one in-game day (24000 ticks) per 24
	 * real hours. Minecraft runs 20 ticks per real second, so 24h = 1,728,000
	 * ticks. 24000 / 1728000 = 0.013888... ticks per tick.
	 */
	private static final float LOBBY_REAL_TIME_RATE = 24000f / (86400f * 20f);

	private final ClaimMapConfig config;
	private final RealWorldSync realWorld;
	private final WorldScanner scanner = new WorldScanner();
	private final Map<String, WorldDefinition> worlds = new LinkedHashMap<>();
	private volatile MinecraftServer server;
	private volatile Path directory;
	private volatile LevelStorageSource storageSource;
	private volatile boolean stopping;
	private volatile studio.timeandtime.claimmap.TeleportManager teleports;
	private int tickCounter;
	private RealWorldSync.Weather lastAppliedWeather;

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
		this.realWorld = new RealWorldSync(config);
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
		realWorld.start();
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
		// Keep the lobby clock pinned to the external real-world time and drive
		// lobby weather from the live conditions at the configured coordinates.
		if (s.getTickCount() % 20 == 0) {
			syncLobbyClock();
			applyLobbyWeather();
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
		realWorld.stop();
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
			def.reloadConfig();
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
			if ("lobby".equalsIgnoreCase(def.name())) {
				stem = lobbyDimensionStem(s, stem);
			} else if ("find".equalsIgnoreCase(def.name())) {
				stem = findDimensionStem(s, stem);
			}

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

			// Lobby world: no natural mob spawning and permanently morning.
			if ("lobby".equalsIgnoreCase(def.name())) {
				s.execute(() -> applyLobbyEnvironment(level));
			}

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

	/**
	 * Lobby world rules: disable natural mob spawning, stop vanilla from
	 * advancing the weather on its own, and drive the lobby's dedicated clock
	 * (timeandtime:lobby) at real-world speed so its day/night matches the
	 * external real-world clock. The lobby uses its own world clock (isolated
	 * from the main overworld clock), so this does not affect the main world's
	 * day/night cycle. Weather is applied from {@link RealWorldSync} on the
	 * next server tick.
	 * Minecraft 26.3 renamed the old doDaylightCycle/doMobSpawning rules to
	 * advance_time/advance_weather/spawn_mobs.
	 */
	private void applyLobbyEnvironment(ServerLevel level) {
		MinecraftServer s = server;
		if (s == null || level == null) return;
		try {
			CommandSourceStack source = s.createCommandSourceStack()
					.withLevel(level)
					.withSuppressedOutput();
			s.getCommands().performPrefixedCommand(source, "gamerule spawn_mobs false");
			s.getCommands().performPrefixedCommand(source, "gamerule advance_weather false");
			// Neutral starting state; the real weather is applied as soon as the
			// first external reading is available.
			s.getCommands().performPrefixedCommand(source, "weather clear");
			lastAppliedWeather = null;
		} catch (Throwable e) {
			LOGGER.warn("[worlds] Could not apply lobby gamerules: {}", e.getMessage());
		}
		setupLobbyClock(s);
	}

	/**
	 * Returns a LevelStem whose dimension type is the dedicated lobby clock
	 * (timeandtime:lobby) and whose generator is THE_VOID flat generator,
	 * so the lobby's day/night is isolated from the main overworld clock,
	 * any newly explored chunks generate as pure void air (no mountains/trees),
	 * and no visible blue worldborder barrier is needed.
	 */
	private LevelStem lobbyDimensionStem(MinecraftServer s, LevelStem stem) {
		try {
			ResourceKey<DimensionType> key = ResourceKey.create(Registries.DIMENSION_TYPE,
					Identifier.fromNamespaceAndPath("timeandtime", "lobby"));
			Holder<DimensionType> holder = s.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key);
			net.minecraft.world.level.chunk.ChunkGenerator generator = stem.generator();
			try {
				var voidPreset = s.registryAccess().lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
						.getOrThrow(net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets.THE_VOID)
						.value();
				generator = new net.minecraft.world.level.levelgen.FlatLevelSource(voidPreset.settings());
				LOGGER.info("[worlds] Lobby uses THE_VOID chunk generator (endless void outside structure, no trees)");
			} catch (Throwable e) {
				LOGGER.warn("[worlds] Could not attach THE_VOID generator to lobby: {}", e.getMessage());
			}
			LOGGER.info("[worlds] Lobby uses isolated dimension type timeandtime:lobby (own world clock)");
			return new LevelStem(holder, generator);
		} catch (Throwable e) {
			LOGGER.warn("[worlds] Could not apply isolated lobby dimension type: {}", e.getMessage());
			return stem;
		}
	}

	/**
	 * Returns a LevelStem whose dimension type is the dedicated find clock
	 * (timeandtime:find), so the find world's day/night is isolated from the main
	 * overworld clock and runs independently.
	 */
	private LevelStem findDimensionStem(MinecraftServer s, LevelStem stem) {
		try {
			ResourceKey<DimensionType> key = ResourceKey.create(Registries.DIMENSION_TYPE,
					Identifier.fromNamespaceAndPath("timeandtime", "find"));
			Holder<DimensionType> holder = s.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key);
			LOGGER.info("[worlds] Find world uses isolated dimension type timeandtime:find (own world clock)");
			return new LevelStem(holder, stem.generator());
		} catch (Throwable e) {
			LOGGER.warn("[worlds] Could not apply isolated find dimension type: {}", e.getMessage());
			return stem;
		}
	}

	/**
	 * Configures the lobby's dedicated clock to advance at real-world speed and
	 * starts it at the current external real-world time of day.
	 */
	private void setupLobbyClock(MinecraftServer s) {
		if (s == null) return;
		try {
			ServerClockManager clockManager = s.clockManager();
			Holder<WorldClock> clock = lobbyClockHolder(s);
			clockManager.setPaused(clock, false);
			clockManager.setRate(clock, LOBBY_REAL_TIME_RATE);
			clockManager.setTotalTicks(clock, realWorldLobbyTicks());
			LOGGER.info("[worlds] Lobby clock set to real-world time ({} in {}, source: {})",
					realWorld.now().toLocalTime().withNano(0), realWorld.zone(), realWorld.timeSource());
		} catch (Throwable e) {
			LOGGER.warn("[worlds] Could not set lobby real-world clock: {}", e.getMessage());
		}
	}

	/** Corrects the lobby clock's drift so it stays pinned to the external real time. */
	private void syncLobbyClock() {
		MinecraftServer s = server;
		if (s == null || stopping || !hasLobbyWorld()) return;
		try {
			ServerClockManager clockManager = s.clockManager();
			Holder<WorldClock> clock = lobbyClockHolder(s);
			clockManager.setTotalTicks(clock, realWorldLobbyTicks());
		} catch (Throwable ignored) {
			// best effort; the clock keeps its last known real time
		}
	}

	/**
	 * Applies the real-world weather fetched by {@link RealWorldSync} to the
	 * lobby level only. Weather commands are given a long duration so the
	 * state persists between polls ({@code advance_weather} is off anyway).
	 */
	private void applyLobbyWeather() {
		if (!config.lobbyWeather) return;
		MinecraftServer s = server;
		if (s == null || stopping) return;
		WorldDefinition def;
		synchronized (worlds) {
			def = worlds.get("lobby");
		}
		if (def == null || !def.loaded()) return;
		ServerLevel level = def.level;
		if (level == null) return;
		RealWorldSync.Weather next = realWorld.weather();
		if (!realWorld.weatherKnown() || next == lastAppliedWeather) return;
		String command = switch (next) {
			case CLEAR -> "weather clear 1000000";
			case RAIN -> "weather rain 1000000";
			case THUNDER -> "weather thunder 1000000";
		};
		try {
			CommandSourceStack source = s.createCommandSourceStack()
					.withLevel(level)
					.withSuppressedOutput();
			s.getCommands().performPrefixedCommand(source, command);
			lastAppliedWeather = next;
			LOGGER.info("[worlds] Lobby weather set to {} (real weather code {})", next, realWorld.weatherCode());
		} catch (Throwable e) {
			LOGGER.warn("[worlds] Could not apply lobby weather: {}", e.getMessage());
		}
	}

	private Holder<WorldClock> lobbyClockHolder(MinecraftServer s) {
		ResourceKey<WorldClock> key = ResourceKey.create(Registries.WORLD_CLOCK,
				Identifier.fromNamespaceAndPath("timeandtime", "lobby"));
		return s.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(key);
	}

	/**
	 * Maps the current external real-world time of day to in-game ticks.
	 * Minecraft day time is 0-23999 where 0 = 06:00 (sunrise), 6000 = noon,
	 * 12000 = 18:00 and 18000 = midnight. Convert the real local time of day
	 * accordingly (real 00:00 -> 18000, real 06:00 -> 0, real 14:26 -> ~8433).
	 */
	private long realWorldLobbyTicks() {
		long nanoOfDay = realWorld.now().toLocalTime().toNanoOfDay();
		double mcHours = (nanoOfDay / 3_600_000_000_000.0 - 6.0 + 24.0) % 24.0;
		return Math.round(mcHours / 24.0 * 24000.0);
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

	public void recordPlayerCurrentWorldPosition(ServerPlayer player) {
		if (server == null || player == null) return;
		ServerLevel currentLevel = player.level();
		if (currentLevel == server.overworld()) {
			recordMainWorldPosition(player);
			return;
		}
		if (isLobby(currentLevel)) return;
		for (WorldDefinition def : worlds.values()) {
			if (def.level == currentLevel) {
				def.recordPosition(player.getUUID(), player.getX(), player.getY(), player.getZ(),
						player.getYRot(), player.getXRot());
				break;
			}
		}
	}

	public void clearPlayerWorldPositionOnDeath(ServerPlayer player) {
		if (server == null || player == null) return;
		ServerLevel deathLevel = player.level();
		if (deathLevel == server.overworld()) {
			clearMainWorldPosition(player.getUUID());
			return;
		}
		for (WorldDefinition def : worlds.values()) {
			if (def.level == deathLevel) {
				def.clearSavedPosition(player.getUUID());
				break;
			}
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

		// Remember where player was before teleporting
		recordPlayerCurrentWorldPosition(player);

		WorldConfig worldCfg = def.config();
		if (worldCfg == null) {
			def.reloadConfig();
			worldCfg = def.config();
		}

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
				moved.setGameMode(worldCfg.gameMode());
			}
			if (ClaimMap.instance() != null && ClaimMap.instance().spawnProtection() != null) {
				ClaimMap.instance().spawnProtection().clearProtection(moved);
			}
			ActionBar.send(moved, "ย้ายไปโลก Lobby (พิมพ์ /main เพื่อไปโลกหลัก) แล้ว");
			return;
		}

		SavedPos saved = def.getSavedPosition(player.getUUID());
		Vec3 target;
		float targetYaw = player.getYRot();
		float targetPitch = player.getXRot();
		boolean isFirstTimeRandom = false;

		if (saved != null) {
			target = new Vec3(saved.x(), saved.y(), saved.z());
			targetYaw = saved.yaw();
			targetPitch = saved.pitch();
		} else {
			BlockPos sp = spawnPos(destination);
			int rx = sp.getX();
			int rz = sp.getZ();
			int ry = sp.getY();
			boolean isRandom = worldCfg.randomSpawn();
			if (isRandom) {
				int rad = worldCfg.randomSpawnRadius();
				BlockPos safePos = WorldSpawnProtection.findSafeSpawnLocation(destination, -rad, rad, -rad, rad);
				if (safePos != null) {
					rx = safePos.getX();
					ry = safePos.getY();
					rz = safePos.getZ();
					isFirstTimeRandom = true;
				}
			}
			target = new Vec3(rx + 0.5, Math.max(ry, destination.getMinY() + 1), rz + 0.5);
			// Save the initial spawn position so they don't get randomly scattered on simple reconnects
			def.recordPosition(player.getUUID(), target.x, target.y, target.z, targetYaw, targetPitch);
		}

		TeleportTransition transition = new TeleportTransition(destination, target, Vec3.ZERO,
				targetYaw, targetPitch, Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
		ServerPlayer moved = player.teleport(transition);
		if (moved == null) {
			player.teleportTo(destination, target.x, target.y, target.z, Set.of(), targetYaw, targetPitch, true);
			moved = player;
		}
		if (!moved.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
			moved.setGameMode(worldCfg.gameMode());
		}
		if (ClaimMap.instance() != null && ClaimMap.instance().spawnProtection() != null) {
			ClaimMap.instance().spawnProtection().grantProtection(moved);
		}
		if (isFirstTimeRandom) {
			ActionBar.send(moved, "ย้ายไปโลก " + def.name() + " (พิกัดสุ่ม " + (int) target.x + ", " + (int) target.y + ", " + (int) target.z + ") พร้อมรับการคุ้มครอง 30 วินาที");
		} else {
			ActionBar.send(moved, "ย้ายไปโลก " + def.name() + " พร้อมรับการคุ้มครอง 30 วินาที");
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
		// Remember where player was before teleporting to main world
		recordPlayerCurrentWorldPosition(player);
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
		ActionBar.send(moved, firstTime
				? "ยินดีต้อนรับสู่โลกหลัก (พิกัดสุ่ม " + (int)target.x + ", " + (int)target.y + ", " + (int)target.z + ") พร้อมรับการคุ้มครอง 30 วินาที"
				: "กลับสู่โลกหลักที่ตำแหน่งเดิมแล้ว (พิกัด " + (int)target.x + ", " + (int)target.y + ", " + (int)target.z + ") พร้อมรับการคุ้มครอง 30 วินาที");
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
		boolean lobby = isLobby(level);
		BlockPos pos = lobby ? new BlockPos(0, 64, 0) : level.getRespawnData().pos();
		if (pos == null) pos = new BlockPos(0, 64, 0);
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_SPAWN, new ChunkPos(cx, cz), 2);
		ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
		int targetY = lobby ? 64 : pos.getY();
		if (chunk != null) {
			int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX() & 15, pos.getZ() & 15);
			if (surfaceY > level.getMinY() + 5) {
				targetY = lobby ? (surfaceY + 1) : Math.max(pos.getY(), surfaceY + 1);
			}
			while (targetY < level.getMaxY() - 2) {
				BlockState feet = chunk.getBlockState(new BlockPos(pos.getX(), targetY, pos.getZ()));
				BlockState head = chunk.getBlockState(new BlockPos(pos.getX(), targetY + 1, pos.getZ()));
				if ((feet.isAir() || !feet.isSolid()) && (head.isAir() || !head.isSolid())) {
					break;
				}
				targetY++;
			}
		}
		if (targetY <= level.getMinY() + 5) targetY = 64;
		return new BlockPos(pos.getX(), targetY, pos.getZ());
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
