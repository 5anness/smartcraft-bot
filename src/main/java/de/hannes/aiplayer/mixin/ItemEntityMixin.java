package de.hannes.aiplayer.mixin;

import de.hannes.aiplayer.perception.Perception;
import de.hannes.aiplayer.skill.Inv;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Shadow
	public abstract ItemStack getItem();

	@Shadow
	public abstract Entity getOwner();

	@Unique
	private int aiplayer$countBefore;
	@Unique
	private String aiplayer$itemBefore;

	/** Fabric API hat kein Event für „Item aufgehoben“: vorher Anzahl merken, danach vergleichen. */
	@Inject(method = "playerTouch", at = @At("HEAD"))
	private void aiplayer$beforePickup(Player player, CallbackInfo ci) {
		ItemStack stack = getItem();
		aiplayer$countBefore = stack.getCount();
		aiplayer$itemBefore = Inv.itemId(stack);
	}

	@Inject(method = "playerTouch", at = @At("RETURN"))
	private void aiplayer$afterPickup(Player player, CallbackInfo ci) {
		int picked = aiplayer$countBefore - getItem().getCount();
		if (picked > 0 && !player.level().isClientSide()) {
			Perception.onItemPickedUp(player, getOwner(), aiplayer$itemBefore, picked);
		}
	}
}
