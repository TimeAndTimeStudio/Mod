package studio.timeandtime.claimmap.mixin;

import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ResourcePackResponseMixin {

	@Inject(method = "handleResourcePackResponse", at = @At("HEAD"))
	private void claimmap$onResourcePackResponse(ServerboundResourcePackPacket packet, CallbackInfo ci) {
		if ((Object) this instanceof ServerConfigurationPacketListenerImpl listener) {
			AuthGate.onResourcePackResponse(listener, packet.action());
		}
	}
}
