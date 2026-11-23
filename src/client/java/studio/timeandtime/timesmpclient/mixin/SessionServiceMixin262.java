package studio.timeandtime.timesmpclient.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import studio.timeandtime.timesmpclient.skin.CustomTextures;

/** Minecraft 26.2 / authlib 9. The same fix for the older implementation. */
@Pseudo
@Mixin(targets = "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService", remap = false)
public abstract class SessionServiceMixin262 {
	@Inject(method = "unpackTextures", at = @At("HEAD"), cancellable = true, remap = false)
	private void timesmp$unpackCustom(Property property,
			CallbackInfoReturnable<MinecraftProfileTextures> cir) {
		MinecraftProfileTextures textures = CustomTextures.unpack(property);
		if (textures != null) cir.setReturnValue(textures);
	}
}
