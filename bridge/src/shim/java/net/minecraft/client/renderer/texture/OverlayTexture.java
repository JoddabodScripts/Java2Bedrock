package net.minecraft.client.renderer.texture;

/** Server-side stand-in, written for the bridge. Only the packing helpers. */
public class OverlayTexture {
	public static final int NO_WHITE_U = 0;
	public static final int RED_OVERLAY_V = 3;
	public static final int WHITE_OVERLAY_V = 10;
	public static final int NO_OVERLAY = pack(NO_WHITE_U, WHITE_OVERLAY_V);

	public static int u(float whiteOverlayProgress) {
		return (int) (whiteOverlayProgress * 15.0F);
	}

	public static int v(boolean hurtOverlay) {
		return hurtOverlay ? RED_OVERLAY_V : WHITE_OVERLAY_V;
	}

	public static int pack(int u, int v) {
		return u | v << 16;
	}

	public static int pack(float whiteOverlayProgress, boolean hurtOverlay) {
		return pack(u(whiteOverlayProgress), v(hurtOverlay));
	}
}
