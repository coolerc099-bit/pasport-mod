package ru.passportmod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;

public class PassportMod implements ModInitializer {
    public static final String MOD_ID = "passportmod";
    public static final ResourceKey<net.minecraft.world.item.Item> PASSPORT_KEY =
            ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "passport"));
    public static final PassportItem PASSPORT = register(PASSPORT_KEY, PassportItem::new,
            new net.minecraft.world.item.Item.Properties().stacksTo(1));

    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-zА-Яа-яЁё-]{2,32}");
    static final Pattern SEX_PATTERN = Pattern.compile("[МмЖжMFmf]");
    static final Pattern UNIT_CODE_PATTERN = Pattern.compile("\\d{3}-\\d{3}");

    static VoteSession ACTIVE_VOTE;

    public static ResourceKey<net.minecraft.world.item.Item> id(String name) {
        return ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(MOD_ID, name));
    }

    private static <T extends net.minecraft.world.item.Item> T register(
            ResourceKey<net.minecraft.world.item.Item> key,
            Function<net.minecraft.world.item.Item.Properties, T> factory,
            net.minecraft.world.item.Item.Properties properties) {
        T item = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    @Override
    public void onInitialize() {
        registerCommands();
        ServerTickEvents.END_SERVER_TICK.register(PassportMod::tickVotes);
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("passport")
                        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(Commands.literal("info").executes(ctx -> info(ctx.getSource().getPlayerOrException())))
                        .then(Commands.literal("fill")
                                .then(Commands.argument("surname", StringArgumentType.string())
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .then(Commands.argument("patronymic", StringArgumentType.string())
                                                        .then(Commands.argument("sex", StringArgumentType.string())
                                                                .then(Commands.argument("birthDate", StringArgumentType.string())
                                                                        .then(Commands.argument("birthPlace", StringArgumentType.string())
                                                                                .then(Commands.argument("issueDate", StringArgumentType.string())
                                                                                        .then(Commands.argument("issuingAuthority", StringArgumentType.string())
                                                                                                .then(Commands.argument("unitCode", StringArgumentType.string())
                                                                                                        .then(Commands.argument("registration", StringArgumentType.greedyString())
                                                                                                                .executes(PassportCommands::fillSelf))))))))))))
                        .then(Commands.literal("issue")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("surname", StringArgumentType.string())
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .then(Commands.argument("patronymic", StringArgumentType.string())
                                                                .then(Commands.argument("sex", StringArgumentType.string())
                                                                        .then(Commands.argument("birthDate", StringArgumentType.string())
                                                                                .then(Commands.argument("birthPlace", StringArgumentType.string())
                                                                                        .then(Commands.argument("issueDate", StringArgumentType.string())
                                                                                                .then(Commands.argument("issuingAuthority", StringArgumentType.string())
                                                                                                        .then(Commands.argument("unitCode", StringArgumentType.string())
                                                                                                                .then(Commands.argument("registration", StringArgumentType.greedyString())
                                                                                                                        .executes(PassportCommands::issueOther)))))))))))))
                        .then(Commands.literal("namechange")
                                .then(Commands.argument("surname", StringArgumentType.string())
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .then(Commands.argument("patronymic", StringArgumentType.string())
                                                        .executes(PassportCommands::startNameChange)))))
                        .then(Commands.literal("vote")
                                .then(Commands.literal("yes").executes(ctx -> PassportCommands.vote(ctx.getSource(), true)))
                                .then(Commands.literal("no").executes(ctx -> PassportCommands.vote(ctx.getSource(), false))))
                        .then(Commands.literal("registration")
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(PassportCommands::changeRegistration)))
        ));
    }

    private static int help(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Паспорт RP: /passport info, /passport fill, /passport issue, /passport namechange, /passport vote yes|no, /passport registration").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Заполнить свой: /passport fill \"Фамилия\" \"Имя\" \"Отчество\" М 01.01.2000 \"Место рождения\" 26.09.2026 \"Орган выдачи\" 770-001 \"Регистрация\"").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int info(ServerPlayer player) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!PassportData.isPassport(stack)) {
            player.sendSystemMessage(Component.literal("В основной руке должен быть паспорт или чистый бланк."));
            return 0;
        }
        if (!PassportData.isIssued(stack)) {
            player.sendSystemMessage(Component.literal("Это чистый бланк. Оформите его командой /passport fill ... или /passport issue ..."));
            return 1;
        }
        PassportData.sendFull(player, stack);
        return 1;
    }

    private static void tickVotes(MinecraftServer server) {
        if (ACTIVE_VOTE == null) return;
        long now = server.overworld().getGameTime();
        if (now >= ACTIVE_VOTE.expiresAt()) {
            ServerPlayer proposer = server.getPlayerList().getPlayer(ACTIVE_VOTE.proposer());
            broadcast(server, "Голосование за изменение ФИО завершено: большинство не набрано.");
            if (proposer != null) {
                proposer.sendSystemMessage(Component.literal("Голосование завершено. Изменения не применены.").withStyle(ChatFormatting.RED));
            }
            ACTIVE_VOTE = null;
        }
    }

    static void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }

    record VoteSession(
            UUID proposer,
            String passportId,
            String surname,
            String name,
            String patronymic,
            Set<UUID> eligibleVoters,
            Map<UUID, Boolean> votes,
            long expiresAt
    ) {
        int yesCount() {
            return (int) votes.values().stream().filter(Boolean::booleanValue).count();
        }

        int noCount() {
            return (int) votes.values().stream().filter(v -> !v).count();
        }
    }

    static final class PassportCommands {
        private PassportCommands() {}

        static int fillSelf(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            return fillPassport(ctx, player, player, false);
        }

        static int issueOther(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
            ServerPlayer issuer = ctx.getSource().getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            return fillPassport(ctx, issuer, target, true);
        }

        static int fillPassport(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                                 ServerPlayer issuer, ServerPlayer target, boolean transferToTarget) {
            ItemStack blank = issuer.getItemInHand(InteractionHand.MAIN_HAND);
            if (!PassportData.isPassport(blank) || PassportData.isIssued(blank)) {
                issuer.sendSystemMessage(Component.literal("Нужен чистый бланк паспорта в основной руке."));
                return 0;
            }

            String surname = StringArgumentType.getString(ctx, "surname");
            String name = StringArgumentType.getString(ctx, "name");
            String patronymic = StringArgumentType.getString(ctx, "patronymic");
            String sex = StringArgumentType.getString(ctx, "sex");
            String birthDate = StringArgumentType.getString(ctx, "birthDate");
            String birthPlace = StringArgumentType.getString(ctx, "birthPlace");
            String issueDate = StringArgumentType.getString(ctx, "issueDate");
            String authority = StringArgumentType.getString(ctx, "issuingAuthority");
            String unitCode = StringArgumentType.getString(ctx, "unitCode");
            String registration = StringArgumentType.getString(ctx, "registration");

            String validation = PassportData.validate(surname, name, patronymic, sex, birthDate, birthPlace, issueDate, authority, unitCode, registration);
            if (validation != null) {
                issuer.sendSystemMessage(Component.literal("Ошибка оформления: " + validation).withStyle(ChatFormatting.RED));
                return 0;
            }

            ItemStack result = blank.copy();
            PassportData.issue(result, target, surname, name, patronymic, sex.toUpperCase(), birthDate, birthPlace, issueDate, authority, unitCode, registration);

            if (transferToTarget) {
                if (!target.getInventory().add(result)) {
                    issuer.sendSystemMessage(Component.literal("У игрока нет места в инвентаре. Паспорт не выдан.").withStyle(ChatFormatting.RED));
                    return 0;
                }
                blank.shrink(1);
                issuer.sendSystemMessage(Component.literal("Паспорт выдан игроку " + target.getName().getString() + ".").withStyle(ChatFormatting.GREEN));
                target.sendSystemMessage(Component.literal("Вам выдан паспорт Российской Федерации.").withStyle(ChatFormatting.GREEN));
            } else {
                blank.set(DataComponents.CUSTOM_DATA, result.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
                blank.set(DataComponents.CUSTOM_NAME, Component.literal("Паспорт гражданина РФ"));
                issuer.sendSystemMessage(Component.literal("Паспорт оформлен. Серия и номер присвоены автоматически.").withStyle(ChatFormatting.GREEN));
            }

            return 1;
        }

        static int startNameChange(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ItemStack passport = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!PassportData.isIssued(passport)) {
                player.sendSystemMessage(Component.literal("Нужен действующий паспорт в основной руке."));
                return 0;
            }
            if (!PassportData.isOwner(passport, player)) {
                player.sendSystemMessage(Component.literal("Изменять ФИО можно только у своего паспорта.").withStyle(ChatFormatting.RED));
                return 0;
            }
            String surname = StringArgumentType.getString(ctx, "surname");
            String name = StringArgumentType.getString(ctx, "name");
            String patronymic = StringArgumentType.getString(ctx, "patronymic");
            if (!validName(surname) || !validName(name) || !validName(patronymic)) {
                player.sendSystemMessage(Component.literal("ФИО содержит недопустимые символы или длину.").withStyle(ChatFormatting.RED));
                return 0;
            }
            String id = PassportData.get(passport, "passport_id");
            if (id == null || id.isBlank()) {
                player.sendSystemMessage(Component.literal("У паспорта повреждены данные.").withStyle(ChatFormatting.RED));
                return 0;
            }
            Set<UUID> eligible = new HashSet<>();
            for (ServerPlayer online : player.level().getServer().getPlayerList().getPlayers()) {
                eligible.add(online.getUUID());
            }
            long expires = player.level().getServer().overworld().getGameTime() + 1200L;
            VoteSession session = new VoteSession(player.getUUID(), id, surname, name, patronymic, Set.copyOf(eligible), new HashMap<>(), expires);
            if (ACTIVE_VOTE != null) {
                player.sendSystemMessage(Component.literal("Сейчас уже идёт другое голосование. Дождитесь его окончания.").withStyle(ChatFormatting.RED));
                return 0;
            }
            ACTIVE_VOTE = session;

            broadcast(player.level().getServer(), "Игрок " + player.getName().getString() + " открыл голосование за смену ФИО на: "
                    + surname + " " + name + " " + patronymic + ". Голосование 60 секунд.");
            broadcast(player.level().getServer(), "Проголосовать: /passport vote yes или /passport vote no");
            player.sendSystemMessage(Component.literal("Необходимо строго больше 50% голосов от участников, бывших онлайн при старте.").withStyle(ChatFormatting.GRAY));
            return 1;
        }

        static int vote(net.minecraft.commands.CommandSourceStack source, boolean yes) {
            ServerPlayer player = source.getPlayerOrException();
            VoteSession session = ACTIVE_VOTE;
            if (session != null && !session.eligibleVoters().contains(player.getUUID())) {
                session = null;
            }
            if (session == null) {
                player.sendSystemMessage(Component.literal("Нет активного голосования, в котором вы участвуете."));
                return 0;
            }
            if (session.votes().containsKey(player.getUUID())) {
                player.sendSystemMessage(Component.literal("Вы уже голосовали."));
                return 0;
            }
            session.votes().put(player.getUUID(), yes);
            int yesCount = session.yesCount();
            int total = session.eligibleVoters().size();
            broadcast(player.level().getServer(), "Голосование: ЗА " + yesCount + "/" + total + ", ПРОТИВ " + session.noCount());

            if (yesCount * 2 > total) {
                applyNameChange(player.level().getServer(), session);
                ACTIVE_VOTE = null;
            }
            return 1;
        }

        private static void applyNameChange(MinecraftServer server, VoteSession session) {
            ServerPlayer proposer = server.getPlayerList().getPlayer(session.proposer());
            if (proposer == null) {
                broadcast(server, "Голосование прошло, но владелец паспорта вышел с сервера. Изменение не применено.");
                return;
            }
            ItemStack passport = findById(proposer, session.passportId());
            if (passport == null) {
                broadcast(server, "Голосование прошло, но паспорт не найден. Изменение не применено.");
                return;
            }
            PassportData.updateName(passport, session.surname(), session.name(), session.patronymic());
            broadcast(server, "Голосование завершено: ФИО владельца паспорта изменено на "
                    + session.surname() + " " + session.name() + " " + session.patronymic() + ".");
        }

        static int changeRegistration(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ItemStack passport = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!PassportData.isIssued(passport)) {
                player.sendSystemMessage(Component.literal("Нужен действующий паспорт в основной руке."));
                return 0;
            }
            if (!PassportData.isOwner(passport, player)) {
                player.sendSystemMessage(Component.literal("Изменять регистрацию можно только у своего паспорта.").withStyle(ChatFormatting.RED));
                return 0;
            }
            String value = StringArgumentType.getString(ctx, "value").trim();
            if (value.length() < 3 || value.length() > 120) {
                player.sendSystemMessage(Component.literal("Адрес регистрации должен быть длиной 3–120 символов.").withStyle(ChatFormatting.RED));
                return 0;
            }
            PassportData.set(passport, "registration", value);
            player.sendSystemMessage(Component.literal("Регистрация изменена.").withStyle(ChatFormatting.GREEN));
            return 1;
        }

        private static ItemStack findById(ServerPlayer player, String id) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (PassportData.isIssued(stack) && id.equals(PassportData.get(stack, "passport_id"))) {
                    return stack;
                }
            }
            return null;
        }

        private static boolean validName(String value) {
            return value != null && NAME_PATTERN.matcher(value).matches();
        }
    }

    static final class PassportData {
        private PassportData() {}

        static boolean isPassport(ItemStack stack) {
            return stack != null && stack.is(PASSPORT);
        }

        static boolean isIssued(ItemStack stack) {
            return getBool(stack, "issued");
        }

        static boolean isOwner(ItemStack stack, ServerPlayer player) {
            return player.getUUID().toString().equals(get(stack, "owner_uuid"));
        }

        static void issue(ItemStack stack,
                          ServerPlayer target,
                          String surname,
                          String name,
                          String patronymic,
                          String sex,
                          String birthDate,
                          String birthPlace,
                          String issueDate,
                          String issuingAuthority,
                          String unitCode,
                          String registration) {
            String series = String.format("%04d", ThreadLocalRandom.current().nextInt(1, 10000));
            String number = String.format("%06d", ThreadLocalRandom.current().nextInt(1, 1000000));
            String id = UUID.randomUUID().toString();
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.putBoolean("issued", true);
                tag.putString("passport_id", id);
                tag.putString("owner_uuid", target.getUUID().toString());
                tag.putString("owner_name", target.getName().getString());
                tag.putString("citizenship", "Российская Федерация");
                tag.putString("surname", surname);
                tag.putString("name", name);
                tag.putString("patronymic", patronymic);
                tag.putString("sex", sex);
                tag.putString("birth_date", birthDate);
                tag.putString("birth_place", birthPlace);
                tag.putString("series", series);
                tag.putString("number", number);
                tag.putString("issue_date", issueDate);
                tag.putString("issuing_authority", issuingAuthority);
                tag.putString("unit_code", unitCode);
                tag.putString("registration", registration);
            });
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Паспорт гражданина РФ"));
        }

        static void updateName(ItemStack stack, String surname, String name, String patronymic) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.putString("surname", surname);
                tag.putString("name", name);
                tag.putString("patronymic", patronymic);
            });
        }

        static String validate(String surname, String name, String patronymic, String sex, String birthDate,
                               String birthPlace, String issueDate, String authority, String unitCode, String registration) {
            if (!PassportCommands.validName(surname) || !PassportCommands.validName(name) || !PassportCommands.validName(patronymic)) {
                return "ФИО допускает только буквы и дефис, длина 2–32 символа.";
            }
            if (!SEX_PATTERN.matcher(sex).matches()) {
                return "пол должен быть М/Ж (также допускаются M/F)";
            }
            LocalDate birth = parseDate(birthDate);
            LocalDate issue = parseDate(issueDate);
            if (birth == null || issue == null) {
                return "дата должна быть в формате ДД.ММ.ГГГГ";
            }
            LocalDate today = LocalDate.now();
            if (birth.isAfter(today)) {
                return "дата рождения не может быть в будущем";
            }
            if (issue.isBefore(birth)) {
                return "дата выдачи не может быть раньше даты рождения";
            }
            if (birthPlace.length() < 2 || birthPlace.length() > 120 || authority.length() < 2 || authority.length() > 120 || registration.length() < 3 || registration.length() > 120) {
                return "текстовые поля слишком короткие или длинные";
            }
            if (!UNIT_CODE_PATTERN.matcher(unitCode).matches()) {
                return "код подразделения должен быть в формате ХХХ-ХХХ (например 770-001)";
            }
            return null;
        }

        private static LocalDate parseDate(String value) {
            try {
                return LocalDate.parse(value, DATE_FORMAT);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }

        static void sendFull(ServerPlayer player, ItemStack stack) {
            player.sendSystemMessage(Component.literal("════ ПАСПОРТ ГРАЖДАНИНА РОССИЙСКОЙ ФЕДЕРАЦИИ ════").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            line(player, "Фамилия", get(stack, "surname"));
            line(player, "Имя", get(stack, "name"));
            line(player, "Отчество", get(stack, "patronymic"));
            line(player, "Пол", get(stack, "sex"));
            line(player, "Гражданство", get(stack, "citizenship"));
            line(player, "Дата рождения", get(stack, "birth_date"));
            line(player, "Место рождения", get(stack, "birth_place"));
            line(player, "Серия", get(stack, "series"));
            line(player, "Номер", get(stack, "number"));
            line(player, "Дата выдачи", get(stack, "issue_date"));
            line(player, "Кем выдан", get(stack, "issuing_authority"));
            line(player, "Код подразделения", get(stack, "unit_code"));
            line(player, "Регистрация", get(stack, "registration"));
            player.sendSystemMessage(Component.literal("Паспорт принадлежит: " + get(stack, "owner_name")).withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.literal("Изменение ФИО: /passport namechange \"Фамилия\" \"Имя\" \"Отчество\"").withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.literal("Изменение регистрации: /passport registration \"Новый адрес\"").withStyle(ChatFormatting.GRAY));
        }

        private static void line(ServerPlayer player, String label, String value) {
            player.sendSystemMessage(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                    .copy().append(Component.literal(value == null ? "—" : value).withStyle(ChatFormatting.WHITE)));
        }

        static String get(ItemStack stack, String key) {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            return data == null ? "" : data.copyTag().getStringOr(key, "");
        }

        static boolean getBool(ItemStack stack, String key) {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            return data != null && data.copyTag().getBooleanOr(key, false);
        }

        static void set(ItemStack stack, String key, String value) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(key, value));
        }
    }
}
