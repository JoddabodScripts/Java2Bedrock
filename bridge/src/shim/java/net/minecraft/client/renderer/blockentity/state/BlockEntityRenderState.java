package net.minecraft.client.renderer.blockentity.state;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;

/** Server-side stand-in, written for the bridge. */
public class BlockEntityRenderState {
	public BlockPos blockPos = BlockPos.ZERO;
	public BlockEntityType<?> blockEntityType;
	public int lightCoords;
	public ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress;

	public BlockEntityRenderState() {
	}

	public static void extractBase(BlockEntity blockEntity, BlockEntityRenderState state,
			ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		state.blockPos = blockEntity.getBlockPos();
		state.blockEntityType = blockEntity.getType();
		state.lightCoords = blockEntity.getLevel() != null ? LightCoordsUtil.getLightCoords(blockEntity.getLevel(), blockEntity.getBlockPos()) : 0;
		state.breakProgress = breakProgress;
	}
}
