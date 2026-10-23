package io.github.joddabodscripts.j2b.bridge.compat;

import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;
import vectorwing.farmersdelight.common.block.AbstractStoveBlock;
import vectorwing.farmersdelight.common.block.CuttingBoardBlock;
import vectorwing.farmersdelight.common.block.SkilletBlock;
import vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity;
import vectorwing.farmersdelight.common.block.entity.StoveBlockEntity;
import vectorwing.farmersdelight.common.tag.ModTags;

/**
 * Farmer's Delight's block entity renderers, ported to {@link BlockEntityVisuals}. Each transform
 * is copied from the matching renderer in {@code vectorwing.farmersdelight.client.renderer}.
 */
public final class FarmersDelightVisuals {
	public static void register() {
		BlockEntityVisuals.<CuttingBoardBlockEntity>register(id("cutting_board"), (board, out) -> {
			ItemStack stack = board.getStoredItem();
			if (stack.isEmpty()) {
				return;
			}
			Direction direction = board.getBlockState().getValue(CuttingBoardBlock.FACING).getOpposite();
			float yaw = -direction.toYRot();
			if (board.isItemCarvingBoard()) {
				float angle = stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.HOES) ? 225
						: stack.getItem() instanceof TridentItem ? 135 : 180;
				out.accept(part(stack, new Matrix4f().translate(0.5f, 0.23f, 0.5f)
						.rotateY(rad(yaw + 180)).rotateZ(rad(angle)).scale(0.6f)));
				return;
			}
			boolean block = stack.getItem() instanceof BlockItem && !stack.is(ModTags.Items.FLAT_ON_CUTTING_BOARD);
			for (int i = 0; i < modelCount(stack); i++) {
				Matrix4f pose = block
						? new Matrix4f().translate(0.5f, 0.27f + 0.03f * (i + 1), 0.5f).rotateY(rad(yaw)).scale(0.8f)
						: new Matrix4f().translate(0.5f, 0.08f + 0.03f * (i + 1), 0.5f).rotateY(rad(yaw)).rotateX(rad(90)).scale(0.6f);
				out.accept(part(stack, pose));
			}
		});

		BlockEntityVisuals.<StoveBlockEntity>register(id("stove"), (stove, out) -> {
			Direction direction = stove.getBlockState().getValue(AbstractStoveBlock.FACING).getOpposite();
			for (int i = 0; i < 6; i++) {
				ItemStack stack = stove.getItems().getStackInSlot(i);
				if (stack.isEmpty()) {
					continue;
				}
				Vec2 offset = stove.getStoveItemOffset(i);
				out.accept(part(stack, new Matrix4f().translate(0.5f, 1.02f, 0.5f)
						.rotateY(rad(-direction.toYRot())).rotateX(rad(90))
						.translate(offset.x, offset.y, 0).scale(0.375f)));
			}
		});

		BlockEntityVisuals.<SkilletBlockEntity>register(id("skillet"), (skillet, out) -> {
			ItemStack stack = skillet.getStoredStack();
			if (stack.isEmpty()) {
				return;
			}
			Direction direction = skillet.getBlockState().getValue(SkilletBlock.FACING);
			for (int i = 0; i < modelCount(stack); i++) {
				out.accept(part(stack, new Matrix4f().translate(0.5f, 0.1f + 0.03f * (i + 1), 0.5f)
						.rotateY(rad(-direction.toYRot())).rotateX(rad(90)).scale(0.5f)));
			}
		});
	}

	/** Same stack height rule as the renderers: one model, plus up to four more for a full stack. */
	private static int modelCount(ItemStack stack) {
		return stack.getCount() > 1 ? 1 + Mth.ceil((float) stack.getCount() / stack.getMaxStackSize() * 4) : 1;
	}

	private static ItemPart part(ItemStack stack, Matrix4f pose) {
		return new ItemPart(stack, pose, ItemDisplayContext.FIXED);
	}

	private static float rad(float degrees) {
		return (float) Math.toRadians(degrees);
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("farmersdelight", path);
	}

	private FarmersDelightVisuals() {
	}
}
