package io.github.joddabodscripts.j2b.bridge.block;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.impl.DefaultModelData;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks the invisible vanilla state that stands in for a block the client draws with display
 * entities. The client takes collision and the aiming outline from this state, so it should have
 * about the same shape as the real block. A cutting board gets a closed trapdoor, a pot gets a
 * slab, and only blocks that really fill the cube get a barrier.
 */
final class StandIns {
	private static final int RES = 16;
	private static final BitSet FULL = new BitSet(RES * RES * RES);

	static {
		FULL.set(0, RES * RES * RES);
	}

	/**
	 * Types whose vanilla blocks do something on the client besides having a shape: draw a
	 * block entity, make smoke, burn, let players climb, or take up two blocks.
	 */
	private static final Set<String> UNSAFE_PREFIXES = Set.of(
			"FIRE", "BED", "HEAD", "CAMPFIRE", "SCAFFOLDING", "DOOR", "SHELF", "SCULK_SENSOR_ACTIVE");

	private static final Map<BlockModelType, BitSet> SHAPES = new EnumMap<>(BlockModelType.class);
	private static final Map<BlockModelType, BlockState> REQUESTED = new EnumMap<>(BlockModelType.class);
	private static boolean loaded;

	static BlockState forState(BlockState state) {
		load();
		boolean waterlogged = state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
		BitSet shape = occupancy(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));

		BlockModelType best = null;
		double bestScore = overlap(shape, FULL);
		for (Map.Entry<BlockModelType, BitSet> candidate : SHAPES.entrySet()) {
			if (candidate.getKey().name().endsWith("WATERLOGGED") != waterlogged) {
				continue;
			}
			double score = overlap(shape, candidate.getValue());
			if (score > bestScore + 1e-6) {
				best = candidate.getKey();
				bestScore = score;
			}
		}

		if (best != null) {
			BlockState standIn = request(best);
			if (standIn != null) {
				return standIn;
			}
		}
		return Blocks.BARRIER.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, waterlogged);
	}

	private static BlockState request(BlockModelType type) {
		if (REQUESTED.containsKey(type)) {
			return REQUESTED.get(type);
		}
		BlockState state = null;
		try {
			state = PolymerBlockResourceUtils.requestEmpty(type);
		} catch (RuntimeException e) {
			J2bBridge.LOGGER.debug("No empty {} state left", type, e);
		}
		REQUESTED.put(type, state);
		return state;
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		for (Map.Entry<BlockModelType, List<BlockState>> entry : DefaultModelData.USABLE_STATES.entrySet()) {
			String name = entry.getKey().name();
			if (entry.getValue().isEmpty() || UNSAFE_PREFIXES.stream().anyMatch(name::startsWith)) {
				continue;
			}
			BlockState sample = entry.getValue().getFirst();
			if (sample.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
				continue;
			}
			SHAPES.put(entry.getKey(), occupancy(sample.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
		}
	}

	/** Which of the block's 16x16x16 cells the shape covers. */
	private static BitSet occupancy(VoxelShape shape) {
		BitSet cells = new BitSet(RES * RES * RES);
		for (AABB box : shape.toAabbs()) {
			int x0 = cell(box.minX), x1 = cell(box.maxX);
			int y0 = cell(box.minY), y1 = cell(box.maxY);
			int z0 = cell(box.minZ), z1 = cell(box.maxZ);
			for (int x = x0; x < x1; x++) {
				for (int y = y0; y < y1; y++) {
					for (int z = z0; z < z1; z++) {
						cells.set((x * RES + y) * RES + z);
					}
				}
			}
		}
		return cells;
	}

	private static int cell(double coordinate) {
		return Math.clamp(Math.round(coordinate * RES), 0, RES);
	}

	/** Intersection over union of two shapes, from 0 (nothing shared) to 1 (identical). */
	private static double overlap(BitSet a, BitSet b) {
		BitSet both = (BitSet) a.clone();
		both.and(b);
		BitSet either = (BitSet) a.clone();
		either.or(b);
		return either.isEmpty() ? 0 : (double) both.cardinality() / either.cardinality();
	}

	private StandIns() {
	}
}
