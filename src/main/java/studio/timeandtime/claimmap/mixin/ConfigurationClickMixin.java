package studio.timeandtime.claimmap.mixin;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;

/**
 * Routes dialog button clicks received during the configuration phase to the
 * login gate. Play-phase custom click actions are left untouched.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ConfigurationClickMixin {

	@Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
	private void claimmap$configurationClick(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
		if ((Object) this instanceof ServerConfigurationPacketListenerImpl listener) {
			AuthGate.configurationClick(listener, packet.id(), packet.payload());
			ci.cancel();
		}
	}
}
