package studio.timeandtime.timesmpclient.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ContainerScreen.class)
public abstract class ContainerScreenMixin {

	@Shadow
	@Final
	private int containerRows;

	@Unique
	private static final Identifier GUI_AUCTION = Identifier.fromNamespaceAndPath("timesmp", "textures/gui/gui_auction.png");
	@Unique
	private static final Identifier GUI_SELL = Identifier.fromNamespaceAndPath("timesmp", "textures/gui/gui_sell.png");
	@Unique
	private static final Identifier GUI_TPA = Identifier.fromNamespaceAndPath("timesmp", "textures/gui/gui_tpa.png");
	@Unique
	private static final Identifier GUI_CLAIM = Identifier.fromNamespaceAndPath("timesmp", "textures/gui/gui_claim.png");
	@Unique
	private static final Identifier GUI_CUSTOM = Identifier.fromNamespaceAndPath("timesmp", "textures/gui/gui_custom.png");

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void onExtractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ContainerScreen screen = (ContainerScreen) (Object) this;
		Component titleComp = screen.getTitle();
		if (titleComp == null) return;

		String title = titleComp.getString();
		Identifier customTexture = null;

		if (title.contains("ตลาด") || title.contains("ประมูล") || title.contains("AH") || title.contains("ah") || title.contains("เสนอราคา")) {
			customTexture = GUI_AUCTION;
		} else if (title.contains("ขาย") || title.contains("Sell") || title.contains("ราคา")) {
			customTexture = GUI_SELL;
		} else if (title.contains("เทเลพอร์ต") || title.contains("TPA") || title.contains("วาร์ป") || title.contains("บ้าน") || title.contains("Homes")) {
			customTexture = GUI_TPA;
		} else if (title.contains("แผนที่") || title.contains("เขต") || title.contains("Claim") || title.contains("คู่มือ") || title.contains("Help")) {
			customTexture = GUI_CLAIM;
		} else if (title.contains("Time SMP") || title.contains("กล่องรับของ") || title.contains("mail") || title.startsWith("§0")) {
			// Other Time SMP custom GUIs
			customTexture = GUI_CUSTOM;
		}

		// Only apply custom theme if it is a 6-row Time SMP menu matching one of our layouts
		if (customTexture != null && this.containerRows == 6) {
			int left = (screen.width - 176) / 2;
			int top = (screen.height - (114 + this.containerRows * 18)) / 2;

			// Draw our custom UI layout texture
			graphics.blit(RenderPipelines.GUI_TEXTURED, customTexture, left, top, 0.0f, 0.0f, 176, 222, 256, 256);
			ci.cancel();
		}
	}
}
