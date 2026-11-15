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
 *   <li>{@code /ah}                   - auction house list</li>
 *   <li>{@code /ah bid <id> <amount>} - place a bid</li>
 *   <li>{@code /ah cancel <id>}       - cancel a listing without bids</li>
 *   <li>{@code /eco give|take|set <player> <amount> [reason]} - admin money (OP only)</li>
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
	private final AuctionManager auctions;
	private final PriceService prices;
	private final AdminService admin;
	private final TeleportManager teleports;
	private final Map<UUID, SellAllPlan> sellAllPlans = new HashMap<>();

	private record SellAllPlan(Map<String, Integer> items, long expiresAt) {
	}

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy, AuthGate authGate, ShopManager shop, MailboxManager mailbox,
			AuctionManager auctions, PriceService prices, AdminService admin) {
		this(regions, links, accounts, config, economy, authGate, shop, mailbox, auctions, prices, admin,
				new TeleportManager());
	}

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy, AuthGate authGate, ShopManager shop, MailboxManager mailbox,
			AuctionManager auctions, PriceService prices, AdminService admin, TeleportManager teleports) {
		this.regions = regions;
		this.links = links;
		this.accounts = accounts;
		this.config = config;
		this.economy = economy;
		this.authGate = authGate;
		this.shop = shop;
		this.mailbox = mailbox;
		this.auctions = auctions;
		this.prices = prices;
		this.admin = admin;
		this.teleports = teleports != null ? teleports : new TeleportManager();
	}

	public void register() {			CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
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

			dispatcher.register(Commands.literal("ah").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runAhList(ctx.getSource()))
					.then(Commands.literal("bid")
							.then(Commands.argument("id", StringArgumentType.word())
									.then(Commands.argument("amount", StringArgumentType.word())
											.executes(ctx -> runAhBid(ctx.getSource(),
													StringArgumentType.getString(ctx, "id"),
													StringArgumentType.getString(ctx, "amount"))))))
					.then(Commands.literal("cancel")
							.then(Commands.argument("id", StringArgumentType.word())
									.executes(ctx -> runAhCancel(ctx.getSource(),
											StringArgumentType.getString(ctx, "id"))))));

			dispatcher.register(Commands.literal("eco")
					.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
					.then(Commands.literal("give").then(ecoArgs("give")))
					.then(Commands.literal("take").then(ecoArgs("take")))
					.then(Commands.literal("set").then(ecoArgs("set"))));

			dispatcher.register(Commands.literal("claim").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runClaim(ctx.getSource()))
					.then(Commands.literal("transfer")
							.then(Commands.argument("player", StringArgumentType.word())
									.executes(ctx -> runClaimTransferHere(ctx.getSource(),
											StringArgumentType.getString(ctx, "player"))))));

			var timesmpNode = dispatcher.register(Commands.literal("timesmp")
					.then(Commands.literal("here").requires(src -> src.getPlayer() != null)
							.executes(ctx -> runHere(ctx.getSource())))
					.then(Commands.literal("reload").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
							.executes(ctx -> runReload(ctx.getSource())))
					.then(Commands.literal("transfer").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
							.then(Commands.argument("cx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
									.then(Commands.argument("cz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
											.then(Commands.argument("player", StringArgumentType.word())
													.executes(ctx -> runAdminTransfer(ctx.getSource(),
															com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cx"),
															com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cz"),
															StringArgumentType.getString(ctx, "player"))))))));

			dispatcher.register(Commands.literal("claimmap").redirect(timesmpNode));

			dispatcher.register(Commands.literal("tpa").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runTpaAccept(ctx.getSource(), null))
					.then(Commands.literal("accept")
							.executes(ctx -> runTpaAccept(ctx.getSource(), null))
							.then(Commands.argument("player", StringArgumentType.word())
									.executes(ctx -> runTpaAccept(ctx.getSource(),
											StringArgumentType.getString(ctx, "player")))))
					.then(Commands.literal("deny")
							.executes(ctx -> runTpaDeny(ctx.getSource(), null))
							.then(Commands.argument("player", StringArgumentType.word())
									.executes(ctx -> runTpaDeny(ctx.getSource(),
											StringArgumentType.getString(ctx, "player")))))
					.then(Commands.literal("cancel")
							.executes(ctx -> runTpaCancel(ctx.getSource())))
					.then(Commands.argument("player", StringArgumentType.word())
							.executes(ctx -> runTpaRequestOrAccept(ctx.getSource(),
									StringArgumentType.getString(ctx, "player")))));

			dispatcher.register(Commands.literal("tpaccept").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runTpaAccept(ctx.getSource(), null))
					.then(Commands.argument("player", StringArgumentType.word())
							.executes(ctx -> runTpaAccept(ctx.getSource(),
									StringArgumentType.getString(ctx, "player")))));

			dispatcher.register(Commands.literal("tpdeny").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runTpaDeny(ctx.getSource(), null))
					.then(Commands.argument("player", StringArgumentType.word())
							.executes(ctx -> runTpaDeny(ctx.getSource(),
									StringArgumentType.getString(ctx, "player")))));

			dispatcher.register(Commands.literal("tpacancel").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runTpaCancel(ctx.getSource())));

			dispatcher.register(Commands.literal("tp").requires(src -> src.getPlayer() != null)
					.executes(ctx -> {
						ctx.getSource().sendFailure(Component.literal("§eการใช้งาน: /tp <ชื่อผู้เล่น> (วาร์ปหรือส่งคำขอวาร์ป)"));
						return 0;
					})
					.then(Commands.argument("player", StringArgumentType.word())
							.executes(ctx -> runTp(ctx.getSource(),
									StringArgumentType.getString(ctx, "player")))));
		});
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
		player.sendSystemMessage(Component.literal("§cระบบร้านค้าเปิดรับซื้อไอเท็มจากผู้เล่นเท่านั้น (ไม่มีการเสกขายของจากเซิร์ฟเวอร์) คุณสามารถซื้อของจากผู้เล่นอื่นได้ที่หน้าเว็บตลาด"));
		return 0;
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
		if (o.claimed() == 0 && o.remaining() > 0) {
			player.sendSystemMessage(Component.literal("§cช่องเก็บของในตัวไม่พอ กรุณาเคลียร์ช่องว่างในตัวก่อนรับของ (คงเหลือ " + o.remaining() + " รายการ)"));
			return 1;
		}
		if (o.inventoryFull()) {
			player.sendSystemMessage(Component.literal("§eรับของได้ " + o.claimed() + " ชิ้น แต่ช่องเก็บของเต็มแล้ว! คงเหลือในกล่อง " + o.remaining() + " รายการ"));
			return 1;
		}
		player.sendSystemMessage(Component.literal("รับของ " + o.claimed() + " ชิ้น (คงเหลือในกล่อง "
				+ o.remaining() + " รายการ" + (o.skipped() > 0 ? ", รับไม่ได้ " + o.skipped() + " รายการ" : "") + ")"));
		return 1;
	}

	// ---- auction house ------------------------------------------------------

	private int runAhList(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (auctions.isDisabled()) {
			player.sendSystemMessage(Component.literal("ระบบตลาดถูกปิดชั่วคราว กรุณาแจ้งแอดมิน"));
			return 0;
		}
		List<AuctionManager.Auction> all = auctions.all();
		int active = 0;
		StringBuilder sb = new StringBuilder("ตลาดประมูล:\n");
		for (AuctionManager.Auction a : all) {
			if (!a.isActive()) continue;
			if (active >= 10) { sb.append("... และอีก ").append(active + 1).append(" รายการ\n"); break; }
			active++;
			sb.append("- ").append(a.id).append(' ').append(a.label).append(" x").append(a.count)
					.append(" เริ่ม ").append(a.startPrice);
			if (a.buyout != null) sb.append(" ซื้อทันที ").append(a.buyout);
			if (a.highBid > 0) sb.append(" ล่าสุด ").append(a.highBid);
			long mins = Math.max(0, (a.endsAt - System.currentTimeMillis()) / 60_000L);
			sb.append(" เหลือ ").append(mins).append(" นาที\n");
		}
		if (active == 0) {
			player.sendSystemMessage(Component.literal("ยังไม่มีการประมูล — เซิร์ฟเวอร์เท่านั้นที่เปิดประมูลได้"));
			return 0;
		}
		player.sendSystemMessage(Component.literal(sb.toString()));
		return 1;
	}

	private int runAhSell(CommandSourceStack src, String startText, String buyoutText, String hoursText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (auctions.isDisabled()) {
			player.sendSystemMessage(Component.literal("ระบบตลาดถูกปิดชั่วคราว กรุณาแจ้งแอดมิน"));
			return 0;
		}
		long start;
		long buyoutRaw;
		int hours;
		try {
			start = Long.parseLong(startText);
			buyoutRaw = Long.parseLong(buyoutText);
			hours = Integer.parseInt(hoursText);
		} catch (NumberFormatException e) {
			player.sendSystemMessage(Component.literal("ตัวเลขไม่ถูกต้อง"));
			return 0;
		}
		ItemStack hand = player.getMainHandItem();
		if (hand == null || hand.isEmpty()) {
			player.sendSystemMessage(Component.literal("มือหลักว่าง เลือกของที่จะประมูลก่อน"));
			return 0;
		}
		String itemId = ItemCodecs.idOf(hand);
		if (prices.isBlocked(itemId) || !ItemRegistryAdapter.exists(itemId)) {
			player.sendSystemMessage(Component.literal("ไอเท็มนี้ลงประมูลไม่ได้"));
			return 0;
		}
		var server = ClaimMap.getServer();
		if (server == null) return 0;
		String itemJson = ItemCodecs.encode(hand, server.registryAccess());
		int count = hand.getCount();
		String label = ItemRegistryAdapter.label(itemId);
		Long buyout = buyoutRaw <= 0 ? null : buyoutRaw;

		AuctionManager.CreatePlan plan = new AuctionManager.CreatePlan(player.getUUID().toString(),
				player.getGameProfile().name(), itemJson, itemId, count, label, start, buyout, hours);
		AuctionManager.CreateOutcome check = auctions.planCreate(plan);
		if (check.result() != AuctionManager.Result.OK) {
			player.sendSystemMessage(Component.literal("ลงประมูลไม่ได้: " + auctionErrorText(check.result())));
			return 0;
		}

		// Take the stack only after validation; put it back if the listing fails.
		ItemStack backup = hand.copy();
		hand.setCount(0);
		AuctionManager.CreateOutcome o = auctions.create(plan);
		if (o.result() != AuctionManager.Result.OK) {
			player.getInventory().setItem(player.getInventory().getSelectedSlot(), backup);
			player.sendSystemMessage(Component.literal("ลงประมูลไม่ได้: " + auctionErrorText(o.result())));
			return 0;
		}
		player.sendSystemMessage(Component.literal("ลงประมูลแล้ว " + o.auctionId() + " " + label + " x" + count
				+ " ราคาเริ่ม " + start + " " + config.currencyName
				+ (buyout == null ? "" : " (ซื้อทันที " + buyout + ")")));
		return 1;
	}

	private int runAhBid(CommandSourceStack src, String id, String amountText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		long amount;
		try {
			amount = Long.parseLong(amountText);
		} catch (NumberFormatException e) {
			player.sendSystemMessage(Component.literal("จำนวนเงินต้องเป็นจำนวนเต็ม"));
			return 0;
		}
		AuctionManager.BidOutcome o = auctions.bid(player.getUUID(), player.getGameProfile().name(), id, amount);
		if (o.result() == AuctionManager.Result.OK) {
			player.sendSystemMessage(Component.literal("ประมูล " + id + " ที่ " + amount + " " + config.currencyName
					+ " (คงเหลือ " + economy.balance(player.getUUID()) + ")"));
		} else if (o.result() == AuctionManager.Result.BID_TOO_LOW) {
			player.sendSystemMessage(Component.literal("ต้องประมูลอย่างน้อย " + o.minBid()));
		} else {
			player.sendSystemMessage(Component.literal("ประมูลไม่ได้: " + auctionErrorText(o.result())));
		}
		return 1;
	}

	private int runAhCancel(CommandSourceStack src, String id) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		AuctionManager.Result r = auctions.cancel(player.getUUID(), false, id);
		if (r == AuctionManager.Result.OK) {
			player.sendSystemMessage(Component.literal("ยกเลิกแล้ว ของจะอยู่ในกล่องของ พิมพ์ /mail claim"));
		} else {
			player.sendSystemMessage(Component.literal("ยกเลิกไม่ได้: " + auctionErrorText(r)));
		}
		return 1;
	}

	private static String auctionErrorText(AuctionManager.Result r) {
		return switch (r) {
			case NOT_FOUND -> "ไม่พบรายการนี้";
			case CLOSED -> "รายการนี้ปิดแล้ว";
			case OWN_AUCTION -> "ประมูลรายการของตัวเองไม่ได้";
			case ALREADY_HIGHEST -> "คุณเป็นผู้ประมูลสูงสุดอยู่แล้ว";
			case BID_TOO_LOW -> "ราคาต่ำเกินไป";
			case USE_BUYOUT -> "ราคานี้ถึงขั้นซื้อทันที กรุณาใช้ปุ่มซื้อทันที";
			case LIMIT_REACHED -> "ลงประมูลครบจำนวนสูงสุดแล้ว หรือกล่องของเต็ม";
			case INSUFFICIENT -> "ยอดเงินไม่พอ";
			case FROZEN -> "บัญชีถูกระงับการค้า";
			case DISABLED -> "ระบบตลาดถูกปิดชั่วคราว";
			case HAS_BIDS -> "มีผู้ประมูลแล้ว ยกเลิกไม่ได้";
			case FORBIDDEN -> "ไม่มีสิทธิ์กับรายการนี้";
			case ALREADY_LISTED -> "เขตนี้มีรายการประมูลเปิดอยู่แล้ว";
			case ALREADY_OWNER -> "คุณเป็นเจ้าของเขตนี้อยู่แล้ว";
			case BAD_REQUEST -> "ข้อมูลไม่ถูกต้อง";
			default -> "เกิดข้อผิดพลาดภายใน กรุณาลองใหม่";
		};
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

	private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> ecoArgs(String op) {
		return Commands.argument("player", StringArgumentType.word())
				.then(Commands.argument("amount", StringArgumentType.word())
						.executes(ctx -> runEco(ctx.getSource(), op,
								StringArgumentType.getString(ctx, "player"),
								StringArgumentType.getString(ctx, "amount"), "eco command"))
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> runEco(ctx.getSource(), op,
										StringArgumentType.getString(ctx, "player"),
										StringArgumentType.getString(ctx, "amount"),
										StringArgumentType.getString(ctx, "reason")))));
	}

	private int runEco(CommandSourceStack src, String op, String playerName, String amountText, String reason) {
		ServerPlayer player = src.getPlayer();
		if (player != null && !authGate.isAuthed(player.getUUID())) {
			src.sendFailure(Component.literal("ต้องเข้าสู่ระบบก่อนใช้คำสั่งนี้"));
			return 0;
		}
		long amount;
		try {
			amount = Long.parseLong(amountText);
		} catch (NumberFormatException e) {
			src.sendFailure(Component.literal("จำนวนเงินต้องเป็นจำนวนเต็ม"));
			return 0;
		}
		String actor = player == null ? "CONSOLE" : player.getGameProfile().name();
		AdminService.MoneyOutcome o = admin.money(actor, null, playerName, op, amount, reason);
		switch (o.result()) {
			case OK -> src.sendSuccess(() -> Component.literal("สำเร็จ: " + playerName + " คงเหลือ "
					+ o.balance() + " " + config.currencyName), true);
			case NO_ACCOUNT -> src.sendFailure(Component.literal("ไม่พบบัญชีผู้เล่นนี้"));
			case INSUFFICIENT -> src.sendFailure(Component.literal("ยอดเงินไม่พอ (ห้ามติดลบ)"));
			case DISABLED -> src.sendFailure(Component.literal("ระบบเงินถูกปิดชั่วคราว"));
			case BAD_REQUEST -> src.sendFailure(Component.literal("ข้อมูลไม่ถูกต้อง (เหตุผล 3-200 ตัวอักษร)"));
			default -> src.sendFailure(Component.literal("ทำรายการไม่สำเร็จ"));
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
		String id = cx + "," + cz;
		String uuid = player.getUUID().toString();
		if (regions.countClaimedBy(uuid) >= config.maxCellsPerPlayer) {
			player.sendSystemMessage(Component.literal("§cคุณครอบครองที่ดินครบโควตา " + config.maxCellsPerPlayer + " ช่องแล้ว"));
			return 0;
		}
		if (regions.isCommonArea(cx, cz)) {
			player.sendSystemMessage(Component.literal("§cนี่คือพื้นที่เกิดส่วนกลาง ไม่สามารถซื้อได้"));
			return 0;
		}
		if (regions.get(id) != null) {
			player.sendSystemMessage(Component.literal("§cเขตนี้มีเจ้าของแล้ว (" + regions.ownerName(id) + ")"));
			return 0;
		}
		int totalClaimed = regions.ownerMap().size();
		long price = Math.min(1_000_000L, Math.max(100_000L, 100_000L + ((long) totalClaimed * 15_000L)));
		long bal = economy.balance(player.getUUID());
		if (bal < price) {
			player.sendSystemMessage(Component.literal("§cเงินไม่พอซื้อที่ดิน ต้องการ " + price + " " + config.currencyName
					+ " (คุณมี " + bal + " " + config.currencyName + ")"));
			return 0;
		}
		TxResult tx = economy.withdraw(player.getUUID(), price, "land.purchase:" + id, id);
		if (tx != TxResult.OK) {
			player.sendSystemMessage(Component.literal("§cการหักเงินไม่สำเร็จ (" + tx + ")"));
			return 0;
		}
		String name = player.getGameProfile().name();
		RegionManager.ClaimResult r = regions.claim(cx, cz, uuid, name);
		if (r != RegionManager.ClaimResult.OK) {
			economy.deposit(player.getUUID(), price, "land.refund:" + id, id);
			player.sendSystemMessage(Component.literal("§cไม่สามารถจองเขตได้: " + r));
			return 0;
		}
		player.sendSystemMessage(Component.literal("§aซื้อกรรมสิทธิ์เขต " + id + " สำเร็จ! จ่าย " + price + " "
				+ config.currencyName + " (คงเหลือ " + economy.balance(player.getUUID()) + " " + config.currencyName
				+ ", ถือครอง " + regions.countClaimedBy(uuid) + "/" + config.maxCellsPerPlayer + " ช่อง)"));
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

	private int runClaimTransferHere(CommandSourceStack src, String targetName) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		int bx = player.getBlockX();
		int bz = player.getBlockZ();
		int cx = Math.floorDiv(bx, config.cellSize);
		int cz = Math.floorDiv(bz, config.cellSize);
		String id = cx + "," + cz;
		UUID targetUuid = accounts.uuidOf(targetName).orElse(null);
		String resolvedName = targetName;
		if (targetUuid == null) {
			ServerPlayer tp = src.getServer().getPlayerList().getPlayerByName(targetName);
			if (tp != null) {
				targetUuid = tp.getUUID();
				resolvedName = tp.getGameProfile().name();
			}
		}
		if (targetUuid == null) {
			player.sendSystemMessage(Component.literal("ไม่พบผู้เล่น " + targetName));
			return 0;
		}
		if (targetUuid.equals(player.getUUID())) {
			player.sendSystemMessage(Component.literal("ไม่สามารถโอนเขตให้ตัวเองได้"));
			return 0;
		}
		boolean admin = src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
		boolean ok = regions.transfer(id, player.getUUID().toString(), targetUuid.toString(), resolvedName, admin);
		if (ok) {
			player.sendSystemMessage(Component.literal("โอนกรรมสิทธิ์เขต " + id + " ให้แก่ " + resolvedName + " เรียบร้อยแล้ว"));
		} else {
			player.sendSystemMessage(Component.literal("ไม่สามารถโอนเขตได้ (คุณไม่ใช่เจ้าของเขตนี้)"));
		}
		return ok ? 1 : 0;
	}

	private int runAdminTransfer(CommandSourceStack src, int cx, int cz, String targetName) {
		String id = cx + "," + cz;
		UUID targetUuid = accounts.uuidOf(targetName).orElse(null);
		String resolvedName = targetName;
		if (targetUuid == null) {
			ServerPlayer tp = src.getServer().getPlayerList().getPlayerByName(targetName);
			if (tp != null) {
				targetUuid = tp.getUUID();
				resolvedName = tp.getGameProfile().name();
			}
		}
		if (targetUuid == null) {
			src.sendFailure(Component.literal("ไม่พบผู้เล่น " + targetName));
			return 0;
		}
		boolean ok = regions.transfer(id, "admin", targetUuid.toString(), resolvedName, true);
		if (ok) {
			final String finalName = resolvedName;
			src.sendSuccess(() -> Component.literal("Admin: โอนเขต " + id + " ให้แก่ " + finalName + " สำเร็จ"), true);
		} else {
			src.sendFailure(Component.literal("ไม่พบเขต " + id + " หรือยังไม่มีการจอง"));
		}
		return ok ? 1 : 0;
	}

	private int runTp(CommandSourceStack src, String targetName) {
		ServerPlayer sender = src.getPlayer();
		if (sender == null || src.getServer() == null) return 0;
		ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
		if (target == null) {
			sender.sendSystemMessage(Component.literal("§c[TP] ไม่พบผู้เล่น " + targetName + " ในเซิร์ฟเวอร์ (ต้องออนไลน์อยู่)"));
			return 0;
		}

		boolean isAdmin = config.isAdmin(sender.getScoreboardName()) || src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
		if (isAdmin) {
			teleports.teleportDirect(sender, target);
			return 1;
		} else {
			sender.sendSystemMessage(Component.literal("§e[TP] กำลังส่งคำขอ /tpa ไปยัง " + target.getScoreboardName() + "..."));
			teleports.request(sender, target);
			return 1;
		}
	}

	private int runTpaRequestOrAccept(CommandSourceStack src, String targetName) {
		ServerPlayer player = src.getPlayer();
		if (player == null || src.getServer() == null) return 0;
		if (teleports.hasPending(player.getUUID())) {
			return runTpaAccept(src, targetName);
		}
		return runTpaRequest(src, targetName);
	}

	private int runTpaRequest(CommandSourceStack src, String targetName) {
		ServerPlayer sender = src.getPlayer();
		if (sender == null || src.getServer() == null) return 0;
		ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
		if (target == null) {
			sender.sendSystemMessage(Component.literal("§c[TPA] ไม่พบผู้เล่น " + targetName + " ในเซิร์ฟเวอร์ (ต้องออนไลน์อยู่)"));
			return 0;
		}
		teleports.request(sender, target);
		return 1;
	}

	private int runTpaAccept(CommandSourceStack src, String targetName) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		teleports.accept(player, targetName);
		return 1;
	}

	private int runTpaDeny(CommandSourceStack src, String targetName) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		teleports.deny(player, targetName);
		return 1;
	}

	private int runTpaCancel(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		teleports.cancel(player);
		return 1;
	}
}
