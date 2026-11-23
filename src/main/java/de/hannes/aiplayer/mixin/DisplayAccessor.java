package de.hannes.aiplayer.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Private Setter der Anzeige-Entities. */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setBillboardConstraints")
	void aiplayer$setBillboardConstraints(Display.BillboardConstraints constraints);

	@Invoker("setTransformation")
	void aiplayer$setTransformation(Transformation transformation);
}
