package ru.passportmod;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;

public final class PassportItem extends Item {
    public PassportItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemInteractionResult use(Level level, Player user, InteractionHand hand) {
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        if (!(user instanceof ServerPlayer player)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        ItemStack stack = user.getItemInHand(hand);
        if (!PassportMod.PassportData.isPassport(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        PassportMod.PassportRecord record = PassportMod.recordForStack(player, stack);
        if (record == null) {
            if (!PassportMod.PassportData.isIssued(stack)) {
                player.sendSystemMessage(Component.literal(
                        "Это чистый бланк. Положи его в «Карман для паспорта» и открой паспортный стол."
                ));
            } else {
                player.sendSystemMessage(Component.literal(
                        "Сервер не подтвердил владельца или ID этого паспорта. Использование отклонено."
                ));
            }
            return ItemInteractionResult.SUCCESS;
        }

        PassportMod.openViewer(player, record);
        return ItemInteractionResult.SUCCESS;
    }
}
