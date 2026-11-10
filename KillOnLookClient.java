// ============================================================
//  Kill on Look — мод для Minecraft 1.21.4 (Fabric)
//  Все исходники в одном файле (для чтения).
//  Реально проект собирается из 4 файлов в папке killonlook\
//
//  Функции:
//   - Гуишка (Right Shift по умолчанию) с настройками
//   - Ползунок «Удары в секунду» от 1 до 10
//   - Бинды: «Открыть гуишку» и «Вкл/выкл мод» — клик по кнопке
//     в гуишке, потом нажми любую клавишу (ESC — отмена)
//   - Категории: враждебные мобы / животные / жители / игроки
//   - «Мгновенно убивать» — только одиночная игра
//   - Всё сохраняется в .minecraft/config/killonlook.json
// ============================================================

package com.killonlook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

// ============================================================
// 1. KillOnLookClient — входная точка мода.
//    Загружает конфиг и вешает обработчик игрового тика.
// ============================================================
public class KillOnLookClient implements ClientModInitializer {

    public static KillAuraConfig config;

    @Override
    public void onInitializeClient() {
        config = KillAuraConfig.load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> KillAuraHandler.tick(client, config));
    }
}

// ============================================================
// 2. KillAuraConfig — настройки мода.
//    Скорость ударов, категории, бинды. Сохраняется в
//    .minecraft/config/killonlook.json (JSON, читается Gson'ом).
// ============================================================
class KillAuraConfig {

    public boolean enabled = false;
    public int hitsPerSecond = 5;       // удары в секунду (от 1 до 10)
    public boolean hostile = true;      // зомби, скелеты, крипперы и т.д.
    public boolean animals = false;     // коровы, свиньи, овцы и т.д.
    public boolean villagers = false;   // жители и бродячие торговцы
    public boolean players = false;     // игроки
    public boolean instantKill = false; // мгновенное убийство (одиночная игра)

    // Бинды (GLFW-коды клавиш; -1 = не назначено)
    public int bindOpenGui = 344;  // Right Shift
    public int bindToggle = 71;    // G

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Проверяет, входит ли сущность в выбранные категории. */
    public boolean matches(LivingEntity target) {
        if (target instanceof PlayerEntity) return players;
        if (target instanceof HostileEntity) return hostile;
        if (target instanceof MerchantEntity) return villagers;
        if (target instanceof AnimalEntity || target instanceof WaterCreatureEntity) return animals;
        return false;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("killonlook.json");
    }

    public static KillAuraConfig load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                KillAuraConfig cfg = GSON.fromJson(Files.readString(f), KillAuraConfig.class);
                if (cfg != null) return cfg;
            }
        } catch (Exception e) {
            System.err.println("[KillOnLook] Не удалось прочитать конфиг: " + e);
        }
        return new KillAuraConfig();
    }

    public void save() {
        try {
            Files.writeString(file(), GSON.toJson(this));
        } catch (Exception e) {
            System.err.println("[KillOnLook] Не удалось сохранить конфиг: " + e);
        }
    }
}

// ============================================================
// 3. KillAuraScreen — гуишка настроек.
//    Переключатели категорий, ползунок скорости, кнопки биндов
//    (клик — затем нажимаешь клавишу, ESC — отмена).
// ============================================================
class KillAuraScreen extends Screen {

    private final KillAuraConfig cfg = KillOnLookClient.config;

    // Ожидание нажатия клавиши для перепривязки бинда
    private String listeningName = null;
    private IntSupplier listeningGetter = null;
    private Consumer<Integer> listeningSetter = null;
    private ButtonWidget listeningButton = null;

    public KillAuraScreen() {
        super(Text.literal("Kill on Look — настройки"));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = 35;

        addDrawableChild(toggle(x, y, "Мод включён", () -> cfg.enabled, v -> cfg.enabled = v)); y += 22;
        addDrawableChild(slider(x, y)); y += 22;
        addDrawableChild(toggle(x, y, "Враждебные мобы", () -> cfg.hostile, v -> cfg.hostile = v)); y += 22;
        addDrawableChild(toggle(x, y, "Животные", () -> cfg.animals, v -> cfg.animals = v)); y += 22;
        addDrawableChild(toggle(x, y, "Жители", () -> cfg.villagers, v -> cfg.villagers = v)); y += 22;
        addDrawableChild(toggle(x, y, "Игроки", () -> cfg.players, v -> cfg.players = v)); y += 22;
        addDrawableChild(toggle(x, y, "Мгновенно убивать (одиночная игра)", () -> cfg.instantKill, v -> cfg.instantKill = v)); y += 28;

        addDrawableChild(bindButton(x, y, "Открыть гуишку", () -> cfg.bindOpenGui, code -> cfg.bindOpenGui = code)); y += 22;
        addDrawableChild(bindButton(x, y, "Вкл/выкл мод", () -> cfg.bindToggle, code -> cfg.bindToggle = code)); y += 22;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> this.close())
                .dimensions(x, this.height - 30, 200, 20)
                .build());
    }

    /** Кнопка-переключатель: при клике меняет поле конфига и сразу сохраняет его в файл. */
    private ButtonWidget toggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set) {
        return ButtonWidget.builder(message(label, get.getAsBoolean()), button -> {
            set.accept(!get.getAsBoolean());
            button.setMessage(message(label, get.getAsBoolean()));
            cfg.save();
        }).dimensions(x, y, 200, 20).build();
    }

    private Text message(String label, boolean on) {
        return Text.literal(label + ": ")
                .append(Text.literal(on ? "ВКЛ" : "ВЫКЛ")
                        .formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    /** Ползунок: удары в секунду от 1 до 10. */
    private SliderWidget slider(int x, int y) {
        double normalized = (cfg.hitsPerSecond - 1) / 9.0;
        normalized = Math.max(0.0, Math.min(1.0, normalized));
        return new SliderWidget(x, y, 200, 20, sliderMessage(), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(sliderMessage());
            }

            @Override
            protected void applyValue() {
                cfg.hitsPerSecond = 1 + (int) Math.round(this.value * 9);
                cfg.save();
            }
        };
    }

    private Text sliderMessage() {
        return Text.literal("Удары в секунду: " + cfg.hitsPerSecond);
    }

    /** Кнопка бинда: клик — и следующая нажатая клавиша станет биндом (ESC — отмена). */
    private ButtonWidget bindButton(int x, int y, String name, IntSupplier get, Consumer<Integer> set) {
        return ButtonWidget.builder(bindLabel(name, get.getAsInt()), button -> {
            listeningName = name;
            listeningGetter = get;
            listeningSetter = set;
            listeningButton = button;
            button.setMessage(Text.literal(name + ": нажми клавишу (ESC — отмена)"));
        }).dimensions(x, y, 200, 20).build();
    }

    private Text bindLabel(String name, int keyCode) {
        String key = keyCode <= 0 ? "нет" : InputUtil.fromKeyCode(keyCode, 0).getLocalizedText().getString();
        return Text.literal(name + ": " + key);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ловим клавишу для перепривязки бинда
        if (listeningName != null) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                listeningSetter.accept(keyCode);
                cfg.save();
            }
            listeningButton.setMessage(bindLabel(listeningName, listeningGetter.getAsInt()));
            listeningName = null;
            listeningGetter = null;
            listeningSetter = null;
            listeningButton = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false; // мир не ставится на паузу, пока открыта гуишка
    }
}

// ============================================================
// 4. KillAuraHandler — сама логика.
//    Опрашивает бинды, каждый тик смотрит, в кого целится
//    крестик, и бьёт цель с заданной скоростью. instantKill в
//    одиночной игре убивает цель напрямую на серверной стороне.
// ============================================================
class KillAuraHandler {

    private static int cooldown = 0;
    private static boolean prevOpenDown = false;
    private static boolean prevToggleDown = false;

    private KillAuraHandler() {}

    /** Вызывается каждый игровой тик (20 раз в секунду). */
    public static void tick(MinecraftClient client, KillAuraConfig cfg) {
        // --- Бинды (опрашиваем клавиши напрямую; работает, когда нет открытых экранов) ---
        long window = client.getWindow().getHandle();
        boolean openDown = InputUtil.isKeyPressed(window, cfg.bindOpenGui);
        boolean toggleDown = cfg.bindToggle > 0 && InputUtil.isKeyPressed(window, cfg.bindToggle);

        if (openDown && !prevOpenDown && client.currentScreen == null && client.player != null) {
            client.setScreen(new KillAuraScreen());
        }
        if (toggleDown && !prevToggleDown && client.currentScreen == null && client.player != null) {
            cfg.enabled = !cfg.enabled;
            cfg.save();
            client.player.sendMessage(
                    Text.literal("Kill on Look: ")
                            .append(Text.literal(cfg.enabled ? "ВКЛ" : "ВЫКЛ")
                                    .formatted(cfg.enabled ? Formatting.GREEN : Formatting.RED)),
                    true);
        }
        prevOpenDown = openDown;
        prevToggleDown = toggleDown;

        // --- Удары по цели под прицелом ---
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (client.player == null || !cfg.enabled || client.interactionManager == null) return;
        if (client.currentScreen != null) return;

        // То, на что смотрит прицел (крестик по центру экрана)
        if (!(client.crosshairTarget instanceof EntityHitResult hit)) return;
        if (!(hit.getEntity() instanceof LivingEntity target)) return;
        if (target == client.player || !target.isAlive()) return;
        if (!cfg.matches(target)) return;

        cooldown = Math.max(1, (int) Math.round(20.0 / Math.max(1, cfg.hitsPerSecond)));

        if (cfg.instantKill && client.getServer() != null) {
            // Одиночная игра: убиваем сущность напрямую на серверной стороне
            UUID id = target.getUuid();
            RegistryKey<World> worldKey = client.world.getRegistryKey();
            MinecraftServer server = client.getServer();
            client.player.swingHand(Hand.MAIN_HAND);
            server.execute(() -> {
                ServerWorld world = server.getWorld(worldKey);
                if (world == null) return;
                Entity e = world.getEntity(id);
                if (e instanceof LivingEntity le) {
                    le.kill(world);
                }
            });
        } else {
            // Обычный удар (работает и на серверах, но с обычным уроном)
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
