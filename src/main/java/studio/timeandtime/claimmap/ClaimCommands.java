package studio.timeandtime.claimmap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-game commands:
 * <ul>
 *   <li>{@code /auth reset <player>}  - admin password reset (kicks the player)</li>
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
 *   <li>{@code /timesmp here}         - show info about the current cell</li>
 *   <li>{@code /timesmp reload}       - reload config + regions (operator)</li>
 *   <li>{@code /map}                  - open the land map GUI</li>
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
	private final HomeManager homeManager;
	private final studio.timeandtime.claimmap.world.WorldManager worldManager;
	private final PlayerMarket playerMarket;
	private final Map<UUID, SellAllPlan> sellAllPlans = new HashMap<>();

	private record SellAllPlan(Map<String, Integer> items, long expiresAt) {
	}

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy, AuthGate authGate, ShopManager shop, MailboxManager mailbox,
			AuctionManager auctions, PriceService prices, AdminService admin) {
		this(regions, links, accounts, config, economy, authGate, shop, mailbox, auctions, prices, admin,
				new TeleportManager(), null, null);
	}

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy, AuthGate authGate, ShopManager shop, MailboxManager mailbox,
			AuctionManager auctions, PriceService prices, AdminService admin, TeleportManager teleports) {
		this(regions, links, accounts, config, economy, authGate, shop, mailbox, auctions, prices, admin,
				teleports, null, null);
	}

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy, AuthGate authGate, ShopManager shop, MailboxManager mailbox,
			AuctionManager auctions, PriceService prices, AdminService admin, TeleportManager teleports,
			HomeManager homeManager, studio.timeandtime.claimmap.world.WorldManager worldManager) {
		this(regions, links, accounts, config, economy, authGate, shop, mailbox, auctions, prices, admin,
				teleports, homeManager, worldManager, null);
	}

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config,
			EconomyManager economy, AuthGate authGate, ShopManager shop, MailboxManager mailbox,
			AuctionManager auctions, PriceService prices, AdminService admin, TeleportManager teleports,
			HomeManager homeManager, studio.timeandtime.claimmap.world.WorldManager worldManager,
			PlayerMarket playerMarket) {
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
		this.homeManager = homeManager;
		this.worldManager = worldManager;
		this.playerMarket = playerMarket;
	}

	public void register() {			CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			dispatcher.register(Commands.literal("auth")
					.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
					.then(Commands.literal("reset")
							.then(Commands.argument("player", StringArgumentType.word())
									.executes(ctx -> runAdminResetPass(ctx.getSource(),
											StringArgumentType.getString(ctx, "player"))))));

			dispatcher.register(Commands.literal("bal").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runBal(ctx.getSource())));

			dispatcher.register(Commands.literal("pay").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("player", StringArgumentType.word())
							.then(Commands.argument("amount", StringArgumentType.word())
									.executes(ctx -> runPay(ctx.getSource(),
											StringArgumentType.getString(ctx, "player"),
											StringArgumentType.getString(ctx, "amount"))))));

			dispatcher.register(Commands.literal("sell").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runSellGui(ctx.getSource()))
					.then(Commands.literal("hand")
							.executes(ctx -> runSellHand(ctx.getSource()))
							.then(Commands.argument("price", StringArgumentType.word())
									.executes(ctx -> runBuySell(ctx.getSource(),
											StringArgumentType.getString(ctx, "price")))))
					.then(Commands.literal("all")
							.executes(ctx -> runSellAllSummary(ctx.getSource()))
							.then(Commands.literal("confirm")
									.executes(ctx -> runSellAllConfirm(ctx.getSource()))))
					.then(Commands.argument("price", StringArgumentType.word())
							.executes(ctx -> runBuySell(ctx.getSource(),
									StringArgumentType.getString(ctx, "price")))));

			dispatcher.register(Commands.literal("buy").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runBuyGui(ctx.getSource()))
					.then(Commands.literal("sell")
							.then(Commands.argument("price", StringArgumentType.word())
									.executes(ctx -> runBuySell(ctx.getSource(),
											StringArgumentType.getString(ctx, "price")))))
					.then(Commands.literal("cancel")
							.then(Commands.argument("id", StringArgumentType.word())
									.executes(ctx -> runBuyCancel(ctx.getSource(),
											StringArgumentType.getString(ctx, "id")))))
					.then(Commands.argument("item", StringArgumentType.word())
							.then(Commands.argument("qty", StringArgumentType.word())
									.executes(ctx -> runBuy(ctx.getSource(),
											StringArgumentType.getString(ctx, "item"),
											StringArgumentType.getString(ctx, "qty"))))));

			dispatcher.register(Commands.literal("mail").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runMailGui(ctx.getSource())));

			dispatcher.register(Commands.literal("ah").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runAhGui(ctx.getSource()))
					.then(Commands.literal("list").executes(ctx -> runAhList(ctx.getSource())))
					.then(Commands.literal("sell")
							.then(Commands.argument("startPrice", StringArgumentType.word())
									.executes(ctx -> runAhSell(ctx.getSource(),
											StringArgumentType.getString(ctx, "startPrice"), "24"))
									.then(Commands.argument("hours", StringArgumentType.word())
											.executes(ctx -> runAhSell(ctx.getSource(),
													StringArgumentType.getString(ctx, "startPrice"),
													StringArgumentType.getString(ctx, "hours"))))))
					.then(Commands.literal("cell")
							.then(Commands.argument("cellId", StringArgumentType.word())
									.then(Commands.argument("startPrice", StringArgumentType.word())
											.executes(ctx -> runAhCell(ctx.getSource(),
													StringArgumentType.getString(ctx, "cellId"),
													StringArgumentType.getString(ctx, "startPrice"), "24"))
											.then(Commands.argument("hours", StringArgumentType.word())
													.executes(ctx -> runAhCell(ctx.getSource(),
															StringArgumentType.getString(ctx, "cellId"),
															StringArgumentType.getString(ctx, "startPrice"),
															StringArgumentType.getString(ctx, "hours")))))))
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

			dispatcher.register(Commands.literal("price").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runPriceDefault(ctx.getSource()))
					.then(Commands.literal("hand").executes(ctx -> runPriceHand(ctx.getSource()))));

			dispatcher.register(Commands.literal("eco")
					.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
					.then(Commands.literal("give").then(ecoArgs("give")))
					.then(Commands.literal("take").then(ecoArgs("take")))
					.then(Commands.literal("set").then(ecoArgs("set"))));

			dispatcher.register(Commands.literal("claim").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runClaimHelp(ctx.getSource()))
					.then(Commands.literal("add")
							.then(Commands.argument("player", StringArgumentType.word())
									.executes(ctx -> runClaimAddHere(ctx.getSource(),
											StringArgumentType.getString(ctx, "player")))))
					.then(Commands.literal("remove")
							.then(Commands.argument("player", StringArgumentType.word())
									.executes(ctx -> runClaimRemoveHere(ctx.getSource(),
											StringArgumentType.getString(ctx, "player")))))
					.then(Commands.literal("tp")
							.then(Commands.argument("cellId", StringArgumentType.word())
									.executes(ctx -> runClaimTp(ctx.getSource(),
											StringArgumentType.getString(ctx, "cellId")))))
					.then(Commands.literal("tf")
							.then(Commands.argument("player", StringArgumentType.word())
									.executes(ctx -> runClaimTransferHere(ctx.getSource(),
											StringArgumentType.getString(ctx, "player")))))
					.then(Commands.literal("at")
							.then(Commands.argument("startPrice", StringArgumentType.word())
									.executes(ctx -> runClaimAuctionHere(ctx.getSource(),
											StringArgumentType.getString(ctx, "startPrice"), "24"))
									.then(Commands.argument("hours", StringArgumentType.word())
											.executes(ctx -> runClaimAuctionHere(ctx.getSource(),
													StringArgumentType.getString(ctx, "startPrice"),
													StringArgumentType.getString(ctx, "hours")))))));

			dispatcher.register(Commands.literal("sethome").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runSetHome(ctx.getSource(), "home"))
					.then(Commands.argument("name", StringArgumentType.word())
							.executes(ctx -> runSetHome(ctx.getSource(),
									StringArgumentType.getString(ctx, "name")))));

			dispatcher.register(Commands.literal("delhome").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("name", StringArgumentType.word())
							.executes(ctx -> runDelHome(ctx.getSource(),
									StringArgumentType.getString(ctx, "name")))));

			dispatcher.register(Commands.literal("home").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runHomeGui(ctx.getSource()))
					.then(Commands.argument("name", StringArgumentType.word())
							.executes(ctx -> runHomeTeleport(ctx.getSource(),
									StringArgumentType.getString(ctx, "name")))));

			dispatcher.register(Commands.literal("timesmp")
					.executes(ctx -> {
						ctx.getSource().sendSuccess(() -> Component.literal(
								"§eใช้ /map เปิดแผนที่ • /timesmp here • /timesmp reload|transfer (แอดมิน)"), false);
						return 1;
					})
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

			// --- TimeSMP Pregen System (/tsmp pregen) ---
			dispatcher.register(Commands.literal("pregen")
					.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
					.executes(ctx -> {
						studio.timeandtime.claimmap.world.PregenManager.instance().showStatus(null, ctx.getSource());
						return 1;
					})
					.then(Commands.literal("start")
							.then(Commands.argument("world", StringArgumentType.word())
									.then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(16, 50000))
											.executes(ctx -> {
												String w = StringArgumentType.getString(ctx, "world");
												int r = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius");
												int cx = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getBlockX() : 0;
												int cz = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getBlockZ() : 0;
												return studio.timeandtime.claimmap.world.PregenManager.instance().startTask(w, cx, cz, r, true, ctx.getSource()) ? 1 : 0;
											})
											.then(Commands.literal("square")
													.executes(ctx -> {
														String w = StringArgumentType.getString(ctx, "world");
														int r = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius");
														int cx = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getBlockX() : 0;
														int cz = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getBlockZ() : 0;
														return studio.timeandtime.claimmap.world.PregenManager.instance().startTask(w, cx, cz, r, false, ctx.getSource()) ? 1 : 0;
													}))
											.then(Commands.literal("circle")
													.executes(ctx -> {
														String w = StringArgumentType.getString(ctx, "world");
														int r = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius");
														int cx = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getBlockX() : 0;
														int cz = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getBlockZ() : 0;
														return studio.timeandtime.claimmap.world.PregenManager.instance().startTask(w, cx, cz, r, true, ctx.getSource()) ? 1 : 0;
													})))))
					.then(Commands.literal("status")
							.executes(ctx -> {
								studio.timeandtime.claimmap.world.PregenManager.instance().showStatus(null, ctx.getSource());
								return 1;
							})
							.then(Commands.argument("world", StringArgumentType.word())
									.executes(ctx -> {
										studio.timeandtime.claimmap.world.PregenManager.instance().showStatus(StringArgumentType.getString(ctx, "world"), ctx.getSource());
										return 1;
									})))
					.then(Commands.literal("pause")
							.then(Commands.argument("world", StringArgumentType.word())
									.executes(ctx -> studio.timeandtime.claimmap.world.PregenManager.instance().pauseTask(StringArgumentType.getString(ctx, "world"), ctx.getSource()) ? 1 : 0)))
					.then(Commands.literal("resume")
							.then(Commands.argument("world", StringArgumentType.word())
									.executes(ctx -> studio.timeandtime.claimmap.world.PregenManager.instance().resumeTask(StringArgumentType.getString(ctx, "world"), ctx.getSource()) ? 1 : 0)))
					.then(Commands.literal("cancel")
							.then(Commands.argument("world", StringArgumentType.word())
									.executes(ctx -> studio.timeandtime.claimmap.world.PregenManager.instance().cancelTask(StringArgumentType.getString(ctx, "world"), ctx.getSource()) ? 1 : 0))));

			dispatcher.register(Commands.literal("map").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runClaimMapGui(ctx.getSource())));

			dispatcher.register(Commands.literal("help").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runHelpGui(ctx.getSource())));

			dispatcher.register(Commands.literal("tpa").requires(src -> src.getPlayer() != null)
					.executes(ctx -> runTpaGui(ctx.getSource()))
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
							.executes(ctx -> runTpaRequest(ctx.getSource(),
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
		});
	}

	private int runAdminResetPass(CommandSourceStack source, String targetName) {
		var srv = ClaimMap.getServer();
		UUID targetUuid = null;
		ServerPlayer targetPlayer = srv != null ? srv.getPlayerList().getPlayerByName(targetName) : null;
		if (targetPlayer != null) {
			targetUuid = targetPlayer.getUUID();
		} else {
			targetUuid = accounts.anyUuidOf(targetName).orElse(null);
		}

		if (targetUuid == null) {
			source.sendFailure(Component.literal("§cไม่พบข้อมูลผู้เล่น: " + targetName));
			return 0;
		}

		boolean reset = accounts.resetPassword(targetUuid.toString());
		if (!reset) {
			source.sendFailure(Component.literal("§cรีเซ็ตรหัสผ่านไม่สำเร็จ (ไม่พบบัญชี)"));
			return 0;
		}

		if (targetPlayer != null) {
			targetPlayer.connection.disconnect(Component.literal(
					"§c[Time SMP] ผู้ดูแลระบบได้รีเซ็ตรหัสผ่านของคุณ\n§eกรุณาเข้าสู่ระบบใหม่เพื่อตั้งรหัสผ่านใหม่"
			));
		}

		source.sendSuccess(() -> Component.literal("§a[Auth] รีเซ็ตรหัสผ่านของ " + targetName
				+ " สำเร็จ ผู้เล่นจะต้องตั้งรหัสผ่านใหม่เมื่อเข้าสู่เซิร์ฟเวอร์ครั้งถัดไป"), true);
		return 1;
	}

	private int runSetHome(CommandSourceStack source, String name) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return 0;
		if (homeManager == null) {
			player.sendSystemMessage(Component.literal("§c[Home] ระบบบ้านยังไม่พร้อมใช้งาน"));
			return 0;
		}
		if (!homeManager.isValidName(name)) {
			player.sendSystemMessage(Component.literal("§c[Home] ชื่อบ้านไม่ถูกต้อง (ต้องเป็นภาษาอังกฤษหรือตัวเลข ไม่เกิน 24 ตัวอักษร)"));
			return 0;
		}
		boolean ok = homeManager.setHome(player.getUUID(), name, (ServerLevel) player.level(), player.position(), player.getYRot(), player.getXRot());
		if (ok) {
			player.sendSystemMessage(Component.literal("§a[Home] บันทึกจุดเซฟบ้าน \"§e" + name + "§a\" สำเร็จ! (พิมพ์ §b/home §aเพื่อเปิดเมนู)"));
			return 1;
		} else {
			player.sendSystemMessage(Component.literal("§c[Home] ไม่สามารถบันทึกบ้านได้ (จำนวนบ้านถึงขีดจำกัดสูงสุด " + HomeManager.MAX_HOMES + " หลังแล้ว)"));
			return 0;
		}
	}

	private int runDelHome(CommandSourceStack source, String name) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return 0;
		if (homeManager == null) {
			player.sendSystemMessage(Component.literal("§c[Home] ระบบบ้านยังไม่พร้อมใช้งาน"));
			return 0;
		}
		boolean ok = homeManager.deleteHome(player.getUUID(), name);
		if (ok) {
			player.sendSystemMessage(Component.literal("§e[Home] ลบจุดเซฟบ้าน \"§f" + name + "§e\" สำเร็จ"));
			return 1;
		} else {
			player.sendSystemMessage(Component.literal("§c[Home] ไม่พบจุดเซฟบ้านชื่อ \"§f" + name + "§c\""));
			return 0;
		}
	}

	private int runHomeGui(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return 0;
		if (homeManager == null) {
			player.sendSystemMessage(Component.literal("§c[Home] ระบบบ้านยังไม่พร้อมใช้งาน"));
			return 0;
		}
		HomeGui.open(player, homeManager, teleports);
		return 1;
	}

	private int runHomeTeleport(CommandSourceStack source, String name) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return 0;
		if (homeManager == null) {
			player.sendSystemMessage(Component.literal("§c[Home] ระบบบ้านยังไม่พร้อมใช้งาน"));
			return 0;
		}
		HomeManager.HomeEntry entry = homeManager.getHome(player.getUUID(), name);
		if (entry == null) {
			player.sendSystemMessage(Component.literal("§c[Home] ไม่พบจุดเซฟบ้านชื่อ \"§f" + name + "§c\" (พิมพ์ §e/home §cเพื่อดูรายการทั้งหมด)"));
			return 0;
		}
		if (teleports != null) {
			teleports.startWarmup(player, Component.literal("บ้าน " + entry.name), () -> homeManager.teleport(player, entry));
		} else {
			homeManager.teleport(player, entry);
		}
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

	private int runSellGui(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		SellGui.open(player, shop, auctions, prices, economy, config.currencyName);
		return 1;
	}

	private int runBuyGui(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (playerMarket == null) {
			player.sendSystemMessage(Component.literal("§cระบบตลาดยังไม่พร้อมใช้งาน"));
			return 0;
		}
		BuyGui.open(player, playerMarket, economy, config.currencyName);
		return 1;
	}

	private int runBuySell(CommandSourceStack src, String priceText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (playerMarket == null || playerMarket.isDisabled()) {
			player.sendSystemMessage(Component.literal("§cระบบตลาดผู้เล่นปิดปรับปรุงชั่วคราว"));
			return 0;
		}
		long price;
		try {
			price = Long.parseLong(priceText);
		} catch (NumberFormatException e) {
			player.sendSystemMessage(Component.literal("§cกรุณาระบุราคาเป็นตัวเลขจำนวนเต็มที่ถูกต้อง"));
			return 0;
		}
		if (price < 1 || price > EconomyManager.MAX_MONEY) {
			player.sendSystemMessage(Component.literal("§cราคาต้องอยู่ระหว่าง 1 ถึง " + EconomyManager.MAX_MONEY));
			return 0;
		}
		ItemStack hand = player.getMainHandItem();
		if (hand == null || hand.isEmpty()) {
			player.sendSystemMessage(Component.literal("§cกรุณาถือไอเท็มที่ต้องการขายไว้ในมือหลัก"));
			return 0;
		}
		String itemId = ItemCodecs.idOf(hand);
		if (prices.isBlocked(itemId) || !ItemRegistryAdapter.exists(itemId)) {
			player.sendSystemMessage(Component.literal("§cไอเท็มนี้ไม่อนุญาตให้นำมาลงขาย"));
			return 0;
		}
		var server = ClaimMap.getServer();
		if (server == null) return 0;
		String itemJson = ItemCodecs.encode(hand, server.registryAccess());
		int count = hand.getCount();
		String label = ItemRegistryAdapter.label(itemId);

		PlayerMarket.CreatePlan plan = new PlayerMarket.CreatePlan(
				player.getUUID().toString(),
				player.getGameProfile().name(),
				itemJson,
				itemId,
				count,
				label,
				price
		);

		ItemStack backup = hand.copy();
		hand.setCount(0);

		PlayerMarket.CreateOutcome out = playerMarket.create(plan);
		if (out.result() != PlayerMarket.Result.OK) {
			player.getInventory().setItem(player.getInventory().getSelectedSlot(), backup);
			player.sendSystemMessage(Component.literal("§cไม่สามารถลงขายได้: " + marketErrorText(out.result())));
			return 0;
		}
		player.sendSystemMessage(Component.literal("§a[ตลาดผู้เล่น] ลงขายสำเร็จ! §f" + label + " x" + count
				+ " §aราคา §6" + price + " " + config.currencyName + " §7(ตรวจสอบได้ที่ /buy)"));
		return 1;
	}

	private int runBuyCancel(CommandSourceStack src, String id) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (playerMarket == null || playerMarket.isDisabled()) {
			player.sendSystemMessage(Component.literal("§cระบบตลาดผู้เล่นปิดปรับปรุงชั่วคราว"));
			return 0;
		}
		PlayerMarket.Result r = playerMarket.cancel(player.getUUID(), false, id);
		if (r == PlayerMarket.Result.OK) {
			player.sendSystemMessage(Component.literal("§a[ตลาดผู้เล่น] ยกเลิกการลงขายสำเร็จ! ไอเท็มถูกส่งเข้า /mail"));
			return 1;
		} else {
			player.sendSystemMessage(Component.literal("§c[ตลาดผู้เล่น] ไม่สามารถยกเลิกได้: " + marketErrorText(r)));
			return 0;
		}
	}

	private static String marketErrorText(PlayerMarket.Result r) {
		return switch (r) {
			case OWN_LISTING -> "ไม่สามารถซื้อไอเท็มของตัวเองได้";
			case INSUFFICIENT -> "เงินในบัญชีของคุณไม่เพียงพอ";
			case FROZEN -> "บัญชีการเงินของคุณถูกระงับ";
			case CLOSED -> "รายการนี้ถูกซื้อหรือปิดไปแล้ว";
			case NOT_FOUND -> "ไม่พบรายการสินค้านี้";
			case LIMIT_REACHED -> "คุณลงขายสินค้าครบจำนวนจำกัดแล้ว";
			case DISABLED -> "ระบบตลาดซื้อขายปิดปรับปรุงชั่วคราว";
			case FORBIDDEN -> "คุณไม่มีสิทธิ์จัดการรายการนี้";
			case IO_ERROR -> "กล่องรับของปลายทางเต็ม หรือเกิดข้อผิดพลาดในการส่งไอเท็ม";
			case BAD_REQUEST -> "ข้อมูลไม่ถูกต้อง";
			default -> "เกิดข้อผิดพลาด (" + r + ")";
		};
	}

	private long pricesSell(String itemId) {
		return shop.sellUnitPrice(itemId);
	}

	private int runBuy(CommandSourceStack src, String item, String qtyText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		player.sendSystemMessage(Component.literal("§eหากต้องการเลือกซื้อสินค้าจากผู้เล่น กรุณาพิมพ์ §a/buy §eเพื่อเปิดตลาดซื้อขายสินค้าผู้เล่น"));
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
		player.sendSystemMessage(Component.literal("พิมพ์ /mail เพื่อเปิดกล่องรับของ"));
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

	private int runMailGui(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		MailGui.open(player, mailbox, playerMarket, auctions, economy, config.currencyName);
		return 1;
	}

	private int runPriceDefault(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		ItemStack stack = player.getMainHandItem();
		if (stack != null && !stack.isEmpty()) {
			PriceGui.openDetail(player, prices, shop, config.currencyName, ItemCodecs.idOf(stack), 0, 0);
		} else {
			PriceGui.open(player, prices, shop, config.currencyName, 0, 0);
		}
		return 1;
	}

	private int runPriceHand(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		ItemStack stack = player.getMainHandItem();
		if (stack == null || stack.isEmpty()) {
			player.sendSystemMessage(Component.literal("§eในมือหลักว่างเปล่า กำลังเปิดหน้ารวมราคาทั้งหมด..."));
			PriceGui.open(player, prices, shop, config.currencyName, 0, 0);
			return 1;
		}
		PriceGui.openDetail(player, prices, shop, config.currencyName, ItemCodecs.idOf(stack), 0, 0);
		return 1;
	}

	private int runPriceItem(CommandSourceStack src, String query) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		String itemId = query.contains(":") ? query : "minecraft:" + query;
		if (!ItemRegistryAdapter.exists(itemId)) {
			player.sendSystemMessage(Component.literal("§cไม่พบไอเท็ม: " + itemId));
			return 0;
		}
		PriceGui.openDetail(player, prices, shop, config.currencyName, itemId, 0, 0);
		return 1;
	}

	private int runClaimMapGui(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (!RegionManager.isClaimableWorld(player.level())) {
			player.sendSystemMessage(Component.literal("§cระบบแผนที่เรดาร์และเขตที่ดินเปิดใช้งานเฉพาะในโลกหลัก เนเธอร์ และ The End เท่านั้น"));
			return 0;
		}
		int cx = Math.floorDiv(player.getBlockX(), config.cellSize);
		int cz = Math.floorDiv(player.getBlockZ(), config.cellSize);
		ClaimMapGui.open(player, regions, config, economy, cx, cz);
		return 1;
	}

	private int runAhGui(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		AuctionGui.open(player, auctions, economy, config.currencyName, 0);
		return 1;
	}

	private int runHelpGui(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (authGate != null && !authGate.isAuthed(player.getUUID())) {
			src.sendFailure(Component.literal("§cกรุณายืนยันตัวตนก่อน (ใส่รหัสผ่านในหน้าต่างที่ขึ้นมาเมื่อเข้าเกม)"));
			return 0;
		}
		HelpGui.open(player, regions, config, economy, prices, shop, auctions, mailbox);
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
			if (a.highBid > 0) sb.append(" ล่าสุด ").append(a.highBid);
			long mins = Math.max(0, (a.endsAt - System.currentTimeMillis()) / 60_000L);
			sb.append(" เหลือ ").append(mins).append(" นาที\n");
		}
		if (active == 0) {
			player.sendSystemMessage(Component.literal("ยังไม่มีรายการประมูลที่เปิดอยู่"));
			return 0;
		}
		player.sendSystemMessage(Component.literal(sb.toString()));
		return 1;
	}

	private int runAhSell(CommandSourceStack src, String startText, String hoursText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (auctions.isDisabled()) {
			player.sendSystemMessage(Component.literal("ระบบตลาดถูกปิดชั่วคราว กรุณาแจ้งแอดมิน"));
			return 0;
		}
		long start;
		int hours;
		try {
			start = Long.parseLong(startText);
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
		Long buyout = null;

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
				+ " ราคาเริ่ม " + start + " " + config.currencyName));
		return 1;
	}

	private int runClaimAuctionHere(CommandSourceStack src, String startText, String hoursText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (!RegionManager.isClaimableWorld(player.level())) {
			player.sendSystemMessage(Component.literal("§cระบบเขตที่ดินเปิดใช้งานเฉพาะในโลกหลัก เนเธอร์ และ The End เท่านั้น"));
			return 0;
		}
		int bx = player.getBlockX();
		int bz = player.getBlockZ();
		String cellId = regions.cellIdAt(player.level(), bx, bz);
		return doCellAuction(player, cellId, startText, hoursText);
	}

	private int runAhCell(CommandSourceStack src, String cellId, String startText, String hoursText) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		return doCellAuction(player, cellId, startText, hoursText);
	}

	private int doCellAuction(ServerPlayer player, String cellId, String startText, String hoursText) {
		if (auctions.isDisabled()) {
			player.sendSystemMessage(Component.literal("ระบบตลาดถูกปิดชั่วคราว"));
			return 0;
		}
		long start;
		int hours;
		try {
			start = Long.parseLong(startText);
			hours = Integer.parseInt(hoursText);
		} catch (NumberFormatException e) {
			player.sendSystemMessage(Component.literal("ราคาและชั่วโมงต้องเป็นตัวเลขจำนวนเต็ม"));
			return 0;
		}
		if (start < 0 || start > EconomyManager.MAX_MONEY) {
			player.sendSystemMessage(Component.literal("ราคาเริ่มต้นต้องระหว่าง 0 ถึง " + EconomyManager.MAX_MONEY));
			return 0;
		}
		Long buyout = null;
		if (hours < 1 || hours > config.auctionMaxHours) {
			player.sendSystemMessage(Component.literal("ระยะเวลาต้องระหว่าง 1 ถึง " + config.auctionMaxHours + " ชั่วโมง"));
			return 0;
		}
		AuctionManager.CreateOutcome o = auctions.createCellAuction(
				cellId, player.getUUID().toString(), player.getGameProfile().name(), start, buyout, hours);
		if (o.result() != AuctionManager.Result.OK) {
			player.sendSystemMessage(Component.literal("เปิดประมูลเขตไม่ได้: " + auctionErrorText(o.result())));
			return 0;
		}
		player.sendSystemMessage(Component.literal("เปิดประมูลเขต " + cellId + " แล้ว (รหัส " + o.auctionId()
				+ ") ราคาเริ่ม " + start + " " + config.currencyName
				+ " ระยะเวลา " + hours + " ชม."));
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
			player.sendSystemMessage(Component.literal("ยกเลิกแล้ว ของจะอยู่ในกล่องของ พิมพ์ /mail"));
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

	private int runClaimHelp(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		player.sendSystemMessage(Component.literal("§6============== [ ระบบจัดการเขตที่ดิน ] =============="));
		player.sendSystemMessage(Component.literal("§e• §a/map §7- เปิดดูแผนที่เรดาร์และกดจอง/ซื้อ/จัดการที่ดิน"));
		player.sendSystemMessage(Component.literal("§e• §a/claim add <ชื่อ> §7- เพิ่มเพื่อนเป็นสมาชิกในแปลงที่ดิน"));
		player.sendSystemMessage(Component.literal("§e• §a/claim remove <ชื่อ> §7- ลบสิทธิ์เพื่อนออกจากแปลงที่ดิน"));
		player.sendSystemMessage(Component.literal("§e• §a/claim tf <ชื่อ> §7- โอนกรรมสิทธิ์ที่ดินแปลงปัจจุบัน"));
		player.sendSystemMessage(Component.literal("§e• §a/claim at <ราคา> [ชม.] §7- นำโฉนดที่ดินขึ้นตลาดประมูล /ah"));
		player.sendSystemMessage(Component.literal("§e• §a/claim tp <พิกัดเขต> §7- วาร์ปไปยังเขต (เช่น /claim tp 5,5 หรือ nether:5,5)"));
		player.sendSystemMessage(Component.literal("§6=================================================="));
		return 1;
	}

	private int runClaim(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (!RegionManager.isClaimableWorld(player.level())) {
			player.sendSystemMessage(Component.literal("§cระบบจองและซื้อเขตที่ดินเปิดใช้งานเฉพาะในโลกหลัก เนเธอร์ และ The End เท่านั้น"));
			return 0;
		}
		if (player.level().dimension() == net.minecraft.world.level.Level.END
				&& studio.timeandtime.claimmap.world.EndArenaManager.isInsideArena(player.getBlockX(), player.getBlockZ())) {
			player.sendSystemMessage(Component.literal("§cพื้นที่นี้เป็นพื้นที่ส่วนกลางเกาะมังกร (The End) ไม่สามารถเคลมได้"));
			return 0;
		}
		int bx = player.getBlockX();
		int bz = player.getBlockZ();
		int cx = Math.floorDiv(bx, config.cellSize);
		int cz = Math.floorDiv(bz, config.cellSize);
		String id = regions.cellIdAt(player.level(), bx, bz);
		String uuid = player.getUUID().toString();
		int ownedCount = regions.countClaimedBy(uuid);
		boolean isFree = ownedCount < config.freeCellsPerPlayer;
		if (!isFree && config.maxCellsPerPlayer > 0 && ownedCount >= config.maxCellsPerPlayer) {
			player.sendSystemMessage(Component.literal("§cคุณครอบครองที่ดินครบโควตา " + config.maxCellsPerPlayer + " ช่องแล้ว"));
			return 0;
		}
		if (regions.get(id) != null) {
			player.sendSystemMessage(Component.literal("§cเขตนี้มีเจ้าของแล้ว (" + regions.ownerName(id) + ")"));
			return 0;
		}
		if (isFree) {
			String name = player.getGameProfile().name();
			RegionManager.ClaimResult r = regions.claim(player.level(), cx, cz, uuid, name);
			if (r != RegionManager.ClaimResult.OK) {
				player.sendSystemMessage(Component.literal("§cไม่สามารถจองเขตได้: " + r));
				return 0;
			}
			player.sendSystemMessage(Component.literal("§aรับสิทธิ์ครอบครองเขต " + id + " ฟรีสำเร็จ! (สิทธิ์ฟรีช่องที่ "
					+ (ownedCount + 1) + "/" + config.freeCellsPerPlayer + ", รวมถือครอง " + regions.countClaimedBy(uuid) + " ช่อง)"));
			return 1;
		}
		int totalClaimed = regions.ownerMap().size();
		long price = ClaimMap.instance() != null && ClaimMap.instance().landPrices() != null
				? ClaimMap.instance().landPrices().currentPrice()
				: Math.min(1_000_000L, Math.max(100_000L, 100_000L + ((long) totalClaimed * 15_000L)));
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
		RegionManager.ClaimResult r = regions.claim(player.level(), cx, cz, uuid, name);
		if (r != RegionManager.ClaimResult.OK) {
			economy.deposit(player.getUUID(), price, "land.refund:" + id, id);
			player.sendSystemMessage(Component.literal("§cไม่สามารถจองเขตได้: " + r));
			return 0;
		}
		if (ClaimMap.instance() != null && ClaimMap.instance().landPrices() != null) {
			ClaimMap.instance().landPrices().onCellPurchased();
		}
		player.sendSystemMessage(Component.literal("§aซื้อกรรมสิทธิ์เขต " + id + " สำเร็จ! จ่าย " + price + " "
				+ config.currencyName + " (คงเหลือ " + economy.balance(player.getUUID()) + " " + config.currencyName
				+ ", รวมถือครอง " + regions.countClaimedBy(uuid) + " ช่อง)"));
		return 1;
	}

	private int runHere(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (!RegionManager.isClaimableWorld(player.level())) {
			player.sendSystemMessage(Component.literal("§cระบบเขตที่ดินเปิดใช้งานเฉพาะในโลกหลัก เนเธอร์ และ The End เท่านั้น"));
			return 0;
		}
		String id = regions.cellIdAt(player.level(), player.getBlockX(), player.getBlockZ());
		RegionManager.CellData c = regions.get(id);
		String owner = c == null ? "free" : c.ownerName;
		src.sendSuccess(() -> Component.literal("Cell " + id + " -> " + owner + " (claimed " + regions.countClaimedBy(player.getUUID().toString())
				+ " by you)"), false);
		return 1;
	}

	private int runReload(CommandSourceStack src) {
		config.load();
		regions.load();
		src.sendSuccess(() -> Component.literal("Reloaded timesmp config + regions."), false);
		return 1;
	}

	private int runClaimTransferHere(CommandSourceStack src, String targetName) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (!RegionManager.isClaimableWorld(player.level())) {
			player.sendSystemMessage(Component.literal("§cระบบเขตที่ดินเปิดใช้งานเฉพาะในโลกหลัก เนเธอร์ และ The End เท่านั้น"));
			return 0;
		}
		int bx = player.getBlockX();
		int bz = player.getBlockZ();
		String id = regions.cellIdAt(player.level(), bx, bz);
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
		if (auctions.hasActiveCellAuction(id)) {
			player.sendSystemMessage(Component.literal("ไม่สามารถโอนเขตได้ เขตนี้กำลังเปิดประมูลอยู่"));
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

	private int runClaimAddHere(CommandSourceStack src, String targetName) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (!RegionManager.isClaimableWorld(player.level())) {
			player.sendSystemMessage(Component.literal("§cระบบเขตที่ดินเปิดใช้งานเฉพาะในโลกหลัก เนเธอร์ และ The End เท่านั้น"));
			return 0;
		}
		int bx = player.getBlockX();
		int bz = player.getBlockZ();
		String id = regions.cellIdAt(player.level(), bx, bz);
		RegionManager.CellData c = regions.get(id);
		String uuid = player.getUUID().toString();
		boolean admin = src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
		if (c == null || (!c.isOwner(uuid) && !admin)) {
			player.sendSystemMessage(Component.literal("คุณต้องเป็นเจ้าของเขต " + id + " นี้เพื่อเพิ่มสมาชิก"));
			return 0;
		}
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
			player.sendSystemMessage(Component.literal("คุณเป็นเจ้าของเขตนี้อยู่แล้ว"));
			return 0;
		}
		boolean ok = regions.addMember(id, c.ownerUuid, targetUuid.toString(), resolvedName);
		if (ok) {
			player.sendSystemMessage(Component.literal("เพิ่ม " + resolvedName + " เป็นสมาชิกในเขต " + id + " สำเร็จ (สามารถสร้างและเปิดกล่องได้)"));
		} else {
			player.sendSystemMessage(Component.literal("เพิ่มสมาชิกไม่สำเร็จ"));
		}
		return ok ? 1 : 0;
	}

	private int runClaimRemoveHere(CommandSourceStack src, String targetName) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		if (!RegionManager.isClaimableWorld(player.level())) {
			player.sendSystemMessage(Component.literal("§cระบบเขตที่ดินเปิดใช้งานเฉพาะในโลกหลัก เนเธอร์ และ The End เท่านั้น"));
			return 0;
		}
		int bx = player.getBlockX();
		int bz = player.getBlockZ();
		String id = regions.cellIdAt(player.level(), bx, bz);
		RegionManager.CellData c = regions.get(id);
		String uuid = player.getUUID().toString();
		boolean admin = src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
		if (c == null || (!c.isOwner(uuid) && !admin)) {
			player.sendSystemMessage(Component.literal("คุณต้องเป็นเจ้าของเขต " + id + " นี้เพื่อลบสมาชิก"));
			return 0;
		}
		UUID targetUuid = accounts.uuidOf(targetName).orElse(null);
		String resolvedName = targetName;
		if (targetUuid == null) {
			ServerPlayer tp = src.getServer().getPlayerList().getPlayerByName(targetName);
			if (tp != null) {
				targetUuid = tp.getUUID();
				resolvedName = tp.getGameProfile().name();
			}
		}
		String targetUuidStr = targetUuid != null ? targetUuid.toString() : null;
		if (targetUuidStr == null && c.members != null) {
			for (String m : c.members) {
				var nameOpt = accounts.nameOf(UUID.fromString(m));
				if (nameOpt.isPresent() && nameOpt.get().equalsIgnoreCase(targetName)) {
					targetUuidStr = m;
					resolvedName = nameOpt.get();
					break;
				}
			}
		}
		if (targetUuidStr == null) {
			player.sendSystemMessage(Component.literal("ไม่พบผู้เล่น " + targetName + " ในระบบ"));
			return 0;
		}
		boolean ok = regions.removeMember(id, c.ownerUuid, targetUuidStr);
		if (ok) {
			player.sendSystemMessage(Component.literal("ลบสิทธิ์ " + resolvedName + " ออกจากเขต " + id + " เรียบร้อยแล้ว"));
		} else {
			player.sendSystemMessage(Component.literal("ลบสมาชิกไม่สำเร็จ (ผู้เล่นนี้อาจไม่ได้เป็นสมาชิกในเขตนี้)"));
		}
		return ok ? 1 : 0;
	}

	private int runClaimTp(CommandSourceStack src, String cellId) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		int cx, cz;
		boolean isNether = false;
		String cleanId = cellId.trim();
		if (cleanId.startsWith("nether:")) {
			isNether = true;
			cleanId = cleanId.substring(7);
		}
		try {
			if (cleanId.contains(",")) {
				String[] parts = cleanId.split(",");
				cx = Integer.parseInt(parts[0].trim());
				cz = Integer.parseInt(parts[1].trim());
			} else {
				player.sendSystemMessage(Component.literal("รูปแบบเขตไม่ถูกต้อง (ตัวอย่าง: /claim tp 5,5 หรือ /claim tp nether:5,5)"));
				return 0;
			}
		} catch (Exception e) {
			player.sendSystemMessage(Component.literal("รูปแบบเขตไม่ถูกต้อง (ตัวอย่าง: /claim tp 5,5 หรือ /claim tp nether:5,5)"));
			return 0;
		}
		teleportToCell(player, isNether, cx, cz, config);
		return 1;
	}

	public static boolean teleportToCell(ServerPlayer player, boolean isNether, int cx, int cz, ClaimMapConfig config) {
		TeleportManager tp = ClaimMap.instance() != null ? ClaimMap.instance().teleports() : null;
		String label = (isNether ? "เนเธอร์ " : "") + "(" + cx + "," + cz + ")";
		if (tp != null) {
			tp.startWarmup(player, Component.literal("เขต " + label), () -> doTeleportToCell(player, isNether, cx, cz, config));
			return true;
		}
		return doTeleportToCell(player, isNether, cx, cz, config);
	}

	public static boolean teleportToCell(ServerPlayer player, int cx, int cz, ClaimMapConfig config) {
		return teleportToCell(player, false, cx, cz, config);
	}

	private static boolean doTeleportToCell(ServerPlayer player, boolean isNether, int cx, int cz, ClaimMapConfig config) {
		MinecraftServer srv = player.level().getServer();
		if (srv == null) return false;
		ServerLevel level = isNether ? srv.getLevel(net.minecraft.world.level.Level.NETHER) : srv.overworld();
		if (level == null) return false;
		int minX = cx * config.cellSize;
		int minZ = cz * config.cellSize;
		int margin = Math.min(2, Math.max(0, (config.cellSize - 2) / 2));
		int span = Math.max(1, config.cellSize - 2 * margin);
		int rx = minX + margin + (int) (Math.random() * span);
		int rz = minZ + margin + (int) (Math.random() * span);

		int minY = level.getMinY();
		int maxY = level.getMaxY();
		int targetY = -1;

		for (int y = Math.min(maxY - 2, 319); y >= minY + 1; y--) {
			BlockPos posBelow = new BlockPos(rx, y - 1, rz);
			BlockPos posFeet = new BlockPos(rx, y, rz);
			BlockPos posHead = new BlockPos(rx, y + 1, rz);

			var stateBelow = level.getBlockState(posBelow);
			var stateFeet = level.getBlockState(posFeet);
			var stateHead = level.getBlockState(posHead);

			if (stateBelow.isSolid() && !stateBelow.is(Blocks.LAVA)
					&& !stateBelow.is(Blocks.FIRE)
					&& !stateBelow.is(Blocks.MAGMA_BLOCK)
					&& !stateBelow.is(Blocks.CACTUS)) {
				if ((stateFeet.isAir() || !stateFeet.isSolid())
						&& (stateHead.isAir() || !stateHead.isSolid())
						&& !stateFeet.is(Blocks.LAVA)
						&& !stateHead.is(Blocks.LAVA)) {
					targetY = y;
					break;
				}
			}
		}

		if (targetY == -1) {
			targetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rx, rz) + 1;
			if (targetY < minY) targetY = 64;
		}

		player.teleportTo(level, rx + 0.5, targetY, rz + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
		player.sendSystemMessage(Component.literal("วาร์ปไปยังเขต " + cx + "," + cz + " พิกัด (" + rx + ", " + targetY + ", " + rz + ") สำเร็จ"));
		return true;
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

	private int runTpaGui(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) return 0;
		TpaGui.open(player, teleports);
		return 1;
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
