package io.github.joddabodscripts.j2b.demo;

import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import io.github.joddabodscripts.j2b.demo.item.SparkWandItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.function.Function;

public final class ModItems {
	public static final Item SPARK_WAND = register("spark_wand", SparkWandItem::new, new Item.Properties().stacksTo(1));
	public static final Item CRYSTAL_BLOCK = registerBlockItem(ModBlocks.CRYSTAL_BLOCK);

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, J2bDemo.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	private static Item registerBlockItem(Block block) {
		String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return register(name, properties -> new PolymerBlockItem(block, properties), new Item.Properties().useBlockDescriptionPrefix());
	}

	static void register() {
		// Polymer creative tabs show up for vanilla clients, a plain Fabric tab would not.
		PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(J2bDemo.id("items"), CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
				.title(Component.translatable("itemGroup.j2bdemo.items"))
				.icon(() -> new ItemStack(SPARK_WAND))
				.displayItems((params, output) -> {
					output.accept(SPARK_WAND);
					output.accept(CRYSTAL_BLOCK);
				})
				.build());
	}

	private ModItems() {
	}
}
