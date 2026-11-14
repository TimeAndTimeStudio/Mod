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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/**
 * In-game commands:
 * <ul>
 *   <li>{@code /pass <password>}      - set the password used to log in on the web page</li>
 *   <li>{@code /bal}                  - show the player's money</li>
 *   <li>{@code /pay <player> <amount>} - transfer money to another player</li>
 *   <li>{@code /sell hand|all}        - sell to the shop</li>
 *   <li>{@code /buy <item> <qty>}     - buy from the shop (items go to the mailbox)</li>
 *   <li>{@code /mail [claim]}         - list / claim mailbox items</li>
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
	private final AuthGate authGate;
	private final ShopManager shop;
	private final MailboxManager mailbox;
	private final Map<UUID, SellAllPlan> sellAllPlans = new HashMap<>();

	private record SellAllPlan(Map<String, Integer> items, long expiresAt) {
	}

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy, AuthGate authGate, ShopManager shop, MailboxManager mailbox) {
		this.regions = regions;
		this.links = links;
		this.accounts = accounts;
		this.config = config;
		this.economy = economy;
		this.authGate = authGate;
		this.shop = shop;
		this.mailbox = mailbox;
	}

	public void register() {			CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			dispatcher.register(Commands.literal("login").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("password", StringArgumentType.string())
							.executes(ctx -> runLogin(ctx.getSource(), StringArgumentType.getString(ctx, "password")))));

			dispatcher.register(Commands.literal("register").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("password", StringArgumentType.string())
							.then(Commands.argument("confirm", StringArgumentType.string())
									.executes(ctx -> runRegister(ctx.getSource(),
											StringArgumentType.getString(ctx, "password"),
											StringArgumentType.getString(ctx, "confirm"))))));

			dispatcher.register(Commands.literal("pass").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("old", StringArgumentType.string())
							.then(Commands.argument("new", StringArgumentType.string())
									.executes(ctx -> runPass(ctx.getSource(),
											StringArgumentType.getString(ctx, "old"),
											StringArgumentType.getString(ctx, "new"))))));

			dispatcher.register(Commands.literal("bal").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runBal(ctx.getSource())));

			dispatcher.register(Commands.literal("pay").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("player", StringArgumentType.word())
							.then(Commands.argument("amount", StringArgumentType.word())
									.executes(ctx -> runPay(ctx.getSource(),
											StringArgumentType.getString(ctx, "player"),
											StringArgumentType.getString(ctx, "amount"))))));

			dispatcher.register(Commands.literal("sell").requires(src -> src.getPlayer() != null)
					.then(Commands.literal("hand").executes(ctx -> runSellHand(ctx.getSource())))
					.then(Commands.literal("all")
							.executes(ctx -> runSellAllSummary(ctx.getSource()))
							.then(Commands.literal("confirm")
									.executes(ctx -> runSellAllConfirm(ctx.getSource())))));

			dispatcher.register(Commands.literal("buy").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("item", StringArgumentType.word())
							.then(Commands.argument("qty", StringArgumentType.word())
									.executes(ctx -> runBuy(ctx.getSource(),
											StringArgumentType.getString(ctx, "item"),
											StringArgumentType.getString(ctx, "qty"))))));

			dispatcher.register(Commands.literal("mail").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runMailList(ctx.getSource()))
					.then(Commands.literal("claim").executes(ctx -> runMailClaim(ctx.getSource()))));

			dispatcher.register(Commands.literal("claim").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runClaim(ctx.getSource())));

			dispatcher.register(Commands.literal("claimmap")
					.then(Commands.literal("here").requires(src -> src.getPlayer() != null)
							.executes(ctx -> runHere(ctx.getSource())))
					.then(Commands.literal("reload").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
							.executes(ctx -> runReload(ctx.getSource()))));
		});
	}

	private int runLogin(CommandSourceStack src, String password) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		authGate.handleLogin(player, password);
		return 1;
	}

	private int runRegister(CommandSourceStack src, String password, String confirm) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		authGate.handleRegister(player, password, confirm);
		return 1;
	}

	private int runPass(CommandSourceStack src, String oldPassword, String newPassword) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		authGate.handleChangePassword(player, oldPassword, newPassword);
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

	private int runSellHand(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		ItemStack hand = player.getMainHandItem();
		if (hand == null || hand.isEmpty()) {
			player.sendSystemMessage(Component.literal("มือหลักว่าง เลือกของที่จะขายก่อน"));
			return 0;
		}
		String itemId = ItemCodecs.idOf(hand);
		int qty = hand.getCount();
		ShopManager.SellOutcome o = shop.sell(player, itemId, qty, "cmd_hand");
		if (o.result() == ShopManager.Result.OK) {
			player.sendSystemMessage(Component.literal("ขาย " + qty + " ชิ้น ได้ " + o.earned() + " "
					+ config.currencyName + " (คงเหลือ " + o.balance() + ")"));
		} else {
			player.sendSystemMessage(Component.literal("ขายไม่ได้: " + sellErrorText(o.result())));
		}
		return 1;
	}

	private int runSellAllSummary(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		Map<String, Integer> summary = shop.sellableSummary(player);
		if (summary.isEmpty()) {
			player.sendSystemMessage(Component.literal("ไม่มีของที่ขายได้ (ของมีเสน่ห์/ชื่อ/ความเสียหายขายไม่ได้)"));
			return 0;
		}
		long total = 0;
		int stacks = 0;
		StringBuilder sb = new StringBuilder("สรุปการขายทั้งหมด:\n");
		for (Map.Entry<String, Integer> e : summary.entrySet()) {
			var price = pricesSell(e.getKey());
			if (price == 0) continue;
			long value = price * e.getValue();
			total += value;
			stacks += e.getValue();
			sb.append("- ").append(ItemRegistryAdapter.label(e.getKey())).append(" x").append(e.getValue())
					.append(" = ").append(value).append('\n');
		}
		if (stacks == 0) {
			player.sendSystemMessage(Component.literal("ไม่มีของที่ขายได้"));
			return 0;
		}
		sellAllPlans.put(player.getUUID(), new SellAllPlan(Map.copyOf(summary), System.currentTimeMillis() + 30_000L));
		sb.append("รวม ").append(stacks).append(" ชิ้น ได้ ").append(total).append(' ').append(config.currencyName)
				.append("\nพิมพ์ /sell all confirm ภายใน 30 วินาที");
		player.sendSystemMessage(Component.literal(sb.toString()));
		return 1;
	}

	private int runSellAllConfirm(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		SellAllPlan plan = sellAllPlans.get(player.getUUID());
		if (plan == null || System.currentTimeMillis() > plan.expiresAt()) {
			sellAllPlans.remove(player.getUUID());
			player.sendSystemMessage(Component.literal("ไม่มีรายการรอยืนยัน กรุณาพิมพ์ /sell all ใหม่"));
			return 0;
		}
		sellAllPlans.remove(player.getUUID());
		Map<String, Integer> now = shop.sellableSummary(player);
		if (!now.equals(plan.items())) {
			player.sendSystemMessage(Component.literal("ของในกระเป๋าเปลี่ยนไป กรุณาพิมพ์ /sell all ใหม่"));
			return 0;
		}
		int sold = 0;
		long earned = 0;
		for (Map.Entry<String, Integer> e : plan.items().entrySet()) {
			ShopManager.SellOutcome o = shop.sell(player, e.getKey(), e.getValue(), "cmd_all");
			if (o.result() != ShopManager.Result.OK) {
				player.sendSystemMessage(Component.literal("ขาย " + ItemRegistryAdapter.label(e.getKey())
						+ " ไม่สำเร็จ: " + sellErrorText(o.result()) + " (ที่เหลือขายไปแล้ว " + sold + " ชิ้น)"));
				return 1;
			}
			sold += o.sold();
			earned += o.earned();
		}
		player.sendSystemMessage(Component.literal("ขายแล้ว " + sold + " ชิ้น ได้ " + earned + " "
				+ config.currencyName + " (คงเหลือ " + economy.balance(player.getUUID()) + ")"));
		return 1;
	}

	private long pricesSell(String itemId) {
		return shop.sellUnitPrice(itemId);
	}

	private int runBuy(CommandSourceStack src, String item, String qtyText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		int qty;
		try {
			qty = Integer.parseInt(qtyText);
		} catch (NumberFormatException e) {
			player.sendSystemMessage(Component.literal("จำนวนต้องเป็นจำนวนเต็ม"));
			return 0;
		}
		var server = ClaimMap.getServer();
		if (server == null) return 0;
		UUID uuid = player.getUUID();
		String ref = "cmd" + Long.toString(System.currentTimeMillis(), 36);
		ShopManager.BuyPlan plan = shop.prepareBuy(uuid, item, qty, ref, server.registryAccess());
		if (plan.result() != ShopManager.Result.OK) {
			player.sendSystemMessage(Component.literal("ซื้อไม่ได้: " + sellErrorText(plan.result())));
			return 0;
		}
		ShopManager.BuyOutcome o = shop.commitBuy(uuid, plan);
		if (o.result() != ShopManager.Result.OK) {
			player.sendSystemMessage(Component.literal("ซื้อไม่ได้: " + sellErrorText(o.result())));
			return 0;
		}
		player.sendSystemMessage(Component.literal("ซื้อ " + o.bought() + " ชิ้น ใช้ " + o.spent() + " "
				+ config.currencyName + " (คงเหลือ " + o.balance() + ")"));
		shop.claim(player);
		int left = mailbox.count(uuid);
		player.sendSystemMessage(Component.literal(left > 0
				? "ของบางส่วนอยู่ในกล่องของ พิมพ์ /mail claim (คงเหลือ " + left + " รายการ)"
				: "ของเข้ากระเป๋าแล้ว"));
		return 1;
	}

	private int runMailList(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		List<MailboxManager.MailEntry> entries = mailbox.list(player.getUUID());
		if (entries.isEmpty()) {
			player.sendSystemMessage(Component.literal("กล่องของว่าง"));
			return 0;
		}
		player.sendSystemMessage(Component.literal("กล่องของ: " + entries.size() + " รายการ"));
		int shown = 0;
		for (MailboxManager.MailEntry e : entries) {
			if (shown++ >= 10) {
				player.sendSystemMessage(Component.literal("... และอีก " + (entries.size() - 10) + " รายการ"));
				break;
			}
			player.sendSystemMessage(Component.literal("- " + e.label() + " x" + e.count()));
		}
		player.sendSystemMessage(Component.literal("พิมพ์ /mail claim เพื่อรับของ"));
		return 1;
	}

	private int runMailClaim(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		ShopManager.ClaimOutcome o = shop.claim(player);
		player.sendSystemMessage(Component.literal("รับของ " + o.claimed() + " ชิ้น (คงเหลือในกล่อง "
				+ o.remaining() + " รายการ" + (o.skipped() > 0 ? ", รับไม่ได้ " + o.skipped() + " รายการ" : "") + ")"));
		return 1;
	}

	private static String sellErrorText(ShopManager.Result r) {
		return switch (r) {
			case INVALID_ITEM -> "ไอเท็มไม่ถูกต้องหรือไม่มีในเกมนี้";
			case INVALID_QTY -> "จำนวนไม่ถูกต้อง";
			case NO_PRICE -> "ไอเท็มนี้ยังไม่มีราคา";
			case BLOCKED -> "ไอเท็มนี้ถูกห้ามซื้อขาย";
			case INSUFFICIENT -> "ยอดเงินไม่พอ";
			case NOT_ENOUGH_ITEMS -> "ของในกระเป๋าไม่พอ (ของมีเสน่ห์/ชื่อ/ความเสียหายขายไม่ได้)";
			case LIMIT_REACHED -> "เกินจำนวนที่อนุญาต";
			case MAILBOX_FULL -> "กล่องของเต็ม";
			case FROZEN -> "บัญชีถูกระงับการค้า";
			case OVERFLOW -> "ยอดเงินเกินขอบเขต";
			case DISABLED -> "ตลาดถูกปิดชั่วคราว";
			case OFFLINE -> "ต้องเข้าเกมก่อน";
			default -> "เกิดข้อผิดพลาดภายใน";
		};
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
