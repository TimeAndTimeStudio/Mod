package studio.timeandtime.claimmap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Optional;
import java.util.UUID;

/**
 * In-game commands:
 * <ul>
 *   <li>{@code /pass <password>}      - set the password used to log in on the web page</li>
 *   <li>{@code /bal}                  - show the player's money</li>
 *   <li>{@code /pay <player> <amount>} - transfer money to another player</li>
 *   <li>{@code /claim}                - claim the cell the player is standing in</li>
 *   <li>{@code /claimmap here}        - show info about the current cell</li>
 *   <li>{@code /claimmap reload}      - reload config + regions (operator)</li>
 * </ul>
 */
public final class ClaimCommands {
	private final RegionManager regions;
	private final LinkManager links;
	private final AccountManager accounts;
	private final ClaimMapConfig config;
	private final EconomyManager economy;

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy) {
		this.regions = regions;
		this.links = links;
		this.accounts = accounts;
		this.config = config;
		this.economy = economy;
	}

	public void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			dispatcher.register(Commands.literal("pass").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("password", StringArgumentType.string())
							.executes(ctx -> runPass(ctx.getSource(), StringArgumentType.getString(ctx, "password")))));

			dispatcher.register(Commands.literal("bal").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runBal(ctx.getSource())));

			dispatcher.register(Commands.literal("pay").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("player", StringArgumentType.word())
							.then(Commands.argument("amount", StringArgumentType.word())
									.executes(ctx -> runPay(ctx.getSource(),
											StringArgumentType.getString(ctx, "player"),
											StringArgumentType.getString(ctx, "amount"))))));

			dispatcher.register(Commands.literal("claim").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runClaim(ctx.getSource())));

			dispatcher.register(Commands.literal("claimmap")
					.then(Commands.literal("here").requires(src -> src.getPlayer() != null)
							.executes(ctx -> runHere(ctx.getSource())))
					.then(Commands.literal("reload").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
							.executes(ctx -> runReload(ctx.getSource()))));
		});
	}

	private int runPass(CommandSourceStack src, String password) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		String uuid = player.getUUID().toString();
		String name = player.getGameProfile().name();
		// Whitelist check first so a name outside `players` can never set a password.
		// (The /pass takeover hole itself is closed in phase 3 with AuthGate.)
		if (!config.isAllowed(name)) {
			player.sendSystemMessage(Component.literal("ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่น"));
			return 0;
		}
		if (!accounts.setPassword(uuid, name, password)) {
			player.sendSystemMessage(Component.literal("Invalid password: use 4-64 characters and not include spaces."));
			return 1;
		}
		player.sendSystemMessage(Component.literal("Password set for " + name
				+ ". Log in on the claim map web page with this name + password."));
		return 1;
	}

	private int runBal(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (economy.isDisabled()) {
			player.sendSystemMessage(Component.literal("ระบบเงินถูกปิดชั่วคราว กรุณาแจ้งแอดมิน"));
			return 0;
		}
		economy.ensureAccount(player.getUUID());
		player.sendSystemMessage(Component.literal("ยอดเงิน: " + economy.balance(player.getUUID())
				+ " " + config.currencyName));
		return 1;
	}

	private int runPay(CommandSourceStack src, String targetName, String amountText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (economy.isDisabled()) {
			player.sendSystemMessage(Component.literal("ระบบเงินถูกปิดชั่วคราว กรุณาแจ้งแอดมิน"));
			return 0;
		}
		long amount;
		try {
			amount = Long.parseLong(amountText);
		} catch (NumberFormatException e) {
			player.sendSystemMessage(Component.literal("จำนวนเงินต้องเป็นจำนวนเต็ม"));
			return 0;
		}
		if (amount < 1 || amount > EconomyManager.MAX_MONEY) {
			player.sendSystemMessage(Component.literal("จำนวนเงินไม่ถูกต้อง"));
			return 0;
		}
		if (targetName.equalsIgnoreCase(player.getGameProfile().name())) {
			player.sendSystemMessage(Component.literal("โอนให้ตัวเองไม่ได้"));
			return 0;
		}
		Optional<UUID> target = accounts.uuidOf(targetName);
		if (target.isEmpty()) {
			player.sendSystemMessage(Component.literal("ไม่พบผู้เล่นชื่อนี้ (ต้องเข้าเกมและตั้งรหัสก่อน)"));
			return 0;
		}
		if (!config.isAllowed(targetName)) {
			player.sendSystemMessage(Component.literal("ชื่อนี้ไม่อยู่ในรายชื่อผู้เล่น"));
			return 0;
		}
		economy.ensureAccount(player.getUUID());
		TxResult r = economy.transfer(player.getUUID(), target.get(), amount, "pay", "");
		switch (r) {
			case OK -> player.sendSystemMessage(Component.literal("โอน " + amount + " " + config.currencyName
					+ " ให้ " + targetName + " แล้ว (คงเหลือ " + economy.balance(player.getUUID()) + ")"));
			case INSUFFICIENT -> player.sendSystemMessage(Component.literal("ยอดเงินไม่พอ"));
			case FROZEN -> player.sendSystemMessage(Component.literal("บัญชีนี้ถูกระงับการโอน"));
			case NO_ACCOUNT -> player.sendSystemMessage(Component.literal("ผู้รับยังไม่เคยเข้าเกม"));
			case SAME_ACCOUNT -> player.sendSystemMessage(Component.literal("โอนให้ตัวเองไม่ได้"));
			case INVALID_AMOUNT -> player.sendSystemMessage(Component.literal("จำนวนเงินไม่ถูกต้อง"));
			default -> player.sendSystemMessage(Component.literal("โอนเงินไม่สำเร็จ กรุณาลองใหม่"));
		}
		return 1;
	}

	private int runClaim(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		int bx = player.getBlockX();
		int bz = player.getBlockZ();
		int cx = Math.floorDiv(bx, config.cellSize);
		int cz = Math.floorDiv(bz, config.cellSize);
		String uuid = player.getUUID().toString();
		String name = player.getGameProfile().name();
		RegionManager.ClaimResult r = regions.claim(cx, cz, uuid, name);
		switch (r) {
			case OK -> player.sendSystemMessage(Component.literal("Claimed cell " + cx + "," + cz));
			case ALREADY_CLAIMED -> player.sendSystemMessage(Component.literal("Cell " + cx + "," + cz + " is already claimed by " + regions.ownerName(cx + "," + cz)));
			case COMMON_AREA -> player.sendSystemMessage(Component.literal("This is the protected spawn area, you cannot claim it."));
			case LIMIT_REACHED -> player.sendSystemMessage(Component.literal("You reached the cell claim limit (" + config.maxCellsPerPlayer + ")."));
		}
		return 1;
	}

	private int runHere(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		String id = regions.cellIdAt(player.getBlockX(), player.getBlockZ());
		RegionManager.CellData c = regions.get(id);
		String owner = c == null ? "free" : c.ownerName;
		src.sendSuccess(() -> Component.literal("Cell " + id + " -> " + owner + " (claimed " + regions.countClaimedBy(player.getUUID().toString())
				+ "/" + config.maxCellsPerPlayer + " by you)"), false);
		return 1;
	}

	private int runReload(CommandSourceStack src) {
		config.load();
		regions.load();
		src.sendSuccess(() -> Component.literal("Reloaded claimmap config + regions."), false);
		return 1;
	}
}
