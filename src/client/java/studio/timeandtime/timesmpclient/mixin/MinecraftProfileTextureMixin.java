package studio.timeandtime.timesmpclient.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * authlib's {@code getHash} parses a URL path and throws
 * "Invalid profile texture url" for {@code data:} URIs, before the texture
 * downloader can run. Give embedded skins a stable SHA-1 cache key instead.
 */
@Mixin(MinecraftProfileTexture.class)
public abstract class MinecraftProfileTextureMixin {
	@Inject(method = "getHash", at = @At("HEAD"), cancellable = true, remap = false)
	private void timesmp$hashDataUri(CallbackInfoReturnable<String> cir) {
		String url = ((MinecraftProfileTexture) (Object) this).getUrl();
		if (url == null || !url.startsWith("data:")) return;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1")
					.digest(url.getBytes(StandardCharsets.UTF_8));
			cir.setReturnValue(HexFormat.of().formatHex(digest));
		} catch (Exception ignored) {
			cir.setReturnValue(Integer.toHexString(url.hashCode()));
		}
	}
}
