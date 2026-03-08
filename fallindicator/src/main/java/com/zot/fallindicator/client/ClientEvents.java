package com.zot.fallindicator.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.zot.fallindicator.FallIndicatorMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Только клиент — никогда не вызывается на сервере
@Mod.EventBusSubscriber(
    modid = FallIndicatorMod.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public class ClientEvents {

    // ─── Глобальное состояние (обновляется каждый тик) ───
    private static BlockPos landingPos      = null;  // блок приземления
    private static float    totalFallDist   = 0f;    // полная высота падения
    private static int      predictedDamage = 0;     // урон в HP (half-hearts)
    private static boolean  isFalling       = false; // игрок падает?
    private static boolean  landInWater     = false; // приземление в воду?

    // ══════════════════════════════════════════════════
    //  ТИКИ — обновление состояния каждый клиентский тик
    // ══════════════════════════════════════════════════
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // Сбрасываем если нет игрока или пауза
        if (player == null || mc.level == null || mc.isPaused()) {
            resetState();
            return;
        }

        // Определяем: игрок падает (движется вниз, не на земле, не в жидкости)
        boolean movingDown  = player.getDeltaMovement().y() < -0.08;
        boolean notOnGround = !player.onGround();
        boolean notInFluid  = !player.isInWater() && !player.isInLava();

        isFalling = notOnGround && movingDown && notInFluid;

        if (!isFalling) {
            resetState();
            return;
        }

        // Находим блок приземления рейкастом вниз
        landingPos = findLandingPos(player, mc.level);

        if (landingPos == null) {
            predictedDamage = 0;
            totalFallDist   = player.fallDistance;
            return;
        }

        // Проверяем: над блоком приземления есть вода → урона нет
        landInWater = !mc.level.getFluidState(landingPos.above()).isEmpty();

        // Полная высота = уже пройдено + расстояние до точки приземления
        double remaining = player.getY() - (landingPos.getY() + 1.0);
        totalFallDist   = player.fallDistance + (float) Math.max(0.0, remaining);

        // Рассчитываем урон
        predictedDamage = landInWater ? 0 : calculateFallDamage(player, totalFallDist);
    }

    // ══════════════════════════════════════════════════
    //  РЕЙКАСТ — ищем первый твёрдый блок под игроком
    // ══════════════════════════════════════════════════
    private static BlockPos findLandingPos(LocalPlayer player, Level level) {
        Vec3 start = player.position();

        // Максимальная глубина поиска — до минимальной высоты мира
        double maxDepth = Math.min(512.0, start.y - level.getMinBuildHeight() + 2);
        Vec3 end = new Vec3(start.x, start.y - maxDepth, start.z);

        ClipContext ctx = new ClipContext(
            start, end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            player
        );

        BlockHitResult result = level.clip(ctx);
        return result.getType() == HitResult.Type.BLOCK ? result.getBlockPos() : null;
    }

    // ══════════════════════════════════════════════════
    //  РАСЧЁТ УРОНА — повторяем логику LivingEntity
    // ══════════════════════════════════════════════════
    private static int calculateFallDamage(LocalPlayer player, float fallDistance) {
        // Базовый урон: высота минус 3 безопасных блока
        int baseDamage = Mth.ceil(fallDistance) - 3;
        if (baseDamage <= 0) return 0;

        // Эффект медленного падения отменяет урон полностью
        if (player.hasEffect(MobEffects.SLOW_FALLING)) return 0;

        float damage = (float) baseDamage;

        // Снижение от брони (формула Minecraft: CombatRules)
        float armor     = (float) player.getAttributeValue(Attributes.ARMOR);
        float toughness = (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        damage = CombatRules.getDamageAfterAbsorb(damage, armor, toughness);

        // Зачарование «Перо падения» (Feather Falling)
        // Каждый уровень даёт 3 EPF (enchantment protection factor)
        // Формула снижения: min(epf, 20) / 25
        int featherLevel = EnchantmentHelper.getEnchantmentLevel(
            Enchantments.FALL_PROTECTION,
            player.getItemBySlot(EquipmentSlot.FEET)
        );
        if (featherLevel > 0) {
            float epf = Math.min(featherLevel * 3, 20); // максимум 20 EPF
            damage *= (1.0f - epf / 25.0f);
        }

        return Math.max(0, Mth.ceil(damage));
    }

    // ══════════════════════════════════════════════════
    //  РЕНДЕР МИРА — рисуем индикатор на блоке приземления
    // ══════════════════════════════════════════════════
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!isFalling || landingPos == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();

        ps.pushPose();
        // Переводим координаты относительно камеры
        ps.translate(-camPos.x, -camPos.y, -camPos.z);

        float[] col   = getColorByDamage(predictedDamage);
        AABB    box   = new AABB(landingPos).inflate(0.005);

        Tesselator    tess   = Tesselator.getInstance();
        BufferBuilder buf    = tess.getBuilder();

        // ── Контур блока (линии) ──────────────────────────
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.5f);

        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        LevelRenderer.renderLineBox(ps, buf, box, col[0], col[1], col[2], 1.0f);
        tess.end();

        // ── Залитая верхняя грань ─────────────────────────
        PoseStack.Pose pose = ps.last();
        float x0 = (float) box.minX, x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z0 = (float) box.minZ, z1 = (float) box.maxZ;

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(pose.pose(), x0, y1, z0).color(col[0], col[1], col[2], 0.38f).endVertex();
        buf.vertex(pose.pose(), x1, y1, z0).color(col[0], col[1], col[2], 0.38f).endVertex();
        buf.vertex(pose.pose(), x1, y1, z1).color(col[0], col[1], col[2], 0.38f).endVertex();
        buf.vertex(pose.pose(), x0, y1, z1).color(col[0], col[1], col[2], 0.38f).endVertex();
        tess.end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        ps.popPose();
    }

    // ══════════════════════════════════════════════════
    //  РЕНДЕР HUD — текстовая информация на экране
    // ══════════════════════════════════════════════════
    @SubscribeEvent
    public static void onRenderHUD(RenderGuiOverlayEvent.Post event) {
        // Вешаемся на Hotbar — один из последних слоёв HUD
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!isFalling) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // Панель: левый нижний угол, чуть выше хотбара
        int panelX = 4;
        int panelY = sh - 76;

        var gg   = event.getGuiGraphics();
        var font = mc.font;

        // ── Фоновый прямоугольник ─────────────────────────
        gg.fill(panelX - 2, panelY - 2, panelX + 118, panelY + 34, 0x88000000);

        // ── Строка 1: Высота падения ──────────────────────
        String lineHeight = String.format("§7Высота: §f%.1f §7блок.", totalFallDist);
        gg.drawString(font, lineHeight, panelX, panelY, 0xFFFFFF, true);

        // ── Строка 2: Урон в сердечках и HP ──────────────
        String lineDamage;
        if (landInWater) {
            lineDamage = "§b⬇ Вода — урона нет ✓";
        } else if (predictedDamage == 0) {
            lineDamage = "§aБезопасно ✓";
        } else {
            // Переводим HP → сердечки (1 heart = 2 HP)
            float hearts = predictedDamage / 2.0f;
            String heartsStr = (hearts == Math.floor(hearts))
                ? String.valueOf((int) hearts)
                : String.format("%.1f", hearts);

            // Цвет зависит от тяжести урона
            String colorCode = (predictedDamage >= 20) ? "§4"
                             : (predictedDamage >= 10) ? "§c"
                             : (predictedDamage >= 4)  ? "§e"
                             :                           "§a";

            lineDamage = String.format("§7Урон: %s%s❤ §7(%d HP)", colorCode, heartsStr, predictedDamage);
        }
        gg.drawString(font, lineDamage, panelX, panelY + 11, 0xFFFFFF, true);

        // ── Строка 3: Y координата приземления ───────────
        if (landingPos != null) {
            String lineY = String.format("§7Точка: §fX%d Y%d Z%d",
                landingPos.getX(), landingPos.getY() + 1, landingPos.getZ());
            gg.drawString(font, lineY, panelX, panelY + 22, 0xFFFFFF, true);
        }
    }

    // ══════════════════════════════════════════════════
    //  ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ══════════════════════════════════════════════════

    // Цвет: зелёный (безопасно) → жёлтый → оранжевый → красный (смерть)
    private static float[] getColorByDamage(int damage) {
        if (damage == 0)  return new float[]{0.1f, 0.95f, 0.1f};  // зелёный
        if (damage < 5)   return new float[]{0.6f, 1.0f,  0.0f};  // жёлто-зелёный
        if (damage < 10)  return new float[]{1.0f, 0.75f, 0.0f};  // жёлтый
        if (damage < 18)  return new float[]{1.0f, 0.35f, 0.0f};  // оранжевый
        return                   new float[]{1.0f, 0.0f,  0.0f};  // красный
    }

    private static void resetState() {
        isFalling       = false;
        landingPos      = null;
        totalFallDist   = 0f;
        predictedDamage = 0;
        landInWater     = false;
    }
}
