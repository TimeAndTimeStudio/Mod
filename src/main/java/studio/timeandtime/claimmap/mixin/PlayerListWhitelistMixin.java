package studio.timeandtime.claimmap.mixin;

import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.Scanner;

@Mixin(PlayerList.class)
public abstract class PlayerListWhitelistMixin {

	@Shadow
	@Final
	private UserWhiteList whitelist;

	@Inject(method = "isWhiteListed", at = @At("HEAD"), cancellable = true)
	private void claimmap$checkTWhiteList(NameAndId nameAndId, CallbackInfoReturnable<Boolean> cir) {
		if (nameAndId == null || nameAndId.name() == null) return;

		String incomingName = nameAndId.name();

		// Check against entries in whitelist case-insensitively or via exact twhite.txt
		for (UserWhiteListEntry entry : this.whitelist.getEntries()) {
			NameAndId user = entry.getUser();
			if (user != null && user.name() != null) {
				if (user.name().equalsIgnoreCase(incomingName)) {
					cir.setReturnValue(true);
					return;
				}
			}
		}

		// Also check config/timesmp/twhite.txt directly
		File file = new File("config/timesmp/twhite.txt");
		if (file.exists()) {
			try (Scanner scanner = new Scanner(file)) {
				while (scanner.hasNextLine()) {
					String line = scanner.nextLine().trim();
					if (!line.isEmpty() && line.equalsIgnoreCase(incomingName)) {
						cir.setReturnValue(true);
						return;
					}
				}
			} catch (Exception ignored) {}
		}
	}
}
