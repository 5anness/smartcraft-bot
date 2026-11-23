package de.hannes.smartcraft.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Private Setter der Anzeige-Entities. */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setBillboardConstraints")
	void smartcraft$setBillboardConstraints(Display.BillboardConstraints constraints);

	@Invoker("setTransformation")
	void smartcraft$setTransformation(Transformation transformation);
}
