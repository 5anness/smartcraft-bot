package de.hannes.smartcraft.nav;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Türen und Tore, die ein Spieler mit der Hand öffnet: Holztüren und Zauntore. */
public final class Doors {
	private Doors() {
	}

	public static boolean isOpenable(BlockState state) {
		return (state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.FENCE_GATES)) && state.hasProperty(BlockStateProperties.OPEN);
	}

	public static boolean isClosed(BlockState state) {
		return isOpenable(state) && !state.getValue(BlockStateProperties.OPEN);
	}
}
