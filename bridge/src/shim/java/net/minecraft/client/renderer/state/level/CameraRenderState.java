package net.minecraft.client.renderer.state.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Server-side stand-in, written for the bridge. */
public class CameraRenderState {
	public BlockPos blockPos = BlockPos.ZERO;
	public Vec3 pos = Vec3.ZERO;
	public float xRot;
	public float yRot;
	public boolean initialized = true;
	public boolean isPanoramicMode;
	public boolean isFrustumCaptured;
	public boolean isFirstPerson = true;
	public boolean smartCull;
	public Quaternionf orientation = new Quaternionf();
	public float cameraEntityPartialTicks;
	public float hudFov = 70;
	public float depthFar = 256;

	public CameraRenderState() {
	}
}
