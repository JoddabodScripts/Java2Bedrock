package io.github.joddabodscripts.j2b.bridge.block;

import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Consumer;

/**
 * Lays out a sign's text the way the client's sign renderer does, one text display per line.
 *
 * <p>A modded sign shows to vanilla clients as display entities, so the client has no sign block
 * entity there to draw the text. The numbers here match {@code StandingSignRenderer} and
 * {@code HangingSignRenderer}.
 */
final class SignTexts {
	/** Text display units per block, the inverse of the 0.025 scale the client applies. */
	private static final float DISPLAY_UNITS = 40;
	/** A one-line text display draws its line with the top this far above its origin. */
	private static final float DISPLAY_LINE_TOP = 9;

	record Line(Component text, Matrix4f pose, boolean glowing) {
	}

	static void collect(SignBlockEntity sign, Consumer<Line> out) {
		BlockState state = sign.getBlockState();
		for (SignTextSlot slot : SignTextSlot.values()) {
			boolean front = slot == SignTextSlot.FRONT;
			Matrix4f base = transformation(state, front);
			if (base == null) {
				return;
			}
			SignText text = sign.getText(slot);
			List<Component> messages = text.getMessages(false);
			int lineHeight = sign.getTextLineHeight();
			int color = text.hasGlowingText() ? text.getColor().getTextColor() : darkColor(text);
			for (int i = 0; i < messages.size(); i++) {
				Component message = messages.get(i);
				if (message.getString().isBlank()) {
					continue;
				}
				// The renderer draws line i at y = i * lineHeight - 2 * lineHeight, centred on x.
				// A text display centres its line too, one pixel to the right.
				Matrix4f pose = new Matrix4f(base)
						.translate(-1, i * lineHeight - 2 * lineHeight + DISPLAY_LINE_TOP, 0)
						.scale(DISPLAY_UNITS, -DISPLAY_UNITS, DISPLAY_UNITS);
				out.accept(new Line(Component.empty().withColor(color).append(message), pose, text.hasGlowingText()));
			}
		}
	}

	/** The renderer's matrix for one side of the sign, from the block corner to text pixels. */
	private static Matrix4f transformation(BlockState state, boolean front) {
		Matrix4f result;
		float scale;
		if (state.getBlock() instanceof WallHangingSignBlock || state.getBlock() instanceof CeilingHangingSignBlock) {
			float angle = state.getBlock() instanceof WallHangingSignBlock
					? state.getValue(WallHangingSignBlock.FACING).toYRot()
					: RotationSegment.convertToDegrees(state.getValue(CeilingHangingSignBlock.ROTATION));
			result = new Matrix4f().translate(0.5f, 0.9375f, 0.5f).rotateY(rad(-angle)).translate(0, -0.3125f, 0);
			if (!front) {
				result.rotateY(rad(180));
			}
			result.translate(0, -0.32f, 0.073f);
			scale = 0.0140625f;
		} else if (state.getBlock() instanceof WallSignBlock || state.getBlock() instanceof StandingSignBlock) {
			boolean wall = state.getBlock() instanceof WallSignBlock;
			float angle = wall
					? state.getValue(WallSignBlock.FACING).toYRot()
					: RotationSegment.convertToDegrees(state.getValue(StandingSignBlock.ROTATION));
			result = new Matrix4f().translate(0.5f, 0.5f, 0.5f).rotateY(rad(-angle));
			if (wall) {
				result.translate(0, -0.3125f, -0.4375f);
			}
			if (!front) {
				result.rotateY(rad(180));
			}
			result.translate(0, 0.33333334f, 0.046666667f);
			scale = 0.010416667f;
		} else {
			return null;
		}
		return result.scale(scale, -scale, scale);
	}

	private static int darkColor(SignText text) {
		int color = text.getColor().getTextColor();
		return color == DyeColor.BLACK.getTextColor() && text.hasGlowingText() ? -988212 : ARGB.scaleRGB(color, 0.4f);
	}

	private static float rad(float degrees) {
		return (float) Math.toRadians(degrees);
	}

	private SignTexts() {
	}
}
