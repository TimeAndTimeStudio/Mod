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

/**
 * In-game commands:
 * <ul>
 *   <li>{@code /pass <password>}      - set the password used to log in on the web page</li>
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

	public ClaimCommands(RegionManager regions, LinkManager links, AccountManager accounts, ClaimMapConfig config) {
		this.regions = regions;
		this.links = links;
		this.accounts = accounts;
		this.config = config;
	}

	public void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			dispatcher.register(Commands.literal("pass").requires(src -> src.getPlayer() != null)
					.then(Commands.argument("password", StringArgumentType.string())
							.executes(ctx -> runPass(ctx.getSource(), StringArgumentType.getString(ctx, "password")))));

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
		if (!accounts.setPassword(uuid, name, password)) {
			player.sendSystemMessage(Component.literal("Invalid password: use 4-64 characters and not include spaces."));
			return 1;
		}
		player.sendSystemMessage(Component.literal("Password set for " + name
				+ ". Log in on the claim map web page with this name + password."));
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
