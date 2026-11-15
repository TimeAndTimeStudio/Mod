package studio.timeandtime.claimmap.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;
import studio.timeandtime.claimmap.ClaimMap;
import studio.timeandtime.claimmap.CommandFilter;

import java.util.HashMap;
import java.util.Map;

/**
 * Filters commands sent to regular players so they only see mod commands
 * and whisper commands (/msg, /tell, /w). Admins/OPs retain full command access.
 * Also gates unauthenticated players and prevents non-admins from executing forbidden commands.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

	@Shadow @Final
	private CommandDispatcher<CommandSourceStack> dispatcher;

	@Shadow @Final
	private static ClientboundCommandsPacket.NodeInspector<CommandSourceStack> COMMAND_NODE_INSPECTOR;

	@Shadow
	private static <S> void fillUsableCommands(CommandNode<S> root, CommandNode<S> target, S source, Map<CommandNode<S>, CommandNode<S>> map) {
		throw new AssertionError();
	}

	@Inject(method = "sendCommands", at = @At("HEAD"), cancellable = true)
	private void claimmap$filterCommands(ServerPlayer player, CallbackInfo ci) {
		if (player == null) return;
		ClaimMap mod = ClaimMap.instance();
		boolean isAdmin = player.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
				|| (mod != null && mod.config() != null && mod.config().isAdmin(player.getScoreboardName()));
		if (isAdmin) {
			return; // Admins receive all vanilla and mod commands
		}

		Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> map = new HashMap<>();
		RootCommandNode<CommandSourceStack> fullRoot = new RootCommandNode<>();
		map.put(this.dispatcher.getRoot(), fullRoot);
		fillUsableCommands(this.dispatcher.getRoot(), fullRoot, player.createCommandSourceStack(), map);

		RootCommandNode<CommandSourceStack> filteredRoot = new RootCommandNode<>();
		for (CommandNode<CommandSourceStack> child : fullRoot.getChildren()) {
			if (CommandFilter.isAllowedCommand(child.getName(), mod)) {
				filteredRoot.addChild(child);
			}
		}

		player.connection.send(new ClientboundCommandsPacket(filteredRoot, COMMAND_NODE_INSPECTOR));
		ci.cancel();
	}

	@Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
	private void claimmap$gateCommands(CommandSourceStack source, String command, CallbackInfo ci) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return;
		ClaimMap mod = ClaimMap.instance();
		AuthGate gate = mod == null ? null : mod.authGate();
		if (gate != null && !gate.isAuthed(player.getUUID())) {
			player.sendSystemMessage(Component.literal("กรุณาเข้าสู่ระบบก่อน"));
			ci.cancel();
			return;
		}

		boolean isAdmin = source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
				|| (mod != null && mod.config() != null && mod.config().isAdmin(player.getScoreboardName()));
		if (isAdmin) return;

		String trimmed = command.trim();
		if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
		int space = trimmed.indexOf(' ');
		String rootName = space > 0 ? trimmed.substring(0, space) : trimmed;
		if (!CommandFilter.isAllowedCommand(rootName, mod)) {
			player.sendSystemMessage(Component.literal("§cคำสั่งนี้ไม่อนุญาตสำหรับผู้เล่นทั่วไป"));
			ci.cancel();
		}
	}
}
