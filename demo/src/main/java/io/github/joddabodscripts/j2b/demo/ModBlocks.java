package io.github.joddabodscripts.j2b.demo;

import io.github.joddabodscripts.j2b.demo.block.CrystalBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

public final class ModBlocks {
	public static final Block CRYSTAL_BLOCK = register("crystal_block", CrystalBlock::new,
			BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.AMETHYST).requiresCorrectToolForDrops());

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, J2bDemo.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
	}

	static void register() {
	}

	private ModBlocks() {
	}
}
