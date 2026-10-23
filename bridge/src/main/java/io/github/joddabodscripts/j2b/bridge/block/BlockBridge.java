package io.github.joddabodscripts.j2b.bridge.block;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.MultiPolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.block.BlockModels.ModelRef;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks a vanilla block state for every state of a modded block.
 *
 * <p>Where the shape allows it, the bridge borrows an unused vanilla state (a note block for
 * full cubes, a sapling or tripwire for plants) and points it at the mod's own model in the
 * resource pack, so the client renders it like any block. Anything else becomes an invisible
 * stand-in with the right collision plus a display entity that draws the model.
 */
public final class BlockBridge {
	/** Block models that display entities draw, so the resource pack can give each an item model. */
	static final Set<Identifier> DISPLAYED_MODELS = new HashSet<>();
	private static final Map<String, BlockState> REQUESTED = new HashMap<>();
	private static int requested;
	private static int displayed;

	public static void bridge(Block block) {
		Map<BlockState, BlockState> clientStates = new IdentityHashMap<>();

		// A sign has to stay a sign on the client, or players can't edit its text.
		Block sign = vanillaSignFor(block);
		if (sign != null) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				clientStates.put(state, copyProperties(state, sign.defaultBlockState()));
			}
			PolymerBlockUtils.registerOverlay(block, new Overlay(clientStates));
			return;
		}
		Map<BlockState, List<List<ModelRef>>> displays = new IdentityHashMap<>();

		for (BlockState state : block.getStateDefinition().getPossibleStates()) {
			List<List<ModelRef>> parts = BlockModels.forState(state);
			BlockState client = requestModelled(state, parts);
			if (client == null) {
				client = standIn(state);
				if (!parts.isEmpty()) {
					displays.put(state, parts);
					parts.forEach(part -> part.forEach(ref -> DISPLAYED_MODELS.add(ref.model())));
				}
				displayed++;
			} else {
				requested++;
			}
			clientStates.put(state, client);
		}

		PolymerBlockUtils.registerOverlay(block, new Overlay(clientStates));
		// Block entities get a holder too, in case a visual is registered for them.
		if (!displays.isEmpty() || block instanceof EntityBlock) {
			BlockWithElementHolder.registerOverlay(block, new ModelDisplayHolder.Factory(displays));
		}
	}

	private static @Nullable Block vanillaSignFor(Block block) {
		return switch (block) {
			case StandingSignBlock ignored -> Blocks.OAK_SIGN;
			case WallSignBlock ignored -> Blocks.OAK_WALL_SIGN;
			case CeilingHangingSignBlock ignored -> Blocks.OAK_HANGING_SIGN;
			case WallHangingSignBlock ignored -> Blocks.OAK_WALL_HANGING_SIGN;
			default -> null;
		};
	}

	/** Copies every property the two states share, such as facing or rotation. */
	private static BlockState copyProperties(BlockState from, BlockState to) {
		for (Property<?> property : from.getProperties()) {
			to = copyProperty(from, to, property);
		}
		return to;
	}

	private static <T extends Comparable<T>> BlockState copyProperty(BlockState from, BlockState to, Property<T> property) {
		return to.hasProperty(property) ? to.setValue(property, from.getValue(property)) : to;
	}

	/** Borrows a vanilla state that renders the model directly, or returns null if none fits. */
	private static @Nullable BlockState requestModelled(BlockState state, List<List<ModelRef>> parts) {
		if (parts.isEmpty()) {
			return null;
		}
		for (BlockModelType type : typesFor(state)) {
			String key = type + "|" + parts;
			BlockState cached = REQUESTED.get(key);
			if (cached != null) {
				return cached;
			}
			BlockState client = parts.size() == 1
					? PolymerBlockResourceUtils.requestBlock(type, toPolymer(parts.getFirst()))
					: PolymerBlockResourceUtils.requestBlock(type, toMultipart(parts));
			if (client != null) {
				REQUESTED.put(key, client);
				return client;
			}
		}
		return null;
	}

	/** Vanilla block families whose shape and collision match this state, best first. */
	private static List<BlockModelType> typesFor(BlockState state) {
		boolean waterlogged = state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
		var level = EmptyBlockGetter.INSTANCE;
		boolean fullCollision = state.isCollisionShapeFullBlock(level, BlockPos.ZERO);
		boolean noCollision = state.getCollisionShape(level, BlockPos.ZERO).isEmpty();

		if (fullCollision && state.canOcclude() && !waterlogged) {
			return List.of(BlockModelType.FULL_BLOCK);
		}
		if (noCollision && !waterlogged) {
			return List.of(BlockModelType.PLANT, BlockModelType.TRIPWIRE);
		}
		if (noCollision) {
			return List.of(BlockModelType.KELP, BlockModelType.PROPAGULE_WATERLOGGED);
		}
		return List.of();
	}

	/** An invisible client state with roughly the right collision, drawn over by display entities. */
	private static BlockState standIn(BlockState state) {
		boolean waterlogged = state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
		if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
			return waterlogged
					? PolymerBlockResourceUtils.requestEmpty(BlockModelType.KELP)
					: PolymerBlockResourceUtils.requestEmpty(BlockModelType.PLANT);
		}
		return Blocks.BARRIER.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, waterlogged);
	}

	private static PolymerBlockModel[] toPolymer(List<ModelRef> refs) {
		return refs.stream()
				.map(ref -> PolymerBlockModel.of(ref.model(), ref.x(), ref.y(), ref.uvLock(), ref.weight()))
				.toArray(PolymerBlockModel[]::new);
	}

	private static MultiPolymerBlockModel toMultipart(List<List<ModelRef>> parts) {
		MultiPolymerBlockModel multi = MultiPolymerBlockModel.of();
		for (List<ModelRef> part : parts) {
			multi.with(toPolymer(part));
		}
		return multi;
	}

	/** Called once every block has been bridged. */
	public static void finish() {
		PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
			for (Identifier model : DISPLAYED_MODELS) {
				builder.addStringData("assets/" + J2bBridge.MOD_ID + "/items/" + ModelDisplayHolder.itemModelPath(model) + ".json",
						"{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}}");
			}
		});
		J2bBridge.LOGGER.info("Blocks: {} states use borrowed vanilla states, {} use display entities", requested, displayed);
	}

	private record Overlay(Map<BlockState, BlockState> clientStates) implements PolymerTexturedBlock {
		@Override
		public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
			return this.clientStates.getOrDefault(state, Blocks.BARRIER.defaultBlockState());
		}
	}

	private BlockBridge() {
	}
}
