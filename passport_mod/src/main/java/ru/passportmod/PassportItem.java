package ru.passportmod;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
    public InteractionResult use(Level level, Player user, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(user instanceof ServerPlayer player)) return InteractionResult.PASS;

        ItemStack stack = user.getItemInHand(hand);
        if (!PassportMod.PassportData.isPassport(stack)) return InteractionResult.PASS;

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
            return InteractionResult.SUCCESS;
        }

        PassportMod.openViewer(player, record);
        return InteractionResult.SUCCESS;
    }
}
