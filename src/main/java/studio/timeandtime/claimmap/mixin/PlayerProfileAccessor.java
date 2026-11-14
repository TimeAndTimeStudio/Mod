package studio.timeandtime.claimmap.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reads the game profile of a configuration-phase connection. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface PlayerProfileAccessor {
	@Invoker("playerProfile")
	GameProfile claimmap$profile();
}
