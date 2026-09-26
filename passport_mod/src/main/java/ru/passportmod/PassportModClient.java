package ru.passportmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class PassportModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ItemTooltipCallback.EVENT.register((stack, context, type, tooltip) -> {
            if (!PassportMod.PassportData.isPassport(stack) || !PassportMod.PassportData.isIssued(stack)) {
                return;
            }
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data == null) {
                return;
            }
            var tag = data.copyTag();
            tooltip.add(Component.literal("Серия " + tag.getStringOr("series", "—") + " № " + tag.getStringOr("number", "—"))
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal(tag.getStringOr("surname", "") + " "
                    + tag.getStringOr("name", "") + " "
                    + tag.getStringOr("patronymic", ""))
                    .withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("ПКМ: открыть паспорт").withStyle(ChatFormatting.GRAY));
        });
    }
}
