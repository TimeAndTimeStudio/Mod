package studio.timeandtime.claimmap;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Login gate for players entering the game (PHASES.md phase 3).
 *
 * <p>Until a player authenticates with the same password as the web account,
 * they cannot move, break/place blocks, use items, attack, drop items, chat or
 * run commands (only {@code /login} and {@code /register}).
 *
 * <p>Verification runs on a small worker thread because PBKDF2 is deliberately
 * slow; results are applied back on the server thread via {@code server.execute}.
 */
public final class AuthGate {
	public enum Phase { NEED_LOGIN, NEED_REGISTER, AUTHED }

	static final int MAX_FAILS = 3;
	static final long TIMEOUT_MS = 60_000L;
	private static final long REMIND_EVERY_MS = 15_000L;

	static final class State {
		Phase phase;
		int fails;
		long joinedAtMs;
		double x;
		double y;
		double z;
	}

	private final ClaimMapConfig config;
	private final AccountManager accounts;
	private final LinkManager links;
	private final EconomyManager economy;
	private final Map<UUID, State> states = new ConcurrentHashMap<>();
	private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "claimmap-auth");
		t.setDaemon(true);
		return t;
	});

	public AuthGate(ClaimMapConfig config, AccountManager accounts, LinkManager links, EconomyManager economy) {
		this.config = config;
		this.accounts = accounts;
		this.links = links;
		this.economy = economy;
	}

	// ---- pure state machine (unit tested) -----------------------------------

	State begin(UUID uuid, boolean hasPassword, long now, double x, double y, double z) {
		State s = new State();
		s.phase = hasPassword ? Phase.NEED_LOGIN : Phase.NEED_REGISTER;
		s.joinedAtMs = now;
		s.x = x;
		s.y = y;
		s.z = z;
		states.put(uuid, s);
		return s;
	}

	public boolean isAuthed(UUID uuid) {
		State s = uuid == null ? null : states.get(uuid);
		return s != null && s.phase == Phase.AUTHED;
	}

	Phase phase(UUID uuid) {
		State s = uuid == null ? null : states.get(uuid);
		return s == null ? null : s.phase;
	}

	/** Records a failed attempt, returning the new consecutive failure count. */
	int fail(UUID uuid) {
		State s = states.get(uuid);
		if (s == null) return 0;
		return ++s.fails;
	}

	boolean timedOut(UUID uuid, long now) {
		State s = states.get(uuid);
		return s != null && s.phase != Phase.AUTHED && now - s.joinedAtMs >= TIMEOUT_MS;
	}

	boolean shouldKickForFails(UUID uuid) {
		State s = states.get(uuid);
		return s != null && s.fails >= MAX_FAILS;
	}

	void markAuthed(UUID uuid) {
		State s = states.get(uuid);
		if (s != null) s.phase = Phase.AUTHED;
	}

	void clear(UUID uuid) {
		states.remove(uuid);
		pending.remove(uuid);
	}

	/** Username in the allowed state list. */
	public boolean allowed(ServerPlayer player) {
		return config.isAllowed(player.getGameProfile().name());
	}

	// ---- hooks --------------------------------------------------------------

	public void registerHooks() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.getPlayer().getUUID()));
		ServerPlayerEvents.LEAVE.register(this::clearPlayer);

		ServerTickEvents.END_SERVER_TICK.register(this::tick);

		PlayerBlockBreakEvents.BEFORE.register(this::onBreak);
		UseBlockCallback.EVENT.register(this::onUseBlock);
		UseItemCallback.EVENT.register(this::onUseItem);
		AttackEntityCallback.EVENT.register(this::onAttackEntity);

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> isAuthed(sender.getUUID()));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer sp) return isAuthed(sp.getUUID());
			return true;
		});
	}

	private void clearPlayer(ServerPlayer player) {
		clear(player.getUUID());
	}

	private void onJoin(ServerPlayer player) {
		UUID uuid = player.getUUID();
		String name = player.getGameProfile().name();
		if (!config.isAllowed(name)) {
			kick(player, "ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่น");
			return;
		}
		boolean hasPassword = accounts.hasPassword(uuid.toString());
		begin(uuid, hasPassword, System.currentTimeMillis(),
				player.getX(), player.getY(), player.getZ());
		if (hasPassword) {
			msg(player, "=== ยินดีต้อนรับกลับ " + name + " ===");
			msg(player, "พิมพ์ /login <รหัสผ่าน> เพื่อเข้าเล่น");
			msg(player, "ใช้รหัสเดียวกับที่ล็อกอินเว็บ (อย่าใช้รหัสซ้ำกับที่อื่น)");
		} else {
			msg(player, "=== ยินดีต้อนรับ " + name + " ===");
			msg(player, "ตั้งรหัสผ่านก่อนเล่น: /register <รหัสใหม่> <ยืนยันรหัส>");
			msg(player, "รหัสต้องยาว 6-64 ตัวอักษร และใช้ได้ทั้งเกมและเว็บ (อย่าใช้รหัสซ้ำกับที่อื่น)");
		}
	}

	private void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		long tick = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID uuid = player.getUUID();
			State s = states.get(uuid);
			if (s == null || s.phase == Phase.AUTHED) continue;
			if (timedOut(uuid, now)) {
				kick(player, "หมดเวลากรอกรหัส (60 วินาที) กรุณาเข้าใหม่");
				continue;
			}
			// freeze movement: pull the player back to where they joined
			double dx = player.getX() - s.x;
			double dy = player.getY() - s.y;
			double dz = player.getZ() - s.z;
			if (dx * dx + dy * dy + dz * dz > 0.04) {
				player.teleportTo(s.x, s.y, s.z);
			}
			if (tick % 300 == 0) {
				if (s.phase == Phase.NEED_LOGIN) msg(player, "ยังไม่ได้เข้าสู่ระบบ: /login <รหัสผ่าน>");
				else msg(player, "ยังไม่ได้ตั้งรหัส: /register <รหัสใหม่> <ยืนยันรหัส>");
			}
		}
	}

	// ---- action blocking ----------------------------------------------------

	private boolean onBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
		return !(player instanceof ServerPlayer sp) || isAuthed(sp.getUUID());
	}

	private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		return allowedInteraction(player);
	}

	private InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		return allowedInteraction(player);
	}

	private InteractionResult onAttackEntity(Player player, Level level, InteractionHand hand, Entity entity,
			EntityHitResult hit) {
		return allowedInteraction(player);
	}

	private InteractionResult allowedInteraction(Player player) {
		if (player instanceof ServerPlayer sp && !isAuthed(sp.getUUID())) {
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	// ---- login / register ---------------------------------------------------

	public void handleLogin(ServerPlayer player, String password) {
		UUID uuid = player.getUUID();
		State s = states.get(uuid);
		if (s == null) return;
		if (s.phase == Phase.AUTHED) {
			msg(player, "เข้าสู่ระบบแล้ว");
			return;
		}
		if (s.phase == Phase.NEED_REGISTER) {
			msg(player, "ยังไม่มีรหัสผ่าน ใช้ /register <รหัสใหม่> <ยืนยันรหัส>");
			return;
		}
		String name = player.getGameProfile().name();
		if (!pending.add(uuid)) {
			msg(player, "กำลังตรวจสอบรหัส กรุณารอสักครู่");
			return;
		}
		worker.submit(() -> {
			boolean ok;
			try {
				ok = accounts.verify(name, password) != null;
			} catch (RuntimeException e) {
				ok = false;
			}
			final boolean success = ok;
			MinecraftServer server = ClaimMap.getServer();
			if (server != null) server.execute(() -> completeLogin(uuid, success));
		});
	}

	private void completeLogin(UUID uuid, boolean success) {
		pending.remove(uuid);
		MinecraftServer server = ClaimMap.getServer();
		if (server == null) return;
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player == null) return;
		State s = states.get(uuid);
		if (s == null || s.phase == Phase.AUTHED) return;
		if (success) {
			finishAuth(player);
			return;
		}
		int fails = fail(uuid);
		if (fails >= MAX_FAILS) {
			kick(player, "ใส่รหัสผิด " + MAX_FAILS + " ครั้ง กรุณาเข้าใหม่");
		} else {
			msg(player, "รหัสผ่านไม่ถูกต้อง (เหลือ " + (MAX_FAILS - fails) + " ครั้ง)");
		}
	}

	public void handleRegister(ServerPlayer player, String password, String confirm) {
		UUID uuid = player.getUUID();
		State s = states.get(uuid);
		if (s == null) return;
		if (s.phase == Phase.AUTHED) {
			msg(player, "เข้าสู่ระบบแล้ว หากต้องการเปลี่ยนรหัสใช้ /pass <รหัสเดิม> <รหัสใหม่>");
			return;
		}
		if (s.phase == Phase.NEED_LOGIN) {
			msg(player, "บัญชีนี้มีรหัสแล้ว ใช้ /login <รหัสผ่าน>");
			return;
		}
		String name = player.getGameProfile().name();
		String problem = passwordProblem(password, confirm, name);
		if (problem != null) {
			msg(player, problem);
			return;
		}
		if (!pending.add(uuid)) {
			msg(player, "กำลังดำเนินการ กรุณารอสักครู่");
			return;
		}
		worker.submit(() -> {
			String hash = PasswordHasher.hash(password, config.pbkdf2Iterations);
			MinecraftServer server = ClaimMap.getServer();
			if (server != null) server.execute(() -> {
				pending.remove(uuid);
				MinecraftServer srv = ClaimMap.getServer();
				ServerPlayer online = srv == null ? null : srv.getPlayerList().getPlayer(uuid);
				State st = states.get(uuid);
				if (online == null || st == null || st.phase != Phase.NEED_REGISTER) return;
				accounts.setPasswordHash(uuid.toString(), name, hash);
				finishAuth(online);
			});
		});
	}

	public void handleChangePassword(ServerPlayer player, String oldPassword, String newPassword) {
		UUID uuid = player.getUUID();
		State s = states.get(uuid);
		if (s == null || s.phase != Phase.AUTHED) {
			msg(player, "ต้องเข้าสู่ระบบก่อน");
			return;
		}
		String name = player.getGameProfile().name();
		String problem = passwordProblem(newPassword, newPassword, name);
		if (problem != null) {
			msg(player, problem);
			return;
		}
		if (!pending.add(uuid)) {
			msg(player, "กำลังดำเนินการ กรุณารอสักครู่");
			return;
		}
		worker.submit(() -> {
			boolean ok;
			try {
				ok = accounts.verify(name, oldPassword) != null;
			} catch (RuntimeException e) {
				ok = false;
			}
			if (!ok) {
				MinecraftServer server = ClaimMap.getServer();
				if (server != null) server.execute(() -> {
					pending.remove(uuid);
					MinecraftServer srv = ClaimMap.getServer();
					ServerPlayer online = srv == null ? null : srv.getPlayerList().getPlayer(uuid);
					if (online != null) msg(online, "รหัสเดิมไม่ถูกต้อง");
				});
				return;
			}
			String hash = PasswordHasher.hash(newPassword, config.pbkdf2Iterations);
			MinecraftServer server = ClaimMap.getServer();
			if (server != null) server.execute(() -> {
				pending.remove(uuid);
				accounts.setPasswordHash(uuid.toString(), name, hash);
				links.revokeAllForUuid(uuid.toString());
				MinecraftServer srv = ClaimMap.getServer();
				ServerPlayer online = srv == null ? null : srv.getPlayerList().getPlayer(uuid);
				if (online != null) {
					msg(online, "เปลี่ยนรหัสผ่านแล้ว — เซสชันเว็บเดิมถูกยกเลิกทั้งหมด");
				}
			});
		});
	}

	/** Returns a Thai problem description, or null when the password is fine. */
	static String passwordProblem(String password, String confirm, String name) {
		if (password == null || password.length() < 6 || password.length() > 64) {
			return "รหัสผ่านต้องยาว 6-64 ตัวอักษร";
		}
		if (!password.equals(confirm)) return "รหัสยืนยันไม่ตรงกัน";
		if (name != null && password.equalsIgnoreCase(name)) return "รหัสผ่านต้องไม่ซ้ำกับชื่อผู้เล่น";
		return null;
	}

	private void finishAuth(ServerPlayer player) {
		UUID uuid = player.getUUID();
		markAuthed(uuid);
		State s = states.get(uuid);
		if (s != null) {
			s.fails = 0;
			s.joinedAtMs = 0;
		}
		economy.ensureAccount(uuid);
		msg(player, "เข้าสู่ระบบสำเร็จ ยินดีต้อนรับ!");
		msg(player, "ยอดเงิน: " + economy.balance(uuid) + " " + config.currencyName);
	}

	private void kick(ServerPlayer player, String reason) {
		clear(player.getUUID());
		try {
			player.connection.disconnect(Component.literal(reason));
		} catch (RuntimeException e) {
			ClaimMap.LOGGER.warn("[claimmap] Could not disconnect {}", player.getGameProfile().name(), e);
		}
	}

	private void msg(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text));
	}
}
