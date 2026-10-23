package io.github.joddabodscripts.j2b.bridge.block;

import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets vanilla clients edit a modded sign's text.
 *
 * <p>The client only opens its sign editor over a sign block entity of its own, and a modded sign
 * is display entities over an invisible block. So for the length of the edit, the editing player
 * alone sees a vanilla oak sign in that spot. Once the new text comes back, the real block is
 * sent again.
 */
public final class SignEditing {
	/** Ticks to wait, so the placement confirmation and the chunk's own update go out first. */
	private static final int DELAY = 2;
	private static final List<Pending> PENDING = new ArrayList<>();

	private record Pending(int tick, Runnable task) {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			List<Pending> due = PENDING.stream().filter(p -> p.tick() <= server.getTickCount()).toList();
			PENDING.removeAll(due);
			due.forEach(p -> p.task().run());
		});
	}

	/**
	 * Returns true when the editor should open a little later instead. Right after a player places
	 * a sign, the client holds back block updates for that spot until the server confirms the
	 * placement at the end of the tick, so the stand-in sign can't arrive in time.
	 */
	public static boolean deferEdit(ServerPlayer player, SignBlockEntity sign, SignTextSlot slot) {
		BlockState state = sign.getBlockState();
		Block vanilla = vanillaSignFor(state.getBlock());
		if (vanilla == null || !BridgedMods.isBridged(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())) {
			return false;
		}
		PENDING.add(new Pending(player.level().getServer().getTickCount() + DELAY, () -> {
			if (sign.isRemoved() || player.isRemoved()) {
				return;
			}
			player.connection.send(new ClientboundBlockUpdatePacket(sign.getBlockPos(), copyProperties(sign.getBlockState(), vanilla.defaultBlockState())));
			player.connection.send(sign.getUpdatePacket());
			// What openTextEdit sends, minus its block update, which would put the real block back.
			player.connection.send(new ClientboundOpenSignEditorPacket(sign.getBlockPos(), slot));
		}));
		return true;
	}

	public static void afterEdit(ServerPlayer player, BlockPos pos) {
		Block block = player.level().getBlockState(pos).getBlock();
		if (vanillaSignFor(block) != null && BridgedMods.isBridged(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) {
			player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
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

	private SignEditing() {
	}
}
