package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles:
 * 1. Random Overworld spawn within 10,000 x 10,000 range (-5000..5000) for new players & bedless respawns.
 * 2. 30-second damage immunity & protection upon entering a world / joining / respawning.
 */
public final class WorldSpawnProtection {
	private static final Gson GSON = new Gson();
	private static final int RANGE = 5000; // -5000 to +5000 (10000x10000 area)
	private static final int PROTECTION_SECONDS = 30;

	private final Path file;
	private final Set<UUID> spawnedPlayers = new HashSet<>();
	private final Map<UUID, Long> protectedUntil = new ConcurrentHashMap<>();

	public WorldSpawnProtection(Path dataDir) {
		this.file = dataDir.resolve("spawned-players.json");
		load();
	}

	public void register() {
		// 1. On Player Join:
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (player == null) return;

			boolean isNew = false;
			synchronized (spawnedPlayers) {
				if (!spawnedPlayers.contains(player.getUUID())) {
					spawnedPlayers.add(player.getUUID());
					save();
					isNew = true;
				}
			}

			final boolean firstTime = isNew;
			server.execute(() -> {
				if (!player.isAlive()) return;
				var wm = ClaimMap.instance() != null ? ClaimMap.instance().worldManager() : null;
				boolean hasLobby = wm != null && wm.hasLobbyWorld();
				if (hasLobby) {
					ServerLevel lobby = wm.getLobbyLevel();
					if (lobby != null) {
						if (wm != null && wm.hasMainWorldPosition(player.getUUID()) && player.level() == server.overworld()) {
							wm.recordMainWorldPosition(player);
						}
						teleportToLobbySpawn(player, lobby, firstTime);
						return;
					}
				}
				if (firstTime) {
					ServerLevel overworld = server.overworld();
					randomSpawnPlayer(player, overworld, true);
				} else {
					if (!studio.timeandtime.claimmap.world.WorldManager.isLobby(player.level())) {
						grantProtection(player);
					}
				}
			});
		});

		// On Player Disconnect:
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (player != null) {
				var wm = ClaimMap.instance() != null ? ClaimMap.instance().worldManager() : null;
				if (wm != null) {
					wm.recordPlayerCurrentWorldPosition(player);
				}
			}
		});

		// 2. On Player Death: Show coordinates in chat & clear saved world position
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				int dx = (int) player.getX();
				int dy = (int) player.getY();
				int dz = (int) player.getZ();
				String worldName = player.level().dimension().identifier().getPath();
				player.sendSystemMessage(Component.literal(
						"§c[Time SMP] ตำแหน่งที่คุณเสียชีวิต: §eX: " + dx + ", Y: " + dy + ", Z: " + dz
								+ " §7(โลก: §f" + worldName + "§7)"));

				var wm = ClaimMap.instance() != null ? ClaimMap.instance().worldManager() : null;
				if (wm != null) {
					wm.clearPlayerWorldPositionOnDeath(player);
				}
			}
		});

		// 3. On Player Respawn: Always respawn in lobby if lobby exists!
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			var wm = ClaimMap.instance() != null ? ClaimMap.instance().worldManager() : null;
			boolean hasLobby = wm != null && wm.hasLobbyWorld();
			if (hasLobby) {
				ServerLevel lobby = wm.getLobbyLevel();
				if (lobby != null) {
					newPlayer.level().getServer().execute(() -> {
						teleportToLobbySpawn(newPlayer, lobby, false);
					});
					return;
				}
			}
			if (newPlayer.getRespawnConfig() == null) {
				ServerLevel overworld = newPlayer.level().getServer().overworld();
				newPlayer.level().getServer().execute(() -> {
					randomSpawnPlayer(newPlayer, overworld, false);
				});
			} else {
				if (!studio.timeandtime.claimmap.world.WorldManager.isLobby(newPlayer.level())) {
					grantProtection(newPlayer);
				}
			}
		});

		// 3. On Dimension / World Change:
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			boolean enteringLobby = studio.timeandtime.claimmap.world.WorldManager.isLobby(destination);
			boolean leavingLobby = studio.timeandtime.claimmap.world.WorldManager.isLobby(origin);
			if (enteringLobby) {
				if (!player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN)) {
					player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
				}
				clearProtection(player);
			} else {
				if (leavingLobby) {
					if (player.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.ADVENTURE) {
						player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
					}
				}
				grantProtection(player);
			}
		});

		// 4. Damage Cancellation while protected or in lobby:
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) {
				if (isProtected(player.getUUID())) {
					return false;
				}
				if (studio.timeandtime.claimmap.world.WorldManager.isLobby(player.level())) {
					return false;
				}
			}
			return true;
		});

		// 5. Server Tick to expire protection cleanly:
		ServerTickEvents.END_SERVER_TICK.register(server -> tick());
	}

	public void tick() {
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, Long> entry : protectedUntil.entrySet()) {
			if (now >= entry.getValue()) {
				protectedUntil.remove(entry.getKey());
				if (ClaimMap.getServer() != null) {
					ServerPlayer sp = ClaimMap.getServer().getPlayerList().getPlayer(entry.getKey());
					if (sp != null && !studio.timeandtime.claimmap.world.WorldManager.isLobby(sp.level())) {
						ActionBar.send(sp, "§e[ระบบคุ้มครอง] หมดเวลาคุ้มครองความปลอดภัย 30 วินาทีแล้ว");
					}
				}
			}
		}
		if (ClaimMap.getServer() != null) {
			for (ServerPlayer player : ClaimMap.getServer().getPlayerList().getPlayers()) {
				if (studio.timeandtime.claimmap.world.WorldManager.isLobby(player.level())) {
					if (player.getFoodData().getFoodLevel() < 20) {
						player.getFoodData().setFoodLevel(20);
					}
				}
			}
		}
	}

	public static void teleportToLobbySpawn(ServerPlayer player, ServerLevel lobby, boolean firstTime) {
		int spawnX = 0;
		int spawnZ = 0;
		int cx = spawnX >> 4;
		int cz = spawnZ >> 4;
		lobby.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, new ChunkPos(cx, cz), 2);
		ChunkAccess chunk = lobby.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
		// Stand on the first free spot above the terrain/build at the origin.
		// Do NOT force y=64: lobby maps may be built at the bottom of the
		// world (e.g. a platform on top of the y=-64 floor).
		int targetY = lobby.getMinY() + 1;
		if (chunk != null) {
			int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, spawnX & 15, spawnZ & 15);
			if (surfaceY > lobby.getMinY()) {
				targetY = surfaceY;
			}
			while (targetY < lobby.getMaxY() - 2) {
				BlockState feet = chunk.getBlockState(new BlockPos(spawnX, targetY, spawnZ));
				BlockState head = chunk.getBlockState(new BlockPos(spawnX, targetY + 1, spawnZ));
				if ((feet.isAir() || !feet.isSolid()) && (head.isAir() || !head.isSolid())) {
					break;
				}
				targetY++;
			}
		}
		if (targetY < lobby.getMinY() + 1) targetY = lobby.getMinY() + 1;

		Vec3 target = new Vec3(0.5, targetY, 0.5);
		ServerPlayer moved = player;
		if (player.level() != lobby) {
			TeleportTransition transition = new TeleportTransition(lobby, target, Vec3.ZERO,
					player.getYRot(), player.getXRot(), Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
			ServerPlayer tp = player.teleport(transition);
			if (tp != null) moved = tp;
		} else {
			player.teleportTo(lobby, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), true);
		}

		if (!moved.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN)) {
			moved.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
		}
		if (ClaimMap.instance() != null && ClaimMap.instance().spawnProtection() != null) {
			ClaimMap.instance().spawnProtection().clearProtection(moved);
		}
		if (firstTime) {
			moved.sendSystemMessage(Component.literal("§a[Lobby] ยินดีต้อนรับสู่เซิร์ฟเวอร์! พิมพ์ §f/main §aเพื่อเดินทางเข้าสู่โลกหลัก"));
		} else {
			moved.sendSystemMessage(Component.literal("§a[Lobby] คุณกลับมาที่ Lobby (พิมพ์ §f/main §aเพื่อไปโลกหลัก)"));
		}
	}

	public boolean isProtected(UUID uuid) {
		Long until = protectedUntil.get(uuid);
		return until != null && System.currentTimeMillis() < until;
	}

	public void clearProtection(ServerPlayer player) {
		if (player == null) return;
		protectedUntil.remove(player.getUUID());
		player.removeEffect(MobEffects.RESISTANCE);
		player.removeEffect(MobEffects.FIRE_RESISTANCE);
		player.removeEffect(MobEffects.WATER_BREATHING);
	}

	public void grantProtection(ServerPlayer player) {
		if (player == null) return;
		if (studio.timeandtime.claimmap.world.WorldManager.isLobby(player.level())) {
			return;
		}
		if (isProtected(player.getUUID())) {
			return;
		}
		protectedUntil.put(player.getUUID(), System.currentTimeMillis() + (PROTECTION_SECONDS * 1000L));
		player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, PROTECTION_SECONDS * 20, 4, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, PROTECTION_SECONDS * 20, 0, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, PROTECTION_SECONDS * 20, 0, false, false, true));
		ActionBar.send(player, "§a[ระบบคุ้มครอง] คุณได้รับการคุ้มครองความปลอดภัย 30 วินาที");
	}

	public void randomSpawnPlayer(ServerPlayer player, ServerLevel level, boolean firstJoin) {
		BlockPos safePos = findSafeSpawnLocation(level, -RANGE, RANGE, -RANGE, RANGE);
		Vec3 target = new Vec3(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
		ServerPlayer moved = player;
		if (player.level() != level) {
			TeleportTransition transition = new TeleportTransition(level, target, Vec3.ZERO,
					player.getYRot(), player.getXRot(), Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
			ServerPlayer tp = player.teleport(transition);
			if (tp != null) moved = tp;
		} else {
			player.teleportTo(level, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), true);
		}

		if (firstJoin) {
			moved.sendSystemMessage(Component.literal("§6[จุดเกิดสุ่ม] ยินดีต้อนรับ! คุณเกิดที่พิกัด X: " + safePos.getX() + ", Z: " + safePos.getZ()));
		} else {
			moved.sendSystemMessage(Component.literal("§6[จุดเกิดสุ่ม] ไม่มีจุดเกิดเตียง คุณได้เกิดใหม่ที่พิกัด X: " + safePos.getX() + ", Z: " + safePos.getZ()));
		}

		grantProtection(moved);
	}

	public static BlockPos findSafeSpawnLocation(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
		RandomSource rnd = level.getRandom();
		for (int attempt = 0; attempt < 40; attempt++) {
			int rx = minX + rnd.nextInt(maxX - minX + 1);
			int rz = minZ + rnd.nextInt(maxZ - minZ + 1);

			int cx = rx >> 4;
			int cz = rz >> 4;
			level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, new ChunkPos(cx, cz), 1);
			ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
			if (chunk == null) continue;

			int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, rx & 15, rz & 15);
			if (surfaceY <= level.getMinY() + 5 || surfaceY >= level.getMaxY() - 2) {
				continue;
			}

			BlockPos pGround = new BlockPos(rx, surfaceY, rz);
			BlockState groundState = chunk.getBlockState(pGround);

			if (groundState.is(Blocks.WATER) || groundState.is(Blocks.LAVA)
					|| groundState.is(Blocks.FIRE) || groundState.is(Blocks.MAGMA_BLOCK)
					|| groundState.is(Blocks.CACTUS) || groundState.is(Blocks.SEAGRASS)
					|| groundState.is(Blocks.KELP) || groundState.is(Blocks.TALL_SEAGRASS)) {
				continue;
			}

			if (!groundState.isSolid()) {
				continue;
			}

			int targetY = surfaceY + 1;
			while (targetY < level.getMaxY() - 2) {
				BlockState feet = chunk.getBlockState(new BlockPos(rx, targetY, rz));
				BlockState head = chunk.getBlockState(new BlockPos(rx, targetY + 1, rz));
				if ((feet.isAir() || !feet.isSolid()) && (head.isAir() || !head.isSolid())
						&& !feet.is(Blocks.LAVA) && !feet.is(Blocks.WATER)) {
					return new BlockPos(rx, targetY, rz);
				}
				targetY++;
			}
		}

		// Fallback to world respawn point with loaded chunk check
		BlockPos sp = level.getRespawnData().pos();
		if (sp == null) sp = new BlockPos(0, 64, 0);
		int cx = sp.getX() >> 4;
		int cz = sp.getZ() >> 4;
		level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, new ChunkPos(cx, cz), 1);
		ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
		int fbY = sp.getY();
		if (chunk != null) {
			int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, sp.getX() & 15, sp.getZ() & 15);
			if (surfaceY > level.getMinY() + 5) {
				fbY = Math.max(sp.getY(), surfaceY + 1);
			}
			while (fbY < level.getMaxY() - 2) {
				BlockState feet = chunk.getBlockState(new BlockPos(sp.getX(), fbY, sp.getZ()));
				BlockState head = chunk.getBlockState(new BlockPos(sp.getX(), fbY + 1, sp.getZ()));
				if ((feet.isAir() || !feet.isSolid()) && (head.isAir() || !head.isSolid())) {
					break;
				}
				fbY++;
			}
		}
		return new BlockPos(sp.getX(), fbY, sp.getZ());
	}

	private void load() {
		if (!Files.exists(file)) return;
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			Set<UUID> set = GSON.fromJson(json, new TypeToken<Set<UUID>>(){}.getType());
			if (set != null) {
				synchronized (spawnedPlayers) {
					spawnedPlayers.addAll(set);
				}
			}
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not load spawned-players.json: {}", e.getMessage());
		}
	}

	private void save() {
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			synchronized (spawnedPlayers) {
				Files.writeString(file, GSON.toJson(spawnedPlayers), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not save spawned-players.json: {}", e.getMessage());
		}
	}
}
