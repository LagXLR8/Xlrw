package com.xlrw.schematic.parser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;

public class BlockStateParser {

    public static BlockState fromNbt(String blockName, CompoundTag properties) {
        ResourceLocation id = ResourceLocation.tryParse(blockName);
        if (id == null) {
            return Blocks.AIR.defaultBlockState();
        }

        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null || block == Blocks.AIR && !blockName.contains("air")) {
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = block.defaultBlockState();
        if (properties != null && !properties.isEmpty()) {
            for (String key : properties.getAllKeys()) {
                String valStr = properties.getString(key);
                state = applyProperty(state, key, valStr);
            }
        }
        return state;
    }

    public static BlockState fromString(String stateString) {
        if (stateString == null || stateString.isBlank()) {
            return Blocks.AIR.defaultBlockState();
        }

        stateString = stateString.trim();
        int bracketStart = stateString.indexOf('[');
        if (bracketStart == -1) {
            ResourceLocation id = ResourceLocation.tryParse(stateString);
            if (id == null) return Blocks.AIR.defaultBlockState();
            Block block = BuiltInRegistries.BLOCK.get(id);
            return block != null ? block.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        String blockName = stateString.substring(0, bracketStart);
        String propsStr = stateString.substring(bracketStart + 1, stateString.length() - 1);

        ResourceLocation id = ResourceLocation.tryParse(blockName);
        if (id == null) return Blocks.AIR.defaultBlockState();
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null) return Blocks.AIR.defaultBlockState();

        BlockState state = block.defaultBlockState();
        String[] pairs = propsStr.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                state = applyProperty(state, kv[0].trim(), kv[1].trim());
            }
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(BlockState state, String propName, String valStr) {
        for (Property property : state.getProperties()) {
            if (property.getName().equalsIgnoreCase(propName)) {
                Optional<?> optVal = property.getValue(valStr);
                if (optVal.isPresent()) {
                    return (BlockState) state.setValue(property, (Comparable) optVal.get());
                }
            }
        }
        return state;
    }

    public static BlockState fromLegacyId(int id, int data) {
        return switch (id) {
            case 0 -> Blocks.AIR.defaultBlockState();
            case 1 -> switch (data) {
                case 1 -> Blocks.GRANITE.defaultBlockState();
                case 2 -> Blocks.POLISHED_GRANITE.defaultBlockState();
                case 3 -> Blocks.DIORITE.defaultBlockState();
                case 4 -> Blocks.POLISHED_DIORITE.defaultBlockState();
                case 5 -> Blocks.ANDESITE.defaultBlockState();
                case 6 -> Blocks.POLISHED_ANDESITE.defaultBlockState();
                default -> Blocks.STONE.defaultBlockState();
            };
            case 2 -> Blocks.GRASS_BLOCK.defaultBlockState();
            case 3 -> switch (data) {
                case 1 -> Blocks.COARSE_DIRT.defaultBlockState();
                case 2 -> Blocks.PODZOL.defaultBlockState();
                default -> Blocks.DIRT.defaultBlockState();
            };
            case 4 -> Blocks.COBBLESTONE.defaultBlockState();
            case 5 -> switch (data) {
                case 1 -> Blocks.SPRUCE_PLANKS.defaultBlockState();
                case 2 -> Blocks.BIRCH_PLANKS.defaultBlockState();
                case 3 -> Blocks.JUNGLE_PLANKS.defaultBlockState();
                case 4 -> Blocks.ACACIA_PLANKS.defaultBlockState();
                case 5 -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
                default -> Blocks.OAK_PLANKS.defaultBlockState();
            };
            case 7 -> Blocks.BEDROCK.defaultBlockState();
            case 12 -> data == 1 ? Blocks.RED_SAND.defaultBlockState() : Blocks.SAND.defaultBlockState();
            case 13 -> Blocks.GRAVEL.defaultBlockState();
            case 14 -> Blocks.GOLD_ORE.defaultBlockState();
            case 15 -> Blocks.IRON_ORE.defaultBlockState();
            case 16 -> Blocks.COAL_ORE.defaultBlockState();
            case 17 -> switch (data & 3) {
                case 1 -> Blocks.SPRUCE_LOG.defaultBlockState();
                case 2 -> Blocks.BIRCH_LOG.defaultBlockState();
                case 3 -> Blocks.JUNGLE_LOG.defaultBlockState();
                default -> Blocks.OAK_LOG.defaultBlockState();
            };
            case 18 -> switch (data & 3) {
                case 1 -> Blocks.SPRUCE_LEAVES.defaultBlockState();
                case 2 -> Blocks.BIRCH_LEAVES.defaultBlockState();
                case 3 -> Blocks.JUNGLE_LEAVES.defaultBlockState();
                default -> Blocks.OAK_LEAVES.defaultBlockState();
            };
            case 19 -> Blocks.SPONGE.defaultBlockState();
            case 20 -> Blocks.GLASS.defaultBlockState();
            case 21 -> Blocks.LAPIS_ORE.defaultBlockState();
            case 22 -> Blocks.LAPIS_BLOCK.defaultBlockState();
            case 24 -> Blocks.SANDSTONE.defaultBlockState();
            case 35 -> getWoolColor(data);
            case 41 -> Blocks.GOLD_BLOCK.defaultBlockState();
            case 42 -> Blocks.IRON_BLOCK.defaultBlockState();
            case 43, 44 -> Blocks.STONE_SLAB.defaultBlockState();
            case 45 -> Blocks.BRICKS.defaultBlockState();
            case 46 -> Blocks.TNT.defaultBlockState();
            case 47 -> Blocks.BOOKSHELF.defaultBlockState();
            case 48 -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case 49 -> Blocks.OBSIDIAN.defaultBlockState();
            case 50 -> Blocks.TORCH.defaultBlockState();
            case 53 -> Blocks.OAK_STAIRS.defaultBlockState();
            case 54 -> Blocks.CHEST.defaultBlockState();
            case 56 -> Blocks.DIAMOND_ORE.defaultBlockState();
            case 57 -> Blocks.DIAMOND_BLOCK.defaultBlockState();
            case 58 -> Blocks.CRAFTING_TABLE.defaultBlockState();
            case 67 -> Blocks.COBBLESTONE_STAIRS.defaultBlockState();
            case 73, 74 -> Blocks.REDSTONE_ORE.defaultBlockState();
            case 82 -> Blocks.CLAY.defaultBlockState();
            case 86 -> Blocks.PUMPKIN.defaultBlockState();
            case 87 -> Blocks.NETHERRACK.defaultBlockState();
            case 88 -> Blocks.SOUL_SAND.defaultBlockState();
            case 89 -> Blocks.GLOWSTONE.defaultBlockState();
            case 98 -> switch (data) {
                case 1 -> Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                case 2 -> Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
                case 3 -> Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                default -> Blocks.STONE_BRICKS.defaultBlockState();
            };
            case 108 -> Blocks.BRICK_STAIRS.defaultBlockState();
            case 109 -> Blocks.STONE_BRICK_STAIRS.defaultBlockState();
            case 121 -> Blocks.END_STONE.defaultBlockState();
            case 133 -> Blocks.EMERALD_BLOCK.defaultBlockState();
            case 134 -> Blocks.SPRUCE_STAIRS.defaultBlockState();
            case 135 -> Blocks.BIRCH_STAIRS.defaultBlockState();
            case 136 -> Blocks.JUNGLE_STAIRS.defaultBlockState();
            case 152 -> Blocks.REDSTONE_BLOCK.defaultBlockState();
            case 155 -> Blocks.QUARTZ_BLOCK.defaultBlockState();
            case 156 -> Blocks.QUARTZ_STAIRS.defaultBlockState();
            case 163 -> Blocks.ACACIA_STAIRS.defaultBlockState();
            case 164 -> Blocks.DARK_OAK_STAIRS.defaultBlockState();
            case 172 -> Blocks.TERRACOTTA.defaultBlockState();
            case 173 -> Blocks.COAL_BLOCK.defaultBlockState();
            default -> Blocks.AIR.defaultBlockState();
        };
    }

    private static BlockState getWoolColor(int color) {
        return switch (color) {
            case 1 -> Blocks.ORANGE_WOOL.defaultBlockState();
            case 2 -> Blocks.MAGENTA_WOOL.defaultBlockState();
            case 3 -> Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
            case 4 -> Blocks.YELLOW_WOOL.defaultBlockState();
            case 5 -> Blocks.LIME_WOOL.defaultBlockState();
            case 6 -> Blocks.PINK_WOOL.defaultBlockState();
            case 7 -> Blocks.GRAY_WOOL.defaultBlockState();
            case 8 -> Blocks.LIGHT_GRAY_WOOL.defaultBlockState();
            case 9 -> Blocks.CYAN_WOOL.defaultBlockState();
            case 10 -> Blocks.PURPLE_WOOL.defaultBlockState();
            case 11 -> Blocks.BLUE_WOOL.defaultBlockState();
            case 12 -> Blocks.BROWN_WOOL.defaultBlockState();
            case 13 -> Blocks.GREEN_WOOL.defaultBlockState();
            case 14 -> Blocks.RED_WOOL.defaultBlockState();
            case 15 -> Blocks.BLACK_WOOL.defaultBlockState();
            default -> Blocks.WHITE_WOOL.defaultBlockState();
        };
    }
}
