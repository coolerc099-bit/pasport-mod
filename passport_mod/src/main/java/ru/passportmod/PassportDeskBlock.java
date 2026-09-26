package ru.passportmod;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * Паспортный стол — мебель-станция. Всё редактирование паспортов (оформление,
 * заявки на голосование) разрешено только рядом с этим блоком — см.
 * PassportMod.requireDeskNearby().
 */
public class PassportDeskBlock extends HorizontalDirectionalBlock {
    public PassportDeskBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
