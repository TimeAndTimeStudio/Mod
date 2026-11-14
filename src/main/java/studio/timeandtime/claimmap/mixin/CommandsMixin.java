package studio.timeandtime.claimmap.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;
import studio.timeandtime.claimmap.ClaimMap;

/**
 * Blocks every command for players that have not passed the login gate. The
 * gate itself runs in the configuration phase, so this is defence in depth.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

	@Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
	private void claimmap$gateCommands(CommandSourceStack source, String command, CallbackInfo ci) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return;
		ClaimMap mod = ClaimMap.instance();
		AuthGate gate = mod == null ? null : mod.authGate();
		if (gate == null || gate.isAuthed(player.getUUID())) return;

		player.sendSystemMessage(Component.literal("กรุณาเข้าสู่ระบบก่อน"));
		ci.cancel();
	}
}
