package de.hannes.smartcraft.mixin;

import net.minecraft.core.HolderSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Zugriff auf die Zutatenliste eines Rezepts, um Tags wie {@code #planks} beim Namen nennen zu können. */
@Mixin(Ingredient.class)
public interface IngredientAccessor {
	@Accessor("values")
	HolderSet<Item> aiplayer$values();
}
