package studio.timeandtime.claimmap;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
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
import studio.timeandtime.claimmap.mixin.PlayerProfileAccessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Password gate for players entering the game.
 *
 * <p>Plan A (additional requirements section B): a vanilla Minecraft dialog is
 * shown during the <em>configuration</em> phase, before the player is placed in
 * the world. The connection is held in configuration until the password is
 * correct; no client-side mod is needed. The mod never asks for the password in
 * chat and has no post-join login path.
 *
 * <p>PBKDF2 is deliberately slow, so verification and hashing run on a small
 * worker thread and the result is applied back on the server thread.
 */
public final class AuthGate {
	public enum Phase { NEED_LOGIN, NEED_REGISTER, AUTHED }

	static final int MAX_FAILS = 3;
	static final long TIMEOUT_MS = 60_000L;

	private static final Identifier LOGIN_ACTION = Identifier.fromNamespaceAndPath(ClaimMap.MOD_ID, "auth/login");
	private static final Identifier REGISTER_ACTION = Identifier.fromNamespaceAndPath(ClaimMap.MOD_ID, "auth/register");
	private static final Identifier QUIT_ACTION = Identifier.fromNamespaceAndPath(ClaimMap.MOD_ID, "auth/quit");

	/** Play-phase state, used by command/item hooks. */
	static final class State {
		Phase phase;
		int fails;
		long joinedAtMs;
	}

	/** Configuration-phase state, one per connecting player. */
	private static final class ConfigState {
		final ServerConfigurationPacketListenerImpl listener;
		final String name;
		final UUID uuid;
		Phase phase;
		int fails;
		final long startedAtMs;
		boolean dialogShown;
		boolean pending;

		ConfigState(ServerConfigurationPacketListenerImpl listener, String name, UUID uuid, Phase phase, long startedAtMs) {
			this.listener = listener;
			this.name = name;
			this.uuid = uuid;
			this.phase = phase;
			this.startedAtMs = startedAtMs;
		}
	}

	private final ClaimMapConfig config;
	private final AccountManager accounts;
	private final LinkManager links;
	private final EconomyManager economy;
	private final Map<UUID, State> states = new ConcurrentHashMap<>();
	private final Map<UUID, ConfigState> configStates = new ConcurrentHashMap<>();
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
	}

	public boolean allowed(ServerPlayer player) {
		return config.isAllowed(player.getGameProfile().name());
	}

	// ---- hooks --------------------------------------------------------------

	public void registerHooks() {
		// CONFIGURE runs on the netty event loop while Fabric is still setting
		// up the configuration pipeline, and sending a dialog there fails with
		// "Pipeline has no outbound protocol configured". Only prepare the
		// state here; the dialog is sent from handleConfigurationFinished,
		// which runs on the server thread after the pipeline is ready.
		ServerConfigurationConnectionEvents.CONFIGURE.register((listener, server) -> beginConfiguration(listener));
		ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> clearConfiguration(listener));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.getPlayer().getUUID()));
		ServerPlayerEvents.LEAVE.register(player -> clear(player.getUUID()));

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

	private static GameProfile profileOf(ServerConfigurationPacketListenerImpl listener) {
		try {
			return ((PlayerProfileAccessor) listener).claimmap$profile();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void beginConfiguration(ServerConfigurationPacketListenerImpl listener) {
		GameProfile profile = profileOf(listener);
		if (profile == null || profile.id() == null) {
			listener.disconnect(Component.literal("ไม่สามารถอ่านข้อมูลผู้เล่นได้"));
			return;
		}
		// A play -> configuration -> play transition (data pack reload) must not
		// ask for the password again.
		if (ServerConfigurationNetworking.isReconfiguring(listener)) {
			configStates.put(profile.id(), new ConfigState(listener, profile.name(), profile.id(), Phase.AUTHED,
					System.currentTimeMillis()));
			return;
		}
		if (!config.isAllowed(profile.name())) {
			listener.disconnect(Component.literal("ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่น"));
			return;
		}
		// Fill a missing uuid in an old accounts.json before looking the account up.
		accounts.bindUuid(profile.name(), profile.id());
		boolean hasPassword = accounts.hasPassword(profile.id().toString());
		ConfigState state = new ConfigState(listener, profile.name(), profile.id(),
				hasPassword ? Phase.NEED_LOGIN : Phase.NEED_REGISTER, System.currentTimeMillis());
		configStates.put(profile.id(), state);
	}

	private void clearConfiguration(ServerConfigurationPacketListenerImpl listener) {
		GameProfile profile = profileOf(listener);
		if (profile != null && profile.id() != null) {
			configStates.remove(profile.id());
		}
	}

	private void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (ConfigState state : configStates.values()) {
			if (state.phase == Phase.AUTHED) continue;
			if (now - state.startedAtMs >= TIMEOUT_MS) {
				disconnect(state, "หมดเวลากรอกรหัส (60 วินาที) กรุณาเข้าใหม่");
			}
		}
	}

	private void onJoin(ServerPlayer player) {
		UUID uuid = player.getUUID();
		String name = player.getGameProfile().name();
		if (!config.isAllowed(name)) {
			player.connection.disconnect(Component.literal("ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่น"));
			return;
		}
		ConfigState configState = configStates.get(uuid);
		if (configState == null || configState.phase != Phase.AUTHED) {
			player.connection.disconnect(Component.literal("ต้องเข้าสู่ระบบก่อนเข้าเกม"));
			return;
		}
		configStates.remove(uuid);
		State state = new State();
		state.phase = Phase.AUTHED;
		states.put(uuid, state);
		economy.ensureAccount(uuid);
		player.sendSystemMessage(Component.literal("เข้าสู่ระบบสำเร็จ ยินดีต้อนรับ!"));
		player.sendSystemMessage(Component.literal("ยอดเงิน: " + economy.balance(uuid) + " " + config.currencyName));

		if (config.resourcePackAutoPush && config.resourcePackUrl != null && !config.resourcePackUrl.isBlank()) {
			try {
				UUID packId = UUID.fromString("6d7ddf93-6004-4224-9d7a-2cbc7efd86a1");
				Component prompt = (config.resourcePackPrompt != null && !config.resourcePackPrompt.isBlank())
						? Component.literal(config.resourcePackPrompt)
						: null;
				net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket rpPacket =
						new net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket(
								packId,
								config.resourcePackUrl,
								config.resourcePackHash != null ? config.resourcePackHash : "",
								false,
								Optional.ofNullable(prompt)
						);
				player.connection.send(rpPacket);
			} catch (Throwable t) {
				ClaimMap.LOGGER.debug("[timesmp] Could not send resource pack push packet: {}", t.getMessage());
			}
		}
	}

	// ---- dialog -------------------------------------------------------------

	private void sendDialog(ConfigState state, String error) {
		if (state.phase == Phase.AUTHED) return;
		Dialog dialog = state.phase == Phase.NEED_LOGIN ? loginDialog(error) : registerDialog(error);
		state.dialogShown = true;
		state.listener.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
	}

	static Dialog loginDialog(String error) {
		Input password = new Input("password", new TextInput(200,
				Component.literal("รหัสผ่าน"), true, "", 64, Optional.empty()));
		ActionButton enter = button("เข้าสู่เกม", LOGIN_ACTION);
		return dialog("เข้าสู่ระบบ Time SMP", error, List.of(password), enter);
	}

	static Dialog registerDialog(String error) {
		Input password = new Input("password", new TextInput(200,
				Component.literal("รหัสผ่านใหม่"), true, "", 64, Optional.empty()));
		Input confirm = new Input("confirm", new TextInput(200,
				Component.literal("ยืนยันรหัสผ่าน"), true, "", 64, Optional.empty()));
		ActionButton enter = button("ตั้งรหัสและเข้าเกม", REGISTER_ACTION);
		return dialog("ตั้งรหัสผ่านครั้งแรก", error, List.of(password, confirm), enter);
	}

	private static Dialog dialog(String title, String error, List<Input> inputs, ActionButton enter) {
		List<DialogBody> body = error == null
				? List.<DialogBody>of(new PlainMessage(Component.literal(
						"รหัสผ่านใช้ร่วมกับเว็บ Time SMP"), 260))
				: List.<DialogBody>of(new PlainMessage(Component.literal(error), 260));
		CommonDialogData common = new CommonDialogData(
				Component.literal(title),
				Optional.empty(),
				false,                 // Esc must not close the gate
				true,
				DialogAction.CLOSE,
				body,
				inputs);
		ActionButton quit = button("ออกจากเกม", QUIT_ACTION);
		return new MultiActionDialog(common, List.of(enter), Optional.of(quit), 1);
	}

	private static ActionButton button(String label, Identifier action) {
		return new ActionButton(new CommonButtonData(Component.literal(label), 180),
				Optional.of(new CustomAll(action, Optional.empty())));
	}

	// ---- packets from the configuration phase ------------------------------

	/**
	 * Called by the ServerCommonPacketListenerImpl mixin before the server sends
	 * {@link ClientboundFinishConfigurationPacket}. That packet is terminal: the
	 * configuration encoder is removed from the pipeline right after it is sent,
	 * so anything sent after it fails. Holding it until authentication keeps the
	 * client in configuration and lets the dialog use the working encoder.
	 *
	 * @return true when the packet may be sent.
	 */
	public static boolean allowFinishConfigurationPacket(ServerCommonPacketListenerImpl listener) {
		if (!(listener instanceof ServerConfigurationPacketListenerImpl configListener)) return true;
		ClaimMap mod = ClaimMap.instance();
		AuthGate gate = mod == null ? null : mod.authGate();
		return gate == null || gate.onFinishConfigurationPacket(configListener);
	}

	private boolean onFinishConfigurationPacket(ServerConfigurationPacketListenerImpl listener) {
		GameProfile profile = profileOf(listener);
		if (profile == null || profile.id() == null) return false;
		ConfigState state = configStates.get(profile.id());
		if (state == null || state.listener != listener) return false;
		if (state.phase == Phase.AUTHED) return true;
		if (!state.dialogShown) sendDialog(state, null);
		return false;
	}

	/** Called by the ServerConfigurationPacketListenerImpl mixin. False = cancel. */
	public static boolean allowConfigurationFinished(ServerConfigurationPacketListenerImpl listener) {
		ClaimMap mod = ClaimMap.instance();
		AuthGate gate = mod == null ? null : mod.authGate();
		return gate == null || gate.onConfigurationFinished(listener);
	}

	private boolean onConfigurationFinished(ServerConfigurationPacketListenerImpl listener) {
		GameProfile profile = profileOf(listener);
		if (profile == null || profile.id() == null) return false;
		ConfigState state = configStates.get(profile.id());
		return state != null && state.phase == Phase.AUTHED;
	}

	/** Called by the ServerCommonPacketListenerImpl mixin (server thread). */
	public static void configurationClick(ServerConfigurationPacketListenerImpl listener, Identifier id,
			Optional<Tag> payload) {
		ClaimMap mod = ClaimMap.instance();
		AuthGate gate = mod == null ? null : mod.authGate();
		if (gate != null) gate.onConfigurationClick(listener, id, payload);
	}

	private void onConfigurationClick(ServerConfigurationPacketListenerImpl listener, Identifier id,
			Optional<Tag> payload) {
		GameProfile profile = profileOf(listener);
		if (profile == null || profile.id() == null) {
			listener.disconnect(Component.literal("ไม่สามารถอ่านข้อมูลผู้เล่นได้"));
			return;
		}
		ConfigState state = configStates.get(profile.id());
		if (state == null || state.listener != listener) {
			listener.disconnect(Component.literal("กรุณาเชื่อมต่อใหม่"));
			return;
		}
		if (QUIT_ACTION.equals(id)) {
			disconnect(state, "ออกจากเกมตามคำขอ");
			return;
		}
		if (state.phase == Phase.AUTHED || state.pending) return;
		if (state.phase == Phase.NEED_LOGIN && !LOGIN_ACTION.equals(id)) {
			sendDialog(state, "บัญชีนี้มีรหัสแล้ว กรุณาใส่รหัสผ่าน");
			return;
		}
		if (state.phase == Phase.NEED_REGISTER && !REGISTER_ACTION.equals(id)) {
			sendDialog(state, "ยังไม่มีรหัสผ่าน กรุณาตั้งรหัสก่อนเข้าเกม");
			return;
		}

		Optional<CompoundTag> tag = payload.filter(t -> t instanceof CompoundTag).map(t -> (CompoundTag) t);
		if (tag.isEmpty()) {
			failed(state, "ข้อมูลจากหน้าต่างไม่ถูกต้อง");
			return;
		}
		String password = string(tag.get(), "password");
		if (password == null) {
			failed(state, "กรุณากรอกรหัสผ่าน");
			return;
		}

		if (state.phase == Phase.NEED_REGISTER) {
			String confirm = string(tag.get(), "confirm");
			String problem = passwordProblem(password, confirm, state.name);
			if (problem != null) {
				failed(state, problem);
				return;
			}
			state.pending = true;
			worker.submit(() -> {
				String hash = PasswordHasher.hash(password, config.pbkdf2Iterations);
				MinecraftServer server = ClaimMap.getServer();
				if (server != null) server.execute(() -> completeRegister(state, hash, password, confirm));
			});
			return;
		}

		state.pending = true;
		worker.submit(() -> {
			boolean ok;
			try {
				ok = accounts.verify(state.name, password) != null;
			} catch (RuntimeException e) {
				ok = false;
			}
			final boolean success = ok;
			MinecraftServer server = ClaimMap.getServer();
			if (server != null) server.execute(() -> completeLogin(state, success));
		});
	}

	static String string(CompoundTag tag, String key) {
		Tag value = tag.get(key);
		return value instanceof StringTag s ? s.value() : null;
	}

	private void completeLogin(ConfigState state, boolean success) {
		ConfigState current = configStates.get(state.uuid);
		if (current != state || state.phase == Phase.AUTHED) return;
		state.pending = false;
		if (!success) {
			failed(state, null);
			return;
		}
		authenticated(state);
	}

	private void completeRegister(ConfigState state, String hash, String password, String confirm) {
		ConfigState current = configStates.get(state.uuid);
		if (current != state || state.phase != Phase.NEED_REGISTER) return;
		state.pending = false;
		String problem = passwordProblem(password, confirm, state.name);
		if (problem != null || !accounts.setPasswordHash(state.uuid.toString(), state.name, hash)) {
			failed(state, "บันทึกรหัสผ่านไม่สำเร็จ กรุณาลองใหม่");
			return;
		}
		authenticated(state);
	}

	private void authenticated(ConfigState state) {
		state.phase = Phase.AUTHED;
		state.fails = 0;
		economy.ensureAccount(state.uuid);
		state.listener.send(ClientboundClearDialogPacket.INSTANCE);
		// The gate in allowFinishConfigurationPacket now lets this through, which
		// moves the client to PLAY; its finish acknowledgement spawns the player.
		state.listener.send(ClientboundFinishConfigurationPacket.INSTANCE);
	}

	private void failed(ConfigState state, String message) {
		state.fails++;
		if (state.fails >= MAX_FAILS) {
			disconnect(state, "ใส่รหัสผิด " + MAX_FAILS + " ครั้ง กรุณาเข้าใหม่");
			return;
		}
		String text = message == null ? "รหัสผ่านไม่ถูกต้อง" : message;
		sendDialog(state, text + " (เหลือ " + (MAX_FAILS - state.fails) + " ครั้ง)");
	}

	private void disconnect(ConfigState state, String reason) {
		configStates.remove(state.uuid);
		try {
			state.listener.disconnect(Component.literal(reason));
		} catch (RuntimeException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not disconnect {} during configuration", state.name, e);
		}
	}

	// ---- action blocking (defence in depth) ---------------------------------

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

	/** Returns a Thai problem description, or null when the password is fine. */
	static String passwordProblem(String password, String confirm, String name) {
		if (password == null || password.length() < 6 || password.length() > 64) {
			return "รหัสผ่านต้องยาว 6-64 ตัวอักษร";
		}
		if (!password.equals(confirm)) return "รหัสยืนยันไม่ตรงกัน";
		if (name != null && password.equalsIgnoreCase(name)) return "รหัสผ่านต้องไม่ซ้ำกับชื่อผู้เล่น";
		return null;
	}
}
