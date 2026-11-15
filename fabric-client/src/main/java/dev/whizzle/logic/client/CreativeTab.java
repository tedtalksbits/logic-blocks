package dev.whizzle.logic.client;

import dev.whizzle.logic.model.ItemText;
import dev.whizzle.logic.model.Presets;
import dev.whizzle.logic.model.Program;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * A "Logic Blocks" creative tab: a blank Logic Block, then one programmed with each preset, so creative search finds
 * them. A Logic Block isn't an item type of its own (the plugin makes it from a daylight detector plus data), so each
 * stack here has exactly the components the plugin's {@code LogicItem.create} gives one: custom_name "Logic Block"
 * (aqua, not italic), lore (grey, not italic, text from {@link ItemText}), and custom_data
 * {@code {PublicBukkitValues:{"logicblocks:logic_block":1b, "logicblocks:program":[B;...]}}} (the program only when
 * there is one), and custom_model_data strings ["logicblocks:logic_block"] (what the mod's item definition draws by). The server then treats a stack from the tab as a real Logic Block, and it stacks with crafted ones.
 * Keep this and LogicItem in step: the self-test's "creative tab" step builds this same JSON and checks both.
 */
public final class CreativeTab {
	private CreativeTab() {}

	/** The item's custom model data string (LogicItem.MODEL): the mod's item definition draws the Logic Block by it. */
	public static final String MODEL = "logicblocks:logic_block";

	public static ItemStack stack(Program program) {
		ItemStack s = new ItemStack(Items.DAYLIGHT_DETECTOR);
		s.set(DataComponents.CUSTOM_NAME, Component.literal(ItemText.NAME).withStyle(st -> st.withColor(ChatFormatting.AQUA).withItalic(false)));
		List<Component> lore = new ArrayList<>();
		for (String line : ItemText.lore(program)) lore.add(Component.literal(line).withStyle(st -> st.withColor(ChatFormatting.GRAY).withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(lore));
		CompoundTag values = new CompoundTag();   // Bukkit's persistent data lives under PublicBukkitValues
		values.putByte("logicblocks:logic_block", (byte) 1);
		if (program != null && !program.equals(Program.EMPTY)) values.putByteArray("logicblocks:program", program.encode());
		CompoundTag root = new CompoundTag();
		root.put("PublicBukkitValues", values);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(MODEL), List.of()));
		return s;
	}

	public static void register() {
		CreativeModeTab tab = FabricCreativeModeTab.builder()
			.title(Component.literal("Logic Blocks"))
			.icon(() -> stack(null))
			.displayItems((params, output) -> {
				output.accept(stack(null));
				for (Presets.Preset p : Presets.all()) output.accept(stack(p.program()));
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("logicblocks", "logic_blocks"), tab);
	}
}
