package io.github.joddabodscripts.j2b.demo.block;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import io.github.joddabodscripts.j2b.demo.J2bDemo;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A plain full block with its own texture.
 *
 * <p>Vanilla clients only know vanilla block states, so Polymer lends us an unused
 * note block state and points it at our model in the server resource pack.
 */
public class CrystalBlock extends Block implements PolymerTexturedBlock {
	private static final BlockState CLIENT_STATE = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.FULL_BLOCK, PolymerBlockModel.of(J2bDemo.id("block/crystal_block")));

	public CrystalBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
		return CLIENT_STATE;
	}
}
