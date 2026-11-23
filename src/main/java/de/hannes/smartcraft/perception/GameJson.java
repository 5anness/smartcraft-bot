package de.hannes.smartcraft.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Übersetzt Spielobjekte in die knappe JSON-Form des Protokolls. */
public final class GameJson {
	private GameJson() {
	}

	/** Position als {@code [x, y, z]}. */
	public static JsonArray pos(BlockPos pos) {
		return ints(pos.getX(), pos.getY(), pos.getZ());
	}

	/** Gegenstück zu {@link #pos}: {@code [x,y,z]} als Blockposition. */
	public static BlockPos toBlockPos(JsonArray xyz) {
		return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
	}

	public static JsonArray ints(int a, int b, int c) {
		JsonArray json = new JsonArray();
		json.add(a);
		json.add(b);
		json.add(c);
		return json;
	}

	/** Item-Stapel als {@code {"id": ..., "count": ...}}, eine leere Hand als {@code null}. */
	public static JsonElement stack(ItemStack stack) {
		if (stack.isEmpty()) {
			return JsonNull.INSTANCE;
		}
		return stack(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
	}

	public static JsonObject stack(String id, int count) {
		JsonObject json = new JsonObject();
		json.addProperty("id", id);
		json.addProperty("count", count);
		return json;
	}

	public static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	public static String entityType(Entity entity) {
		return EntityType.getKey(entity.getType()).toString();
	}

	/** Auf eine Nachkommastelle gerundet; halbe Herzen bleiben sichtbar, Rauschen nicht. */
	public static double round1(double value) {
		return Math.round(value * 10.0) / 10.0;
	}
}
