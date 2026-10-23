package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.PoseStack;

/** Server-side stand-in, written for the bridge. */
public class ModelFeatureRenderer {
	public record CrumblingOverlay(int progress, PoseStack.Pose cameraPose) {
	}
}
