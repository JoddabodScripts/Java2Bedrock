package io.github.joddabodscripts.j2b.bridge.block;

import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals;
import io.github.joddabodscripts.j2b.bridge.block.BlockModels.ModelRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws what a vanilla client can't with item display entities: a block's own models, for states
 * no vanilla block shape fits, and the items a block entity renderer would draw on the block.
 */
public class ModelDisplayHolder extends ElementHolder {
	private final Map<BlockState, List<List<ModelRef>>> displays;
	private final List<ItemDisplayElement> modelElements = new ArrayList<>();
	private final List<ItemDisplayElement> partElements = new ArrayList<>();
	private final List<Matrix4f> partPoses = new ArrayList<>();
	private final List<TextDisplayElement> textElements = new ArrayList<>();
	private final List<Matrix4f> textPoses = new ArrayList<>();
	private @Nullable BlockState shown;

	ModelDisplayHolder(Map<BlockState, List<List<ModelRef>>> displays, BlockState initial) {
		this.displays = displays;
		show(initial);
	}

	/** Path of the generated item model that renders the given block model. */
	static String itemModelPath(Identifier model) {
		return "block/" + model.getNamespace() + "/" + model.getPath();
	}

	private void show(BlockState state) {
		if (state == this.shown) {
			return;
		}
		this.shown = state;
		this.modelElements.forEach(this::removeElement);
		this.modelElements.clear();

		List<List<ModelRef>> parts = this.displays.get(state);
		if (parts == null) {
			return;
		}
		for (List<ModelRef> part : parts) {
			// Weighted random variants are rare on custom blocks, the first one is good enough.
			ModelRef ref = part.getFirst();
			ItemStack stack = new ItemStack(Items.PAPER);
			stack.set(DataComponents.ITEM_MODEL, J2bBridge.id(itemModelPath(ref.model())));

			ItemDisplayElement element = new ItemDisplayElement(stack);
			element.setItemDisplayContext(ItemDisplayContext.NONE);
			// Same rotation the client applies to blockstate models, followed by a half turn that
			// cancels the one item displays always add.
			element.setLeftRotation(new Quaternionf()
					.rotateY((float) Math.toRadians(-ref.y()))
					.rotateX((float) Math.toRadians(-ref.x()))
					.rotateY((float) Math.PI));
			element.setViewRange(1.5f);
			this.modelElements.add(element);
			addElement(element);
		}
	}

	@Override
	protected void onTick() {
		if (getWatchingPlayers().isEmpty() || !(getAttachment() instanceof BlockAwareAttachment attachment)) {
			return;
		}
		BlockEntity blockEntity = attachment.getWorld().getBlockEntity(attachment.getBlockPos());
		BlockEntityVisuals.Visual<BlockEntity> visual = blockEntity == null ? null
				: BlockEntityVisuals.get(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
		List<BlockEntityVisuals.ItemPart> parts = new ArrayList<>();
		if (visual != null) {
			visual.collect(blockEntity, part -> {
				if (!part.stack().isEmpty()) {
					parts.add(part);
				}
			});
		}
		Brightness light = lightAt(attachment);
		showParts(parts, light);

		List<SignTexts.Line> lines = new ArrayList<>();
		if (blockEntity instanceof SignBlockEntity sign) {
			SignTexts.collect(sign, lines::add);
		}
		showText(lines, light);
	}

	private void showText(List<SignTexts.Line> lines, Brightness light) {
		while (this.textElements.size() > lines.size()) {
			removeElement(this.textElements.removeLast());
			this.textPoses.removeLast();
		}
		for (int i = 0; i < lines.size(); i++) {
			SignTexts.Line line = lines.get(i);
			Matrix4f pose = new Matrix4f().translate(-0.5f, -0.5f, -0.5f).mul(line.pose());
			Brightness lineLight = line.glowing() ? Brightness.FULL_BRIGHT : light;
			if (i < this.textElements.size()) {
				TextDisplayElement element = this.textElements.get(i);
				if (!element.getText().equals(line.text())) {
					element.setText(line.text());
				}
				if (!lineLight.equals(element.getBrightness())) {
					element.setBrightness(lineLight);
				}
				if (!this.textPoses.get(i).equals(pose)) {
					this.textPoses.set(i, pose);
					element.setTransformation(pose);
				}
			} else {
				TextDisplayElement element = new TextDisplayElement(line.text());
				element.setBackground(0);
				element.setLineWidth(1000);
				element.setViewRange(0.5f);
				element.setBrightness(lineLight);
				element.setTransformation(pose);
				this.textElements.add(element);
				this.textPoses.add(pose);
				addElement(element);
			}
		}
	}

	/**
	 * A display entity takes its light from the block it stands in, which is dark inside a full
	 * block like a stove. Use whichever is brighter, the block itself or the one above it.
	 */
	private static Brightness lightAt(BlockAwareAttachment attachment) {
		ServerLevel level = attachment.getWorld();
		BlockPos pos = attachment.getBlockPos();
		return new Brightness(
				Math.max(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.BLOCK, pos.above())),
				Math.max(level.getBrightness(LightLayer.SKY, pos), level.getBrightness(LightLayer.SKY, pos.above())));
	}

	/** Updates the part elements in place, so items that didn't change don't flicker. */
	private void showParts(List<BlockEntityVisuals.ItemPart> parts, Brightness light) {
		while (this.partElements.size() > parts.size()) {
			removeElement(this.partElements.removeLast());
			this.partPoses.removeLast();
		}
		for (int i = 0; i < parts.size(); i++) {
			BlockEntityVisuals.ItemPart part = parts.get(i);
			// The renderer's pose starts at the block corner, a display entity at the block centre.
			// The trailing half turn cancels the one item displays always add.
			Matrix4f pose = new Matrix4f().translate(-0.5f, -0.5f, -0.5f).mul(part.pose()).rotateY((float) Math.PI);
			ItemDisplayElement element;
			if (i < this.partElements.size()) {
				element = this.partElements.get(i);
				if (!ItemStack.isSameItemSameComponents(element.getItem(), part.stack())) {
					element.setItem(part.stack().copyWithCount(1));
				}
				if (!light.equals(element.getBrightness())) {
					element.setBrightness(light);
				}
				if (element.getItemDisplayContext() != part.context()) {
					element.setItemDisplayContext(part.context());
				}
				if (this.partPoses.get(i).equals(pose)) {
					continue;
				}
				this.partPoses.set(i, pose);
			} else {
				element = new ItemDisplayElement(part.stack().copyWithCount(1));
				element.setItemDisplayContext(part.context());
				element.setViewRange(0.75f);
				element.setBrightness(light);
				element.setTransformation(pose);
				this.partElements.add(element);
				this.partPoses.add(pose);
				addElement(element);
				continue;
			}
			element.setTransformation(pose);
		}
	}

	@Override
	public void notifyUpdate(HolderAttachment.UpdateType updateType) {
		super.notifyUpdate(updateType);
		if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE && getAttachment() instanceof BlockAwareAttachment attachment) {
			show(attachment.getBlockState());
		}
	}

	record Factory(Map<BlockState, List<List<ModelRef>>> displays) implements BlockWithElementHolder {
		@Override
		public ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
			return new ModelDisplayHolder(this.displays, initialBlockState);
		}

		@Override
		public boolean tickElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
			return true;
		}
	}
}
