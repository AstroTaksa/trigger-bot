// ============================================================
//  Kill on Look v1.2.0 — мод для Minecraft 1.21.4 (Fabric)
//  Все исходники в одном файле (для чтения).
//  Реально проект собирается из 6 файлов в папке killonlook\
//  (в т.ч. миксин killonlook/mixin/ClientPlayerEntityMixin.java)
//
//  Функции:
//   - КЛИК-ГУИ как в чит-клиентах: панели Combat / Targets / Misc /
//     Binds с фиолетовыми шапками, таскаются мышкой за шапку
//   - «Аура» — бьёт ближайшую цель в ванильном радиусе атаки (3.0)
//     со СКРЫТНОЙ ротацией: камера не крутится, поворот уходит на
//     сервер пакетами (миксин), на F5 видно тело с доворотом
//   - «Аим-ассист» — плавно доворачивает КАМЕРУ к ближайшей цели,
//     сам НЕ бьёт; в паре с тригер-ботом = наводка + удар
//   - «Тригер-бот» — МГНОВЕННЫЙ удар, как только прицел коснулся
//     видимой цели (сквозь стены не бьёт)
//   - «Авто-спринт» — бежит вперёд сам
//   - HUD: водяной знак, счётчик убийств за сессию, список
//     включённых модулей справа (как ArrayList у читов)
//   - Всё сохраняется в .minecraft/config/killonlook.json
// ============================================================

package com.killonlook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// ============================================================
// 1. KillOnLookClient — входная точка мода.
// ============================================================
public class KillOnLookClient implements ClientModInitializer {

    public static KillAuraConfig config;

    @Override
    public void onInitializeClient() {
        config = KillAuraConfig.load();
        KillAuraHud.register();

        // Логика — в НАЧАЛЕ тика: пакет удара должен уходить ДО пакетов
        // движения (как у ванильного клиента), иначе Grim флагает Post.
        ClientTickEvents.START_CLIENT_TICK.register(client -> KillAuraHandler.tick(client, config));
    }
}

// ============================================================
// 2. KillAuraConfig — настройки мода (JSON в config/killonlook.json).
// ============================================================
class KillAuraConfig {

    public boolean enabled = false;
    public int hitsPerSecond = 5;       // удары в секунду ауры (от 1 до 10)
    public boolean hostile = true;      // зомби, скелеты, крипперы и т.д.
    public boolean animals = false;     // коровы, свиньи, овцы и т.д.
    public boolean villagers = false;   // жители и бродячие торговцы
    public boolean players = false;     // игроки
    public boolean instantKill = false; // мгновенное убийство (одиночная игра)
    public boolean aura = false;        // бить ближайшую цель (со скрытной ротацией)
    public boolean aimAssist = false;   // плавно наводит камеру на цель, сам не бьёт
    public boolean triggerBot = false;  // мгновенный удар по видимой цели под прицелом
    public boolean autoSprint = false;  // автоматически бежать вперёд

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
// 3. KillAuraScreen — клик-гуи в стиле чит-клиентов.
//    Панели Combat / Targets / Misc / Binds с фиолетовыми шапками,
//    таскаются мышкой за шапку. Клик по строке — действие.
// ============================================================
class KillAuraScreen extends Screen {

    private static final int PANEL_W = 110;
    private static final int HEADER_H = 12;
    private static final int ROW_H = 11;

    private static final int HEADER_COLOR1 = 0xFFA55BC7; // фиолетовый верх
    private static final int HEADER_COLOR2 = 0xFF7D3F9E; // фиолетовый низ
    private static final int BODY_COLOR = 0xD60E0E14;    // тёмный корпус
    private static final int ROW_ON = 0xFFC79BF2;        // включённый модуль
    private static final int ROW_OFF = 0xFF9A9AA5;       // выключенный

    /** Строка панели. */
    private static class Row {
        final Supplier<String> label; // текст строки
        final Runnable onClick;       // действие при клике
        final BooleanSupplier active; // подсветка включённого
        boolean isBind;               // строка бинда (ловит следующую клавишу)

        Row(Supplier<String> label, Runnable onClick, BooleanSupplier active, boolean isBind) {
            this.label = label;
            this.onClick = onClick;
            this.active = active;
            this.isBind = isBind;
        }
    }

    private static class Panel {
        final String title;
        int x, y;
        final List<Row> rows = new ArrayList<>();

        Panel(String title, int x, int y) {
            this.title = title;
            this.x = x;
            this.y = y;
        }

        int height() {
            return HEADER_H + rows.size() * ROW_H + 2;
        }
    }

    private final List<Panel> panels = new ArrayList<>();
    private Panel dragging = null;
    private int dragOffX, dragOffY;

    // перепривязка бинда: следующая нажатая клавиша станет биндом
    private boolean listening = false;
    private IntSupplier listeningGetter = null;
    private Consumer<Integer> listeningSetter = null;

    public KillAuraScreen() {
        super(Text.literal("Kill on Look — click gui"));
    }

    private static String keyName(int keyCode) {
        return keyCode <= 0 ? "нет" : InputUtil.fromKeyCode(keyCode, 0).getLocalizedText().getString();
    }

    @Override
    protected void init() {
        panels.clear();
        listening = false;
        listeningGetter = null;
        listeningSetter = null;
        dragging = null;

        KillAuraConfig cfg = KillOnLookClient.config;
        int gap = PANEL_W + 5;
        int top = 24;

        // --- Combat ---
        Panel combat = new Panel("Combat", 10, top);
        combat.rows.add(new Row(
                () -> "Аура: " + (cfg.aura ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.aura = !cfg.aura; cfg.save(); },
                () -> cfg.aura, false));
        combat.rows.add(new Row(
                () -> "Аим-ассист: " + (cfg.aimAssist ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.aimAssist = !cfg.aimAssist; cfg.save(); },
                () -> cfg.aimAssist, false));
        combat.rows.add(new Row(
                () -> "Тригер-бот: " + (cfg.triggerBot ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.triggerBot = !cfg.triggerBot; cfg.save(); },
                () -> cfg.triggerBot, false));
        combat.rows.add(new Row(
                () -> "Удары/сек: " + cfg.hitsPerSecond,
                () -> { cfg.hitsPerSecond = cfg.hitsPerSecond % 10 + 1; cfg.save(); },
                () -> false, false));
        panels.add(combat);

        // --- Targets ---
        Panel targets = new Panel("Targets", 10 + gap, top);
        targets.rows.add(new Row(
                () -> "Мобы: " + (cfg.hostile ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.hostile = !cfg.hostile; cfg.save(); },
                () -> cfg.hostile, false));
        targets.rows.add(new Row(
                () -> "Животные: " + (cfg.animals ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.animals = !cfg.animals; cfg.save(); },
                () -> cfg.animals, false));
        targets.rows.add(new Row(
                () -> "Жители: " + (cfg.villagers ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.villagers = !cfg.villagers; cfg.save(); },
                () -> cfg.villagers, false));
        targets.rows.add(new Row(
                () -> "Игроки: " + (cfg.players ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.players = !cfg.players; cfg.save(); },
                () -> cfg.players, false));
        panels.add(targets);

        // --- Misc ---
        Panel misc = new Panel("Misc", 10 + gap * 2, top);
        misc.rows.add(new Row(
                () -> "Авто-спринт: " + (cfg.autoSprint ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.autoSprint = !cfg.autoSprint; cfg.save(); },
                () -> cfg.autoSprint, false));
        misc.rows.add(new Row(
                () -> "Мод: " + (cfg.enabled ? "ВКЛ" : "ВЫКЛ"),
                () -> { cfg.enabled = !cfg.enabled; cfg.save(); },
                () -> cfg.enabled, false));
        panels.add(misc);

        // --- Binds ---
        Panel binds = new Panel("Binds", 10 + gap * 3, top);
        binds.rows.add(new Row(
                () -> listening ? "нажми клавишу (ESC)" : "Гуишка: " + keyName(cfg.bindOpenGui),
                () -> {
                    listeningGetter = () -> cfg.bindOpenGui;
                    listeningSetter = code -> cfg.bindOpenGui = code;
                    listening = true;
                },
                () -> false, true));
        binds.rows.add(new Row(
                () -> listening ? "нажми клавишу (ESC)" : "Тогл мода: " + keyName(cfg.bindToggle),
                () -> {
                    listeningGetter = () -> cfg.bindToggle;
                    listeningSetter = code -> cfg.bindToggle = code;
                    listening = true;
                },
                () -> false, true));
        panels.add(binds);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        for (Panel p : panels) {
            int h = p.height();

            // корпус
            context.fill(p.x, p.y + HEADER_H, p.x + PANEL_W, p.y + h, BODY_COLOR);
            // шапка с градиентом
            context.fillGradient(p.x, p.y, p.x + PANEL_W, p.y + HEADER_H, HEADER_COLOR1, HEADER_COLOR2);
            context.drawTextWithShadow(this.textRenderer, p.title, p.x + 3, p.y + 2, 0xFFFFFFFF);

            // строки
            int ry = p.y + HEADER_H + 1;
            for (Row r : p.rows) {
                String label = (r.isBind && listening) ? "нажми клавишу (ESC)" : r.label.get();
                int color = r.active.getAsBoolean() ? ROW_ON : ROW_OFF;
                if (r.isBind && listening) color = 0xFFFFD166; // жёлтый при ожидании клавиши
                context.drawTextWithShadow(this.textRenderer, label, p.x + 3, ry, color);
                ry += ROW_H;
            }
        }

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("ESC — закрыть · панели таскаются за шапку"),
                this.width / 2, 8, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;

        for (Panel p : panels) {
            int h = p.height();

            // клик по шапке — начинаем перетаскивание
            if (mx >= p.x && mx <= p.x + PANEL_W && my >= p.y && my < p.y + HEADER_H) {
                dragging = p;
                dragOffX = mx - p.x;
                dragOffY = my - p.y;
                return true;
            }

            // клик по строке — действие
            if (mx >= p.x && mx <= p.x + PANEL_W && my >= p.y + HEADER_H && my < p.y + h) {
                int index = (my - (p.y + HEADER_H) - 1) / ROW_H;
                if (index >= 0 && index < p.rows.size()) {
                    p.rows.get(index).onClick.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging != null) {
            dragging.x = (int) mouseX - dragOffX;
            dragging.y = (int) mouseY - dragOffY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ловим клавишу для перепривязки бинда
        if (listening) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                listeningSetter.accept(keyCode);
                KillOnLookClient.config.save();
            }
            listening = false;
            listeningGetter = null;
            listeningSetter = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false; // мир не ставится на паузу, пока открыта гуишка
    }
}

// ============================================================
// 4. KillAuraHandler — логика: тригер-бот (мгновенный удар по
//    прицелу), аура (скрытная ротация + удары по кулдауну),
//    аим-ассист (плавный доворот камеры без ударов), бинды,
//    авто-спринт, счёт убийств.
// ============================================================
class KillAuraHandler {

    /** Ванильный радиус атаки в survival — Grim проверяет именно его. */
    private static final double REACH = 3.0;

    /** Плавность доворота: доля оставшегося угла, закрываемая за тик (меньше = плавнее). */
    private static final float TURN_FACTOR = 0.3f;

    /** Потолок скорости доворота (градусы за тик) — чтобы не было рывков. */
    private static final float MAX_TURN_PER_TICK = 5.0f;
    private static final float MAX_PITCH_TURN_PER_TICK = 3.0f;

    /** Сколько сущностей мод убил за сессию. */
    public static int kills = 0;

    /** Скрытная ротация: куда «смотрит» сервер (камера при этом не крутится). */
    private static float fakeYaw = 0f;
    private static float fakePitch = 0f;
    private static float[] pendingFake = null;

    private static int cooldown = 0;    // пауза между ударами ауры
    private static int aimSettled = 0;  // сколько тиков доворот к цели «устоялся»
    private static boolean prevOpenDown = false;
    private static boolean prevToggleDown = false;
    private static LivingEntity lastHit = null;
    private static LivingEntity auraTarget = null; // «липкая» цель наводки

    private KillAuraHandler() {}

    /** Забирает ротацию, которую надо отправить серверу в этом тике (или null). */
    public static float[] pollFakeRotation() {
        float[] f = pendingFake;
        pendingFake = null;
        return f;
    }

    /** Вызывается в начале каждого игрового тика (20 раз в секунду). */
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

        // --- Авто-спринт (независим от главного переключателя) ---
        if (cfg.autoSprint && client.player != null && client.currentScreen == null) {
            client.options.sprintKey.setPressed(true);
        }

        // --- Учёт убийств ---
        if (lastHit != null) {
            if (lastHit.isDead() || !lastHit.isAlive()) {
                kills++;
                lastHit = null;
            } else if (client.world == null || lastHit.getWorld() != client.world) {
                lastHit = null; // сменили мир — счёт не трогаем
            }
        }

        if (cooldown > 0) cooldown--;
        if (client.player == null || !cfg.enabled || client.interactionManager == null) return;
        if (client.currentScreen != null) {
            if (cfg.autoSprint) {
                // при открытой гуишке сбрасываем, чтобы спринт не «залип»
                client.options.sprintKey.setPressed(false);
            }
            return;
        }

        // --- Тригер-бот: МГНОВЕННЫЙ удар, как только прицел коснулся видимой цели ---
        if (cfg.triggerBot
                && client.crosshairTarget instanceof EntityHitResult tHit
                && tHit.getEntity() instanceof LivingEntity tLe
                && tLe != client.player && tLe.isAlive() && cfg.matches(tLe)
                && canSee(client, tLe)) {
            attack(client, cfg, tLe);
        }

        // --- Наводка (аура — скрытная, аим-ассист — камерой) ---
        LivingEntity aimTarget = null;
        boolean lookingAtAimTarget = false;
        if (cfg.aura || cfg.aimAssist) {
            if (client.crosshairTarget instanceof EntityHitResult hit
                    && hit.getEntity() instanceof LivingEntity le
                    && le != client.player && le.isAlive() && cfg.matches(le)) {
                aimTarget = le;
                lookingAtAimTarget = true;
            }
            if (aimTarget == null) aimTarget = findNearest(client, cfg);
        }
        if (aimTarget == null) return;

        if (cfg.aura) {
            // --- АУРА: скрытная ротация + удар по кулдауну ---
            boolean aimReady = true;
            if (lookingAtAimTarget) {
                // камера уже смотрит на цель — сервер видит настоящую ротацию
                fakeYaw = normAngle(client.player.getYaw());
                fakePitch = client.player.getPitch();
            } else {
                // камера стоит на месте, серверу уходит доворот, а на F5 видно тело
                float[] yp = anglesTowards(client.player, aimTarget);
                float yawDiff = normAngle(yp[0] - fakeYaw);
                float pitchDiff = yp[1] - fakePitch;
                if (Math.abs(yawDiff) > 1.0f || Math.abs(pitchDiff) > 1.0f) {
                    float yawStep = Math.max(-MAX_TURN_PER_TICK,
                            Math.min(MAX_TURN_PER_TICK, yawDiff * TURN_FACTOR));
                    float pitchStep = Math.max(-MAX_PITCH_TURN_PER_TICK,
                            Math.min(MAX_PITCH_TURN_PER_TICK, pitchDiff * TURN_FACTOR));
                    fakeYaw = normAngle(fakeYaw + yawStep);
                    fakePitch = Math.max(-90f, Math.min(90f, fakePitch + pitchStep));
                    aimSettled = 0; // доворот ещё идёт
                } else {
                    aimSettled++;
                }
                pendingFake = new float[]{fakeYaw, fakePitch};
                // удар только когда доворот «устоялся» — сервер уже видит ротацию
                aimReady = aimSettled >= 3;
            }

            if (!aimReady) return;
            if (cooldown > 0) return;
            cooldown = Math.max(1, (int) Math.round(20.0 / Math.max(1, cfg.hitsPerSecond)));
            attack(client, cfg, aimTarget);
        } else if (cfg.aimAssist) {
            // --- АИМ-АССИСТ: плавно доворачивает КАМЕРУ к цели, сам не бьёт ---
            float[] yp = anglesTowards(client.player, aimTarget);
            float yawDiff = normAngle(yp[0] - client.player.getYaw());
            float pitchDiff = yp[1] - client.player.getPitch();
            if (Math.abs(yawDiff) > 0.5f || Math.abs(pitchDiff) > 0.5f) {
                float yawStep = Math.max(-MAX_TURN_PER_TICK,
                        Math.min(MAX_TURN_PER_TICK, yawDiff * TURN_FACTOR));
                float pitchStep = Math.max(-MAX_PITCH_TURN_PER_TICK,
                        Math.min(MAX_PITCH_TURN_PER_TICK, pitchDiff * TURN_FACTOR));
                client.player.setYaw(normAngle(client.player.getYaw() + yawStep));
                client.player.setPitch(Math.max(-90f,
                        Math.min(90f, client.player.getPitch() + pitchStep)));
            }
        }
    }

    /** Удар по цели (обычный или мгновенное убийство в одиночной игре). */
    private static void attack(MinecraftClient client, KillAuraConfig cfg, LivingEntity target) {
        lastHit = target;
        client.player.swingHand(Hand.MAIN_HAND);
        if (cfg.instantKill && client.getServer() != null) {
            // Одиночная игра: убиваем сущность напрямую на серверной стороне
            UUID id = target.getUuid();
            RegistryKey<World> worldKey = client.world.getRegistryKey();
            MinecraftServer server = client.getServer();
            server.execute(() -> {
                ServerWorld world = server.getWorld(worldKey);
                if (world == null) return;
                Entity e = world.getEntity(id);
                if (e instanceof LivingEntity le) {
                    le.kill(world);
                }
            });
        } else {
            client.interactionManager.attackEntity(client.player, target);
        }
    }

    /** Ближайшая подходящая сущность в радиусе ванильной атаки (от глаз до хитбокса).
     *  Цель «липкая»: держим предыдущую, пока она жива и в радиусе — иначе при
     *  прыжках выбор мигает между сущностями и доворот дёргается. */
    private static LivingEntity findNearest(MinecraftClient client, KillAuraConfig cfg) {
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();

        // Держим текущую цель, пока она валидна
        if (auraTarget != null) {
            if (auraTarget.isAlive()
                    && auraTarget.getWorld() == client.world
                    && cfg.matches(auraTarget)
                    && eye.squaredDistanceTo(aimPoint(auraTarget, eye)) <= REACH * REACH) {
                return auraTarget;
            }
            auraTarget = null; // умерла/вышла из радиуса/сменился мир — ищем заново
        }

        double bestDist = REACH * REACH;
        LivingEntity best = null;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || le == player || !le.isAlive()) continue;
            if (!cfg.matches(le)) continue;
            double d = eye.squaredDistanceTo(aimPoint(le, eye));
            if (d <= bestDist) {
                bestDist = d;
                best = le;
            }
        }
        auraTarget = best;
        return best;
    }

    /** Видна ли цель (нет ли блоков между глазами и точкой прицеливания). */
    private static boolean canSee(MinecraftClient client, LivingEntity target) {
        Vec3d eyes = client.player.getEyePos();
        Vec3d aim = aimPoint(target, eyes);
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                eyes, aim, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Точка прицеливания: центр хитбокса. Ближайшая точка хитбокса была
     *  нестабильной — при движении она прыгала с края на край, и голова
     *  дёргалась. Луч через центр всегда попадает в хитбокс. */
    private static Vec3d aimPoint(LivingEntity target, Vec3d eye) {
        return target.getBoundingBox().getCenter();
    }

    /** Вычисляет yaw/pitch от глаз игрока к точке прицеливания. */
    private static float[] anglesTowards(ClientPlayerEntity player, LivingEntity target) {
        Vec3d eye = player.getEyePos();
        Vec3d aim = aimPoint(target, eye);
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        return new float[]{yaw, pitch};
    }

    /** Нормализует угол в диапазон [-180; 180) (используется и в миксине). */
    public static float normAngle(float a) {
        return ((a % 360.0f) + 540.0f) % 360.0f - 180.0f;
    }
}

// ============================================================
// 5. ClientPlayerEntityMixin (миксин) — сердце скрытной ротации.
//    Прямо перед отправкой movement-пакетов подставляет «фейковые»
//    yaw/pitch (доворот к цели), сразу после — возвращает настоящую
//    камеру. Здесь же плавно доворачивается тело (видно на F5).
// ============================================================
@Mixin(net.minecraft.client.network.ClientPlayerEntity.class)
class ClientPlayerEntityMixin {

    private boolean killonlook$fakeApplied = false;
    private float killonlook$realYaw;
    private float killonlook$realPitch;

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void killonlook$applyFakeRotation(CallbackInfo ci) {
        float[] fake = KillAuraHandler.pollFakeRotation();
        if (fake != null) {
            net.minecraft.client.network.ClientPlayerEntity self =
                    (net.minecraft.client.network.ClientPlayerEntity) (Object) this;
            killonlook$realYaw = self.getYaw();
            killonlook$realPitch = self.getPitch();
            self.setYaw(fake[0]);
            self.setPitch(fake[1]);

            // Тело плавно доворачивается за фейковой ротацией (видно на F5).
            // Ставим именно здесь — ПОСЛЕ ванильного пересчёта bodyYaw/headYaw
            // в tick(), иначе ванила откатывает их под камеру и голова дёргается.
            float bodyDiff = KillAuraHandler.normAngle(fake[0] - self.bodyYaw);
            self.bodyYaw += bodyDiff * 0.3f;
            self.headYaw = fake[0];
            killonlook$fakeApplied = true;
        }
    }

    @Inject(method = "sendMovementPackets", at = @At("TAIL"))
    private void killonlook$restoreRotation(CallbackInfo ci) {
        if (killonlook$fakeApplied) {
            net.minecraft.client.network.ClientPlayerEntity self =
                    (net.minecraft.client.network.ClientPlayerEntity) (Object) this;
            self.setYaw(killonlook$realYaw);
            self.setPitch(killonlook$realPitch);
            killonlook$fakeApplied = false;
        }
    }
}

// ============================================================
// 6. KillAuraHud — HUD-элементы: водяной знак, счётчик убийств
//    за сессию и список включённых модулей справа (как ArrayList).
// ============================================================
class KillAuraHud {

    private static final int BG = 0x90000000;     // полупрозрачная плашка
    private static final int ACCENT = 0xFFC79BF2; // фиолетовый акцент
    private static final int GOOD = 0xFF8FFF8F;   // зелёный

    private KillAuraHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(KillAuraHud::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        KillAuraConfig cfg = KillOnLookClient.config;

        // --- Водяной знак ---
        context.fill(3, 3, 112, 15, BG);
        context.drawTextWithShadow(client.textRenderer, "Kill on Look v1.2.0", 6, 5, ACCENT);

        // --- Счётчик убийств за сессию ---
        if (KillAuraHandler.kills > 0) {
            String k = "убийств: " + KillAuraHandler.kills;
            int w = client.textRenderer.getWidth(k);
            context.fill(3, 16, w + 14, 28, BG);
            context.drawTextWithShadow(client.textRenderer, k, 6, 18, GOOD);
        }

        // --- Список включённых модулей (справа, как ArrayList) ---
        List<String> mods = new ArrayList<>();
        if (cfg.enabled) {
            if (cfg.aura) mods.add("Аура");
            if (cfg.aimAssist) mods.add("Аим-ассист");
            if (cfg.triggerBot) mods.add("Тригер-бот");
            if (cfg.autoSprint) mods.add("Авто-спринт");

            List<String> tg = new ArrayList<>();
            if (cfg.hostile) tg.add("мобы");
            if (cfg.animals) tg.add("животные");
            if (cfg.villagers) tg.add("жители");
            if (cfg.players) tg.add("игроки");
            if (!tg.isEmpty()) mods.add("цели: " + String.join(", ", tg));
        }

        int sw = client.getWindow().getScaledWidth();
        int y = 3;
        for (String m : mods) {
            int w = client.textRenderer.getWidth(m);
            int x = sw - w - 5;
            context.fill(x - 2, y - 1, x + w + 2, y + 9, BG);
            context.drawTextWithShadow(client.textRenderer, m, x, y, ACCENT);
            y += 10;
        }
    }
}
