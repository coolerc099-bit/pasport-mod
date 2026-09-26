package ru.passportmod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class PassportItem extends Item {
    public PassportItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player user, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = user.getItemInHand(hand);
        if (!PassportMod.PassportData.isIssued(stack)) {
            user.sendSystemMessage(Component.literal("Чистый бланк паспорта. Подойдите к паспортному столу и используйте /passport fill ... или /passport issue ..."));
        } else if (user instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            PassportMod.PassportData.showPage(serverPlayer, stack, 1);
        }
        return InteractionResult.SUCCESS;
    }
}
