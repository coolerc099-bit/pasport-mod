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
            user.sendSystemMessage(Component.literal("Чистый бланк паспорта. Оформить: /passport fill \"Фамилия\" \"Имя\" \"Отчество\" М 01.01.2000 \"Место рождения\" 26.09.2026 \"Орган выдачи\" \"Регистрация\""));
        } else if (user instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            PassportMod.PassportData.sendFull(serverPlayer, stack);
        }
        return InteractionResult.SUCCESS;
    }
}
