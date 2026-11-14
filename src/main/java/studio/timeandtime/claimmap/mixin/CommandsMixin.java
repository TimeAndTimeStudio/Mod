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

import java.util.Locale;

/**
 * Blocks every command for players that have not passed the login gate, except
 * {@code /login} and {@code /register} (PHASES.md 3.3).
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

		String head = command == null ? "" : command.trim();
		if (head.startsWith("/")) head = head.substring(1);
		int space = head.indexOf(' ');
		String name = (space < 0 ? head : head.substring(0, space)).toLowerCase(Locale.ROOT);
		if (name.equals("login") || name.equals("register")) return;

		player.sendSystemMessage(Component.literal(
				"กรุณาเข้าสู่ระบบก่อน: /login <รหัสผ่าน> หรือ /register <รหัสใหม่> <ยืนยันรหัส>"));
		ci.cancel();
	}
}
