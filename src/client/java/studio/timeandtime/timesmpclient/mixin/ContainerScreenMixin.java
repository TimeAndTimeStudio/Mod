package studio.timeandtime.timesmpclient.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import studio.timeandtime.timesmpclient.ui.TimesmpUi;

/**
 * Draws a TimeSMP themed top for its own chest menus and leaves the player
 * inventory half exactly vanilla (DeluxeMenus/ItemsAdder style).
 */
@Mixin(ContainerScreen.class)
public abstract class ContainerScreenMixin {
	@Shadow
	@Final
	private int containerRows;

	@Unique
	private static final Identifier timesmp$VANILLA_CHEST =
			Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void timesmp$topBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo ci) {
		if (containerRows != 3 && containerRows != 6) return;

		ContainerScreen screen = (ContainerScreen) (Object) this;
		String key = TimesmpUi.keyFor(screen.getTitle().getString(), containerRows);
		if (key == null) return;

		Identifier texture = Identifier.fromNamespaceAndPath("timesmp", "textures/gui/" + key + ".png");
		int left = (screen.width - 176) / 2;
		int top = (screen.height - (114 + containerRows * 18)) / 2;
		int topHeight = containerRows * 18 + 17;

		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, left, top,
				0.0F, 0.0F, 176, topHeight, 256, 256);
		// The player half of generic_54 starts at v = 126; leave it untouched.
		graphics.blit(RenderPipelines.GUI_TEXTURED, timesmp$VANILLA_CHEST,
				left, top + topHeight, 0.0F, 126.0F, 176, 96, 256, 256);
		ci.cancel();
	}
}