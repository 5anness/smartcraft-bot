package de.hannes.smartcraft.mixin;

import de.hannes.smartcraft.perception.Perception;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	/** Fabric API hat kein Event für „Block gesetzt“, deshalb der Hook direkt nach dem Setzen. */
	@Inject(method = "place", at = @At("RETURN"))
	private void smartcraft$reportPlacedBlock(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (cir.getReturnValue().consumesAction() && !context.getLevel().isClientSide()) {
			Perception.onBlockPlaced(context.getLevel(), context.getPlayer(), context.getClickedPos());
		}
	}
}
