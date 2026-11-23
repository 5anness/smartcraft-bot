package de.hannes.smartcraft.mixin;

import de.hannes.smartcraft.bot.BotManager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/**
	 * Bei Spielern rechnet normalerweise der Client die Bewegung und der Server übernimmt sie.
	 * Der KI-Spieler hat keinen Client, also muss der Server Schwerkraft und Bewegung selbst berechnen.
	 */
	@Inject(method = "isClientAuthoritative", at = @At("HEAD"), cancellable = true)
	private void smartcraft$serverSimulatesBot(CallbackInfoReturnable<Boolean> cir) {
		if (BotManager.isBot((Player) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
