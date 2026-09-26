package ru.passportmod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.BlockItem;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;

public class PassportMod implements ModInitializer {
    public static final String MOD_ID = "passportmod";

    // --- Обложки паспорта: default (красная, "passport") + 15 доп. цветов красителей ---
    private static final String[] EXTRA_COVER_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "black"
    };
    public static final Map<String, PassportItem> PASSPORT_VARIANTS = new LinkedHashMap<>();
    public static final PassportItem PASSPORT;

    static {
        PASSPORT = registerPassportVariant("passport");
        PASSPORT_VARIANTS.put("red", PASSPORT);
        for (String color : EXTRA_COVER_COLORS) {
            PASSPORT_VARIANTS.put(color, registerPassportVariant("passport_" + color));
        }
    }

    // --- Паспортный стол ---
    public static final ResourceKey<Block> PASSPORT_DESK_KEY =
            ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, "passport_desk"));
    public static final PassportDeskBlock PASSPORT_DESK = registerBlock(PASSPORT_DESK_KEY, PassportDeskBlock::new,
            BlockBehaviour.Properties.of().strength(2.5F).sound(SoundType.WOOD));

    public static final ResourceKey<net.minecraft.world.item.Item> PASSPORT_DESK_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "passport_desk"));
    public static final BlockItem PASSPORT_DESK_ITEM = registerBlockItem(PASSPORT_DESK_ITEM_KEY, PASSPORT_DESK,
            new net.minecraft.world.item.Item.Properties());

    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-zА-Яа-яЁё-]{2,32}");
    static final Pattern SEX_PATTERN = Pattern.compile("[МмЖжMFmf]");
    static final Pattern UNIT_CODE_PATTERN = Pattern.compile("\\d{3}-\\d{3}");

    static final int DESK_RADIUS = 4;
    static final long VOTE_COOLDOWN_MILLIS = 30L * 60L * 1000L; // 30 реальных минут

    static VoteSession ACTIVE_VOTE;
    static final Map<UUID, Long> LAST_VOTE_ATTEMPT = new HashMap<>();

    private static PassportItem registerPassportVariant(String path) {
        ResourceKey<net.minecraft.world.item.Item> key =
                ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, path));
        return register(key, PassportItem::new, new net.minecraft.world.item.Item.Properties().stacksTo(1));
    }

    private static <T extends net.minecraft.world.item.Item> T register(
            ResourceKey<net.minecraft.world.item.Item> key,
            Function<net.minecraft.world.item.Item.Properties, T> factory,
            net.minecraft.world.item.Item.Properties properties) {
        T item = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    private static <T extends Block> T registerBlock(
            ResourceKey<Block> key,
            Function<BlockBehaviour.Properties, T> factory,
            BlockBehaviour.Properties properties) {
        T block = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }

    private static BlockItem registerBlockItem(
            ResourceKey<net.minecraft.world.item.Item> key,
            Block block,
            net.minecraft.world.item.Item.Properties properties) {
        BlockItem item = new BlockItem(block, properties.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    @Override
    public void onInitialize() {
        registerCommands();
        registerDeskHint();
        ServerTickEvents.END_SERVER_TICK.register(PassportMod::tickVotes);
    }

    private static void registerDeskHint() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) {
                return InteractionResult.PASS;
            }
            if (!(world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof PassportDeskBlock)) {
                return InteractionResult.PASS;
            }
            player.sendSystemMessage(Component.literal("═ Паспортный стол ═").withStyle(ChatFormatting.GOLD));
            player.sendSystemMessage(Component.literal("Оформить бланк: /passport fill ... или /passport issue <игрок> ...").withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.literal("Подать заявку на смену данных: /passport propose surname|name|patronymic|all \"...\"").withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.literal("Сменить регистрацию: /passport registration \"Новый адрес\"").withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        });
    }

    static boolean isNearDesk(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -DESK_RADIUS; x <= DESK_RADIUS; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -DESK_RADIUS; z <= DESK_RADIUS; z++) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (player.level().getBlockState(cursor).getBlock() instanceof PassportDeskBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("passport")
                        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(Commands.literal("info").executes(ctx -> info(ctx.getSource().getPlayerOrException())))
                        .then(Commands.literal("page")
                                .then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                                        .executes(PassportCommands::pageNav)))
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
                        .then(Commands.literal("propose")
                                .then(Commands.literal("surname")
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> PassportCommands.propose(ctx, "SURNAME"))))
                                .then(Commands.literal("name")
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> PassportCommands.propose(ctx, "NAME"))))
                                .then(Commands.literal("patronymic")
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> PassportCommands.propose(ctx, "PATRONYMIC"))))
                                .then(Commands.literal("all")
                                        .then(Commands.argument("surname", StringArgumentType.string())
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .then(Commands.argument("patronymic", StringArgumentType.string())
                                                                .executes(ctx -> PassportCommands.proposeAll(ctx)))))))
                        .then(Commands.literal("vote")
                                .then(Commands.literal("yes").executes(ctx -> PassportCommands.vote(ctx.getSource(), true)))
                                .then(Commands.literal("no").executes(ctx -> PassportCommands.vote(ctx.getSource(), false))))
                        .then(Commands.literal("registration")
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(PassportCommands::changeRegistration)))
        ));
    }

    private static int help(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Паспорт RP: /passport info, fill, issue, propose, vote yes|no, registration").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Оформление и заявки работают только рядом с паспортным столом.").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Пример: /passport fill \"Фамилия\" \"Имя\" \"Отчество\" М 01.01.2000 \"Место рождения\" 26.09.2026 \"Орган выдачи\" 770-001 \"Регистрация\"").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int info(ServerPlayer player) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!PassportData.isPassport(stack)) {
            player.sendSystemMessage(Component.literal("В основной руке должен быть паспорт или чистый бланк."));
            return 0;
        }
        if (!PassportData.isIssued(stack)) {
            player.sendSystemMessage(Component.literal("Это чистый бланк. Подойдите к паспортному столу: /passport fill ... или /passport issue ..."));
            return 1;
        }
        PassportData.showPage(player, stack, 1);
        return 1;
    }

    private static void tickVotes(MinecraftServer server) {
        if (ACTIVE_VOTE == null) return;
        long now = server.overworld().getGameTime();
        if (now >= ACTIVE_VOTE.expiresAt()) {
            ServerPlayer proposer = server.getPlayerList().getPlayer(ACTIVE_VOTE.proposer());
            broadcast(server, "Голосование завершено: большинство не набрано.");
            if (proposer != null) {
                proposer.sendSystemMessage(Component.literal("Голосование завершено. Изменения не применены.").withStyle(ChatFormatting.RED));
            }
            ACTIVE_VOTE = null;
        }
    }

    static void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }

    static void broadcastVotePrompt(MinecraftServer server, String question) {
        Component yes = Component.literal("[ДА]").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("/passport vote yes")));
        Component no = Component.literal("[НЕТ]").setStyle(Style.EMPTY.withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent.RunCommand("/passport vote no")));
        Component full = Component.literal(question + "  ").withStyle(ChatFormatting.YELLOW)
                .copy().append(yes).append(Component.literal("  ")).append(no);
        server.getPlayerList().broadcastSystemMessage(full, false);
    }

    record VoteSession(
            UUID proposer,
            String passportId,
            String scope,
            String newSurname,
            String newName,
            String newPatronymic,
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

        static boolean requireDesk(ServerPlayer player) {
            if (!isNearDesk(player)) {
                player.sendSystemMessage(Component.literal("Нужно быть рядом с паспортным столом (в пределах " + DESK_RADIUS + " блоков).").withStyle(ChatFormatting.RED));
                return false;
            }
            return true;
        }

        static int pageNav(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n");
            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!PassportData.isPassport(stack) || !PassportData.isIssued(stack)) {
                player.sendSystemMessage(Component.literal("Нужен действующий паспорт в основной руке."));
                return 0;
            }
            PassportData.showPage(player, stack, n);
            return 1;
        }

        static int fillSelf(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            return fillPassport(ctx, player, player, false);
        }

        static int issueOther(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer issuer = ctx.getSource().getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            return fillPassport(ctx, issuer, target, true);
        }

        static int fillPassport(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,
                                 ServerPlayer issuer, ServerPlayer target, boolean transferToTarget) {
            if (!requireDesk(issuer)) {
                return 0;
            }
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
                target.sendSystemMessage(Component.literal("Вам выдан паспорт гражданина РФ.").withStyle(ChatFormatting.GREEN));
            } else {
                blank.set(DataComponents.CUSTOM_DATA, result.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
                blank.set(DataComponents.CUSTOM_NAME, Component.literal("Паспорт гражданина РФ"));
                issuer.sendSystemMessage(Component.literal("Паспорт оформлен. Серия и номер присвоены автоматически.").withStyle(ChatFormatting.GREEN));
            }

            return 1;
        }

        static int propose(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx, String scope) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            String value = StringArgumentType.getString(ctx, "value");
            String surname = scope.equals("SURNAME") ? value : null;
            String name = scope.equals("NAME") ? value : null;
            String patronymic = scope.equals("PATRONYMIC") ? value : null;
            return startVote(player, scope, surname, name, patronymic);
        }

        static int proposeAll(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            String surname = StringArgumentType.getString(ctx, "surname");
            String name = StringArgumentType.getString(ctx, "name");
            String patronymic = StringArgumentType.getString(ctx, "patronymic");
            return startVote(player, "ALL", surname, name, patronymic);
        }

        private static int startVote(ServerPlayer player, String scope, String surname, String name, String patronymic) {
            if (!requireDesk(player)) {
                return 0;
            }
            ItemStack passport = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!PassportData.isIssued(passport)) {
                player.sendSystemMessage(Component.literal("Нужен действующий паспорт в основной руке."));
                return 0;
            }
            if (!PassportData.isOwner(passport, player)) {
                player.sendSystemMessage(Component.literal("Подавать заявку можно только на свой паспорт.").withStyle(ChatFormatting.RED));
                return 0;
            }
            if (surname != null && !validName(surname)) {
                player.sendSystemMessage(Component.literal("Некорректная фамилия.").withStyle(ChatFormatting.RED));
                return 0;
            }
            if (name != null && !validName(name)) {
                player.sendSystemMessage(Component.literal("Некорректное имя.").withStyle(ChatFormatting.RED));
                return 0;
            }
            if (patronymic != null && !validName(patronymic)) {
                player.sendSystemMessage(Component.literal("Некорректное отчество.").withStyle(ChatFormatting.RED));
                return 0;
            }
            long now = System.currentTimeMillis();
            Long last = LAST_VOTE_ATTEMPT.get(player.getUUID());
            if (last != null && now - last < VOTE_COOLDOWN_MILLIS) {
                long remainingMin = (VOTE_COOLDOWN_MILLIS - (now - last)) / 60000L + 1;
                player.sendSystemMessage(Component.literal("Следующую заявку можно подать через " + remainingMin + " мин.").withStyle(ChatFormatting.RED));
                return 0;
            }
            if (ACTIVE_VOTE != null) {
                player.sendSystemMessage(Component.literal("Сейчас уже идёт другое голосование. Дождитесь его окончания.").withStyle(ChatFormatting.RED));
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
            ACTIVE_VOTE = new VoteSession(player.getUUID(), id, scope, surname, name, patronymic, Set.copyOf(eligible), new HashMap<>(), expires);
            LAST_VOTE_ATTEMPT.put(player.getUUID(), now);

            String what = switch (scope) {
                case "SURNAME" -> "фамилию на " + surname;
                case "NAME" -> "имя на " + name;
                case "PATRONYMIC" -> "отчество на " + patronymic;
                default -> "ФИО на " + surname + " " + name + " " + patronymic;
            };
            broadcast(player.level().getServer(), "Игрок " + player.getName().getString() + " предлагает сменить " + what + ". Голосование 60 секунд.");
            broadcastVotePrompt(player.level().getServer(), "Голосовать:");
            player.sendSystemMessage(Component.literal("Необходимо строго больше 50% голосов от участников, бывших онлайн при старте.").withStyle(ChatFormatting.GRAY));
            return 1;
        }

        static int vote(net.minecraft.commands.CommandSourceStack source, boolean yes) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
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
                applyVote(player.level().getServer(), session);
                ACTIVE_VOTE = null;
            }
            return 1;
        }

        private static void applyVote(MinecraftServer server, VoteSession session) {
            ServerPlayer proposer = server.getPlayerList().getPlayer(session.proposer());
            if (proposer == null) {
                broadcast(server, "Голосование прошло, но заявитель вышел с сервера. Изменение не применено.");
                return;
            }
            ItemStack passport = findById(proposer, session.passportId());
            if (passport == null) {
                broadcast(server, "Голосование прошло, но паспорт не найден. Изменение не применено.");
                return;
            }
            PassportData.applyScopedChange(passport, session.scope(), session.newSurname(), session.newName(), session.newPatronymic());
            broadcast(server, "Голосование завершено: данные владельца паспорта изменены.");
        }

        static int changeRegistration(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            if (!requireDesk(player)) {
                return 0;
            }
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
            return stack != null && stack.getItem() instanceof PassportItem;
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

        static void applyScopedChange(ItemStack stack, String scope, String surname, String name, String patronymic) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                if (("SURNAME".equals(scope) || "ALL".equals(scope)) && surname != null) {
                    tag.putString("surname", surname);
                }
                if (("NAME".equals(scope) || "ALL".equals(scope)) && name != null) {
                    tag.putString("name", name);
                }
                if (("PATRONYMIC".equals(scope) || "ALL".equals(scope)) && patronymic != null) {
                    tag.putString("patronymic", patronymic);
                }
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

        /** "Книжный" постраничный просмотр паспорта через кликабельные кнопки в чате. */
        static void showPage(ServerPlayer player, ItemStack stack, int page) {
            player.sendSystemMessage(Component.literal("════ ПАСПОРТ ГРАЖДАНИНА РОССИЙСКОЙ ФЕДЕРАЦИИ (" + page + "/3) ════").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            if (page == 1) {
                line(player, "Фамилия", get(stack, "surname"));
                line(player, "Имя", get(stack, "name"));
                line(player, "Отчество", get(stack, "patronymic"));
                line(player, "Пол", get(stack, "sex"));
                line(player, "Гражданство", get(stack, "citizenship"));
            } else if (page == 2) {
                line(player, "Дата рождения", get(stack, "birth_date"));
                line(player, "Место рождения", get(stack, "birth_place"));
                line(player, "Серия", get(stack, "series"));
                line(player, "Номер", get(stack, "number"));
            } else {
                line(player, "Дата выдачи", get(stack, "issue_date"));
                line(player, "Кем выдан", get(stack, "issuing_authority"));
                line(player, "Код подразделения", get(stack, "unit_code"));
                line(player, "Регистрация", get(stack, "registration"));
            }
            Component nav = Component.literal("");
            if (page > 1) {
                nav = nav.copy().append(Component.literal("[« Назад]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.RunCommand("/passport page " + (page - 1)))));
            }
            if (page < 3) {
                if (page > 1) nav = nav.copy().append(Component.literal("  "));
                nav = nav.copy().append(Component.literal("[Далее »]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.RunCommand("/passport page " + (page + 1)))));
            }
            player.sendSystemMessage(nav);
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
