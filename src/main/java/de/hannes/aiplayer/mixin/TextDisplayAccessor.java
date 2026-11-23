package de.hannes.aiplayer.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Private Setter der Textanzeige (für Herzen und Hunger über dem Bot). */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
	@Invoker("setText")
	void aiplayer$setText(Component text);

	@Invoker("setBackgroundColor")
	void aiplayer$setBackgroundColor(int color);
}
