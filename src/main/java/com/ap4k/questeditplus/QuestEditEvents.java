package com.ap4k.questeditplus;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.gui.CustomToast;
import dev.ftb.mods.ftbquests.client.gui.quests.ChapterImageButton;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestButton;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestPanel;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.net.EditObjectMessage;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import dev.ftb.mods.ftbquests.quest.Movable;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.QuestShape;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.ScreenWrapper;
import dev.ftb.mods.ftblibrary.ui.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Client-side editing QoL for the FTB Quests book:
 * - Alt + LMB drag from a quest to another: toggle a dependency (with live line preview)
 * - Alt + LMB click (no drag): opens the vanilla edit overlay, same as before
 * - Alt + mouse wheel over a quest / chapter image: resize it, content stays under the cursor
 * - Moving quests shows the real quest icons instead of FTB's plain white ghost circles
 */
@EventBusSubscriber(modid = QuestEditPlus.MODID, value = Dist.CLIENT)
public final class QuestEditEvents {
    private static final double DEP_DRAG_THRESHOLD = 6.0;   // px before click becomes a drag
    private static final double RESIZE_FACTOR = 1.1;
    private static final double MIN_QUEST_SIZE = 0.0625, MAX_QUEST_SIZE = 8.0;
    private static final double MIN_IMAGE_SIZE = 0.5, MAX_IMAGE_SIZE = 64.0;
    private static final long SEND_DELAY_MS = 200L;

    private static Quest dragSource;
    private static double pressX, pressY;
    private static double mouseX, mouseY; // last known, for rendering

    // edits are coalesced: one packet per ~200ms instead of one per wheel tick
    private static final List<QuestObjectBase> dirty = new ArrayList<>();
    private static long lastDirtyAt;

    // ChapterImage keeps its width/height as private fields; reflection is the only way in
    private static final Field IMG_WIDTH;
    private static final Field IMG_HEIGHT;
    // QuestPanel keeps its content transform and selection private/package-private
    private static final Field PANEL_QUEST_X, PANEL_QUEST_Y;
    private static final Field PANEL_MIN_X, PANEL_MIN_Y;
    private static final Field SCREEN_MOVING_OBJECTS, SCREEN_SELECTED_OBJECTS;
    // Quest stores its custom shape as a private field, no public setter
    private static final Field QUEST_SHAPE_FIELD;
    private static final Field SHAPE_MAP_FIELD;

    static {
        try {
            IMG_WIDTH = ChapterImage.class.getDeclaredField("width");
            IMG_WIDTH.setAccessible(true);
            IMG_HEIGHT = ChapterImage.class.getDeclaredField("height");
            IMG_HEIGHT.setAccessible(true);

            PANEL_QUEST_X = field(QuestPanel.class, "questX");
            PANEL_QUEST_Y = field(QuestPanel.class, "questY");
            PANEL_MIN_X = field(QuestPanel.class, "questMinX");
            PANEL_MIN_Y = field(QuestPanel.class, "questMinY");
            SCREEN_MOVING_OBJECTS = field(QuestScreen.class, "movingObjects");
            SCREEN_SELECTED_OBJECTS = field(QuestScreen.class, "selectedObjects");

            QUEST_SHAPE_FIELD = field(Quest.class, "shape");
            SHAPE_MAP_FIELD = QuestShape.class.getDeclaredField("MAP");
            SHAPE_MAP_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("questeditplus: FTB Quests internals not found", e);
        }
    }

    private QuestEditEvents() {
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ------------------------------------------------------------------
    // Mouse press
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onPress(ScreenEvent.MouseButtonPressed.Pre e) {
        QuestScreen qs = asQuestScreen(e.getScreen());
        if (qs == null || !canEdit()) return;
        flushDirty();

        // RMB while dependency-dragging cancels it
        if (dragSource != null) {
            if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                cancelDrag();
                e.setCanceled(true);
            }
            return;
        }

        if (e.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT || qs.getSelectedChapter().isEmpty()) return;

        mouseX = e.getMouseX();
        mouseY = e.getMouseY();

        // Alt + LMB on a quest: begin a dependency drag
        if (Screen.hasAltDown()) {
            Quest q = questUnderMouse(qs);
            if (q != null) {
                dragSource = q;
                pressX = e.getMouseX();
                pressY = e.getMouseY();
                e.setCanceled(true);
            }
        }
    }

    // ------------------------------------------------------------------
    // Mouse release: finish the drag (or pass through as a plain alt-click edit)
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRelease(ScreenEvent.MouseButtonReleased.Pre e) {
        if (dragSource == null || e.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;

        Quest source = dragSource;
        double dist = Math.hypot(e.getMouseX() - pressX, e.getMouseY() - pressY);
        cancelDrag();

        QuestScreen qs = asQuestScreen(e.getScreen());
        if (qs == null || !canEdit()) return;
        e.setCanceled(true);

        if (dist < DEP_DRAG_THRESHOLD) {
            // plain alt-click: open the vanilla edit overlay
            source.onEditButtonClicked(qs);
            return;
        }

        Quest target = questUnderMouse(qs);
        if (target != null && target != source) {
            // if the drag starts/ends on a multi-selection, apply to every selected quest
            Collection<Quest> sel = qs.getSelectedQuests();
            List<Quest> sources = (sel.size() > 1 && sel.contains(source)) ? List.copyOf(sel) : List.of(source);
            List<Quest> targets = (sel.size() > 1 && sel.contains(target)) ? List.copyOf(sel) : List.of(target);

            List<QuestObject> changed = new ArrayList<>();
            boolean added = false;
            for (Quest t : targets) {
                for (Quest s : sources) {
                    if (s == t) continue;
                    boolean add = !t.hasDependency(s);
                    List<QuestObject> prev = t.streamDependencies().toList();

                    if (add) {
                        t.addDependency(s);
                    } else {
                        t.removeDependency(s);
                    }
                    t.removeInvalidDependencies();

                    if (t.verifyDependencies(false)) {
                        changed.add(t);
                        added = add;
                    } else {
                        // rollback, same protection FTB uses against loops
                        t.clearDependencies();
                        prev.forEach(t::addDependency);
                        QuestScreen.displayError(Component.translatable("questeditplus.toast.loop"));
                    }
                }
            }

            if (!changed.isEmpty()) {
                NetworkManager.sendToServer(EditObjectMessage.forQuestObjects(changed));
                qs.questPanel.refreshWidgets();
                ToastHandler.showDependencyToast(sources.getFirst(), targets.getFirst(), added);
            }
        }
    }

    // ------------------------------------------------------------------
    // Alt + wheel: resize quests / images
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre e) {
        QuestScreen qs = asQuestScreen(e.getScreen());
        if (qs == null || !canEdit() || qs.getSelectedChapter().isEmpty()) return;
        if (qs.isViewingQuest()) return;

        double f = e.getScrollDeltaY() > 0 ? RESIZE_FACTOR
                : e.getScrollDeltaY() < 0 ? 1.0 / RESIZE_FACTOR : 0.0;
        if (f == 0.0) return;

        // Ctrl + wheel: resize quest / image under the cursor, aspect kept for images
        if (Screen.hasControlDown()) {
            // image under the cursor -> scale it, aspect ratio kept
            ChapterImage img = imageUnderMouse(qs);
            if (img != null) {
                try {
                    double w = IMG_WIDTH.getDouble(img);
                    double h = IMG_HEIGHT.getDouble(img);
                    IMG_WIDTH.setDouble(img, clamp(w * f, MIN_IMAGE_SIZE, MAX_IMAGE_SIZE));
                    IMG_HEIGHT.setDouble(img, clamp(h * f, MIN_IMAGE_SIZE, MAX_IMAGE_SIZE));
                } catch (IllegalAccessException ex) {
                    return;
                }
                markDirty(img);
                refreshKeepingAnchor(qs, img);
                e.setCanceled(true);
                return;
            }

            // quest under the cursor -> scale it (all selected quests if it's part of the selection)
            Quest q = questUnderMouse(qs);
            if (q != null) {
                Collection<Quest> sel = qs.getSelectedQuests();
                List<Quest> toChange = (sel.size() > 1 && sel.contains(q)) ? List.copyOf(sel) : List.of(q);
                toChange.forEach(x -> x.setSize(clamp(x.getSize() * f, MIN_QUEST_SIZE, MAX_QUEST_SIZE)));
                markDirtyAll(toChange);
                refreshKeepingAnchor(qs, q);
                e.setCanceled(true);
            }
            return;
        }

        // Alt + wheel: cycle the quest shape (circle -> square -> ...); selection-aware
        if (Screen.hasAltDown()) {
            Quest q = questUnderMouse(qs);
            if (q == null) return;
            List<String> shapeIds = availableShapeIds();
            if (shapeIds.isEmpty()) return;
            Collection<Quest> sel = qs.getSelectedQuests();
            List<Quest> toChange = (sel.size() > 1 && sel.contains(q)) ? List.copyOf(sel) : List.of(q);
            boolean up = e.getScrollDeltaY() > 0;
            int n = shapeIds.size();
            for (Quest t : toChange) {
                int i = shapeIds.indexOf(t.getShape());
                if (i < 0) i = 0;
                i = up ? (i + 1) % n : (i - 1 + n) % n;
                try {
                    QUEST_SHAPE_FIELD.set(t, shapeIds.get(i));
                } catch (IllegalAccessException ignored) {
                }
            }
            markDirtyAll(toChange);
            refreshKeepingAnchor(qs, q);
            Minecraft.getInstance().getToasts().addToast(new CustomToast(
                    Component.literal("Form: ").append(Component.translatable("ftbquests.quest.shape." + shapeIds.get(
                            Math.max(0, shapeIds.indexOf(q.getShape()))))),
                    Icons.INFO, Component.empty()));
            e.setCanceled(true);
        }
    }

    // QuestShape.MAP is filled by FTB during resource reload, so it has to be read lazily
    @SuppressWarnings("unchecked")
    private static List<String> availableShapeIds() {
        try {
            Map<String, QuestShape> map = (Map<String, QuestShape>) SHAPE_MAP_FIELD.get(null);
            return new ArrayList<>(map.keySet());
        } catch (IllegalAccessException ex) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Esc cancels the drag
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onKey(ScreenEvent.KeyPressed.Pre e) {
        flushDirty();
        QuestScreen qs = asQuestScreen(e.getScreen());
        if (qs != null && canEdit() && dragSource == null && !qs.getSelectedChapter().isEmpty()
                && !qs.isViewingQuest() && qs.questPanel.isMouseOver()) {
            boolean movingNow;
            try {
                movingNow = SCREEN_MOVING_OBJECTS.getBoolean(qs);
            } catch (IllegalAccessException ex) {
                movingNow = false;
            }
            int code = e.getKeyCode();
            int dx = code == GLFW.GLFW_KEY_RIGHT ? 1 : code == GLFW.GLFW_KEY_LEFT ? -1 : 0;
            int dy = code == GLFW.GLFW_KEY_DOWN ? 1 : code == GLFW.GLFW_KEY_UP ? -1 : 0;
            if ((dx != 0 || dy != 0) && !movingNow) {
                List<Movable> sel = selectedMovables(qs);
                if (sel != null && !sel.isEmpty()) {
                    // arrows nudge the selection: 0.5 of a cell, Shift for fine 0.1 steps
                    double step = Screen.hasShiftDown() ? 0.1 : 0.5;
                    for (Movable m : sel) {
                        if (!m.isPositionLocked()) {
                            m.setPosition(m.getX() + dx * step, m.getY() + dy * step);
                        }
                    }
                    List<QuestObjectBase> dirtyObjs = new ArrayList<>();
                    for (Movable m : sel) {
                        if (m instanceof QuestObjectBase qob) dirtyObjs.add(qob);
                    }
                    markDirtyAll(dirtyObjs);
                    refreshKeepingAnchor(qs, sel.getFirst());
                    e.setCanceled(true);
                }
            }
        }
        if (dragSource != null && e.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            cancelDrag();
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------
    // Rendering: dependency drag line + real quests while moving
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post e) {
        QuestScreen qs = asQuestScreen(e.getScreen());
        if (qs == null || !canEdit()) return;

        flushDirtyIfIdle();

        mouseX = e.getMouseX();
        mouseY = e.getMouseY();

        if (dragSource != null) {
            drawDependencyDrag(qs, e.getGuiGraphics());
        }
        boolean moving = false;
        try {
            moving = SCREEN_MOVING_OBJECTS.getBoolean(qs);
        } catch (IllegalAccessException ignored) {
        }
        updateWidgetVisibility(qs, moving);
        if (moving) {
            snapToGuides(qs);
            drawMovingPreview(qs, e.getGuiGraphics());
            drawGuides(qs, e.getGuiGraphics());
        }
    }

    private static void drawDependencyDrag(QuestScreen qs, GuiGraphics g) {
        QuestButton srcBtn = buttonOf(qs, dragSource);
        if (srcBtn == null) return;

        double sx = centerX(qs, srcBtn);
        double sy = centerY(qs, srcBtn);
        double dx = mouseX - sx, dy = mouseY - sy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 2.0) return;

        Quest target = questUnderMouse(qs);
        boolean remove = target != null && target != dragSource && target.hasDependency(dragSource);
        int color = remove ? 0xFF6060 : 0x60FF80;

        double ux = dx / len, uy = dy / len;
        double spacing = 7.0;
        double anim = (System.currentTimeMillis() / 60.0) % spacing;
        for (double d = 3.0 + anim; d < len - 3.0; d += spacing) {
            int px = (int) Math.round(sx + ux * d);
            int py = (int) Math.round(sy + uy * d);
            g.fill(px - 1, py - 1, px + 1, py + 1, 0xC8000000 | (color & 0xFFFFFF));
        }

        // outline the quest currently under the cursor
        if (target != null && target != dragSource) {
            QuestButton tb = buttonOf(qs, target);
            if (tb != null) {
                QuestShape.get(target.getShape()).getOutline()
                        .withColor(Color4I.rgb(color))
                        .draw(g, (int) Math.round(left(qs, tb)), (int) Math.round(top(qs, tb)),
                                tb.getWidth(), tb.getHeight());
            }
        }

        g.drawString(Minecraft.getInstance().font,
                Component.translatable("questeditplus.hint.drag"),
                6, qs.height - 20, 0xFFFFFF, true);
    }

    /**
     * FTB renders selected objects as plain white shapes while moving. We paint the real
     * quest (shape + icon) on top of every ghost, so dragging looks like actual movement.
     */
    private static void drawMovingPreview(QuestScreen qs, GuiGraphics g) {
        List<Movable> selected = selectedMovables(qs);
        if (selected == null || selected.isEmpty()) return;
        if (!qs.questPanel.isMouseOver()) return;

        double qX, qY, minX, minY;
        try {
            qX = PANEL_QUEST_X.getDouble(qs.questPanel);
            qY = PANEL_QUEST_Y.getDouble(qs.questPanel);
            minX = PANEL_MIN_X.getDouble(qs.questPanel);
            minY = PANEL_MIN_Y.getDouble(qs.questPanel);
        } catch (IllegalAccessException ex) {
            return;
        }

        double ominX = Double.POSITIVE_INFINITY, ominY = Double.POSITIVE_INFINITY;
        for (Movable m : selected) {
            ominX = Math.min(ominX, m.getX());
            ominY = Math.min(ominY, m.getY());
        }

        double bs = qs.getQuestButtonSize();
        double unit = bs + qs.getQuestButtonSpacing();
        double px = qs.questPanel.getX() - qs.questPanel.getScrollX();
        double py = qs.questPanel.getY() - qs.questPanel.getScrollY();

        for (Movable m : selected) {
            if (m.isPositionLocked()) continue;

            double sx = px + (qX + (m.getX() - ominX) - minX) * unit;
            double sy = py + (qY + (m.getY() - ominY) - minY) * unit;

            if (m instanceof ChapterImage img) {
                // images are placed centered too; no backdrop, just the picture at full size
                int w = (int) Math.round(bs * m.getWidth());
                int h = (int) Math.round(bs * m.getHeight());
                int x = (int) Math.round(sx - w / 2.0);
                int y = (int) Math.round(sy - h / 2.0);
                Icon icon = img.getImage();
                if (!icon.isEmpty()) {
                    // rotation pivot: top-left corner when align-to-corner, otherwise the center (same as FTB)
                    float offX = img.isAlignToCorner() ? 0 : w / 2F;
                    float offY = img.isAlignToCorner() ? 0 : h / 2F;
                    var pose = g.pose();
                    pose.pushPose();
                    pose.translate(x + offX, y + offY, 0);
                    pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) img.getRotation()));
                    pose.translate(-offX, -offY, 0);
                    icon.draw(g, 0, 0, w, h);
                    pose.popPose();
                }
                continue;
            }

            int w = (int) Math.round(bs * m.getWidth());
            int h = (int) Math.round(bs * m.getHeight());
            int x = (int) Math.round(sx - w / 2.0);
            int y = (int) Math.round(sy - h / 2.0);

            QuestShape shape = QuestShape.get(m.getShape());
            shape.getShape().withColor(Color4I.DARK_GRAY).draw(g, x, y, w, h);
            shape.getBackground().withColor(Color4I.WHITE.withAlpha(230)).draw(g, x, y, w, h);
            shape.getOutline().withColor(Color4I.WHITE).draw(g, x, y, w, h);

            if (m instanceof QuestObjectBase qob) {
                Icon icon = qob.getIcon();
                if (!icon.isEmpty()) {
                    int s = (int) (w * 2F / 3F);
                    icon.draw(g, x + (w - s) / 2, y + (h - s) / 2, s, s);
                }
            }
        }
    }

    // while FTB's move mode is active we slide the actual quest widgets along with the
    // ghost, so the dependency lines and arrows follow in real time
    private static boolean wasMoving;

    @SuppressWarnings("unchecked")
    private static List<Movable> selectedMovables(QuestScreen qs) {
        try {
            Object sel = SCREEN_SELECTED_OBJECTS.get(qs);
            if (!(sel instanceof List<?> raw)) return null;
            List<Movable> out = new ArrayList<>(raw.size());
            for (Object o : raw) {
                if (o instanceof Movable m) out.add(m);
            }
            return out;
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private static void updateWidgetVisibility(QuestScreen qs, boolean moving) {
        if (moving != wasMoving) {
            wasMoving = moving;
            if (!moving) {
                qs.questPanel.refreshWidgets();
                return;
            }
        }
        if (!moving) return;

        List<Movable> sel = selectedMovables(qs);
        if (sel == null || sel.isEmpty()) return;

        try {
            double qX = PANEL_QUEST_X.getDouble(qs.questPanel);
            double qY = PANEL_QUEST_Y.getDouble(qs.questPanel);
            double minX = PANEL_MIN_X.getDouble(qs.questPanel);
            double minY = PANEL_MIN_Y.getDouble(qs.questPanel);
            double bs = qs.getQuestButtonSize();
            double bp = qs.getQuestButtonSpacing();
            double unit = bs + bp;

            double ominX = Double.POSITIVE_INFINITY, ominY = Double.POSITIVE_INFINITY;
            for (Movable m : sel) {
                ominX = Math.min(ominX, m.getX());
                ominY = Math.min(ominY, m.getY());
            }

            for (Movable m : sel) {
                if (m.isPositionLocked()) continue;
                Widget w = widgetOf(qs, m);
                if (w == null) continue;

                double qx = qX + (m.getX() - ominX);
                double qy = qY + (m.getY() - ominY);
                double qw = m.getWidth(), qh = m.getHeight();
                double x = (qx - minX - qw / 2D) * unit + bp / 2D + bp * (qw - 1D) / 2D;
                double y = (qy - minY - qh / 2D) * unit + bp / 2D + bp * (qh - 1D) / 2D;
                w.setPosAndSize((int) Math.round(x), (int) Math.round(y),
                        (int) Math.round(bs * qw), (int) Math.round(bs * qh));
            }

            // dependency lines cache their geometry per widget; without this they
            // keep pointing where the quest used to be
            for (Widget w : qs.questPanel.getWidgets()) {
                if (w instanceof QuestButton qb) {
                    qb.positionControlPoints();
                }
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Magnetic guides: while moving, snap to aligned centers of other quests
    // ------------------------------------------------------------------

    private static double guideX = -1, guideY = -1;

    private static void snapToGuides(QuestScreen qs) {
        guideX = guideY = -1;
        // Shift = fully free placement: FTB already unsnaps the grid, we skip magnets too
        if (Screen.hasShiftDown()) return;
        if (!qs.questPanel.isMouseOver()) return;

        List<Movable> sel = selectedMovables(qs);
        if (sel == null || sel.isEmpty()) return;

        try {
            double qX = PANEL_QUEST_X.getDouble(qs.questPanel);
            double qY = PANEL_QUEST_Y.getDouble(qs.questPanel);
            double minX = PANEL_MIN_X.getDouble(qs.questPanel);
            double minY = PANEL_MIN_Y.getDouble(qs.questPanel);
            double bs = qs.getQuestButtonSize();
            double unit = bs + qs.getQuestButtonSpacing();
            double px = qs.questPanel.getX() - qs.questPanel.getScrollX();
            double py = qs.questPanel.getY() - qs.questPanel.getScrollY();

            double ominX = Double.POSITIVE_INFINITY, ominY = Double.POSITIVE_INFINITY;
            for (Movable m : sel) {
                ominX = Math.min(ominX, m.getX());
                ominY = Math.min(ominY, m.getY());
            }

            // the anchor is the first movable; its ghost center is what we align
            Movable anchor = null;
            for (Movable m : sel) {
                if (!m.isPositionLocked()) {
                    anchor = m;
                    break;
                }
            }
            if (anchor == null) return;

            double cx = px + (qX + (anchor.getX() - ominX) - minX) * unit;
            double cy = py + (qY + (anchor.getY() - ominY) - minY) * unit;

            double bestDX = Double.MAX_VALUE, bestDY = Double.MAX_VALUE;
            double snapCx = 0, snapCy = 0;

            for (Widget w : qs.questPanel.getWidgets()) {
                if (!(w instanceof dev.ftb.mods.ftbquests.client.gui.quests.QuestPositionableButton qb)) continue;
                Movable om = qb.moveAndDeleteFocus();
                if (om == null) continue;
                boolean isSel = false;
                for (Movable m : sel) {
                    if (m == om) {
                        isSel = true;
                        break;
                    }
                }
                if (isSel) continue;

                double ocx = qs.questPanel.getX() - qs.questPanel.getScrollX() + w.getPosX() + w.getWidth() / 2.0;
                double ocy = qs.questPanel.getY() - qs.questPanel.getScrollY() + w.getPosY() + w.getHeight() / 2.0;

                double dx = ocx - cx, dy = ocy - cy;
                if (Math.abs(dx) < 6.0 && Math.abs(dx) < Math.abs(bestDX)) {
                    bestDX = dx;
                    snapCx = ocx;
                }
                if (Math.abs(dy) < 6.0 && Math.abs(dy) < Math.abs(bestDY)) {
                    bestDY = dy;
                    snapCy = ocy;
                }
            }

            if (bestDX != Double.MAX_VALUE) {
                PANEL_QUEST_X.setDouble(qs.questPanel, (snapCx - px) / unit - (anchor.getX() - ominX) + minX);
                guideX = snapCx;
            }
            if (bestDY != Double.MAX_VALUE) {
                PANEL_QUEST_Y.setDouble(qs.questPanel, (snapCy - py) / unit - (anchor.getY() - ominY) + minY);
                guideY = snapCy;
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    private static void drawGuides(QuestScreen qs, GuiGraphics g) {
        int color = 0xB000E5FF;
        if (guideX >= 0) {
            g.fill((int) Math.round(guideX), qs.questPanel.getY(),
                    (int) Math.round(guideX) + 1, qs.questPanel.getY() + qs.questPanel.getHeight(), color);
        }
        if (guideY >= 0) {
            g.fill(qs.questPanel.getX(), (int) Math.round(guideY),
                    qs.questPanel.getX() + qs.questPanel.getWidth(), (int) Math.round(guideY) + 1, color);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean canEdit() {
        return ClientQuestFile.exists() && ClientQuestFile.getInstance().canEdit();
    }

    // FTB renders its quest book inside a vanilla Screen wrapper
    private static QuestScreen asQuestScreen(net.minecraft.client.gui.screens.Screen s) {
        return s instanceof ScreenWrapper sw && sw.getGui() instanceof QuestScreen qs ? qs : null;
    }

    private static Quest questUnderMouse(QuestScreen qs) {
        for (Widget w : qs.questPanel.getWidgets()) {
            if (w instanceof QuestButton qb && qb.isMouseOver()
                    && qb.moveAndDeleteFocus() instanceof Quest q) {
                return q;
            }
        }
        return null;
    }

    private static ChapterImage imageUnderMouse(QuestScreen qs) {
        for (Widget w : qs.questPanel.getWidgets()) {
            if (w instanceof ChapterImageButton cb && cb.isMouseOver()
                    && cb.moveAndDeleteFocus() instanceof ChapterImage img) {
                return img;
            }
        }
        return null;
    }

    private static QuestButton buttonOf(QuestScreen qs, Quest quest) {
        for (Widget w : qs.questPanel.getWidgets()) {
            if (w instanceof QuestButton qb && qb.moveAndDeleteFocus() == quest) {
                return qb;
            }
        }
        return null;
    }

    private static Widget widgetOf(QuestScreen qs, Movable obj) {
        for (Widget w : qs.questPanel.getWidgets()) {
            if ((w instanceof QuestButton || w instanceof ChapterImageButton)
                    && w instanceof dev.ftb.mods.ftbquests.client.gui.quests.QuestPositionableButton qb
                    && qb.moveAndDeleteFocus() == obj) {
                return w;
            }
        }
        return null;
    }

    // Quest widgets are laid out in panel space without the scroll offset; the panel
    // itself is drawn with a -scroll translate, so subtract it to get real screen coords.
    private static double left(QuestScreen qs, Widget w) {
        return qs.questPanel.getX() - qs.questPanel.getScrollX() + w.getPosX();
    }

    private static double top(QuestScreen qs, Widget w) {
        return qs.questPanel.getY() - qs.questPanel.getScrollY() + w.getPosY();
    }

    private static double centerX(QuestScreen qs, Widget w) {
        return left(qs, w) + w.getWidth() / 2.0;
    }

    private static double centerY(QuestScreen qs, Widget w) {
        return top(qs, w) + w.getHeight() / 2.0;
    }

    /**
     * Rebuilds the quest panel but keeps the resized object visually pinned under the cursor.
     * Changing a quest/image size changes the content bounds, which used to make the whole
     * view jump around while scrolling the mouse wheel.
     */
    private static void refreshKeepingAnchor(QuestScreen qs, Movable anchor) {
        Widget before = widgetOf(qs, anchor);
        double oldX = before == null ? Double.NaN : centerX(qs, before);
        double oldY = before == null ? Double.NaN : centerY(qs, before);

        qs.questPanel.refreshWidgets();

        Widget after = widgetOf(qs, anchor);
        if (before == null || after == null || Double.isNaN(oldX) || Double.isNaN(oldY)) return;

        double dx = centerX(qs, after) - oldX;
        double dy = centerY(qs, after) - oldY;
        if (Math.abs(dx) > 0.01) qs.questPanel.setScrollX(qs.questPanel.getScrollX() + dx);
        if (Math.abs(dy) > 0.01) qs.questPanel.setScrollY(qs.questPanel.getScrollY() + dy);
    }

    private static void toggleDependency(QuestScreen qs, Quest source, Quest target) {
        boolean add = !target.hasDependency(source);
        List<QuestObject> prev = target.streamDependencies().toList();

        if (add) {
            target.addDependency(source);
        } else {
            target.removeDependency(source);
        }
        target.removeInvalidDependencies();

        if (target.verifyDependencies(false)) {
            NetworkManager.sendToServer(EditObjectMessage.forQuestObject(target));
            qs.questPanel.refreshWidgets();
            ToastHandler.showDependencyToast(source, target, add);
        } else {
            // rollback, same protection FTB uses against loops
            target.clearDependencies();
            prev.forEach(target::addDependency);
            QuestScreen.displayError(Component.translatable("questeditplus.toast.loop"));
        }
    }

    private static void markDirty(QuestObjectBase o) {
        if (!dirty.contains(o)) dirty.add(o);
        lastDirtyAt = System.currentTimeMillis();
    }

    private static void markDirtyAll(Collection<? extends QuestObjectBase> list) {
        for (QuestObjectBase o : list) markDirty(o);
    }

    private static void flushDirtyIfIdle() {
        if (!dirty.isEmpty() && System.currentTimeMillis() - lastDirtyAt >= SEND_DELAY_MS) {
            flushDirty();
        }
    }

    private static void flushDirty() {
        if (dirty.isEmpty()) return;
        NetworkManager.sendToServer(EditObjectMessage.forQuestObjects(List.copyOf(dirty)));
        dirty.clear();
    }

    private static void cancelDrag() {
        dragSource = null;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class ToastHandler {
        static void showDependencyToast(Quest source, Quest target, boolean added) {
            String key = added ? "questeditplus.toast.dep_added" : "questeditplus.toast.dep_removed";
            String descKey = added ? "questeditplus.toast.dep_added.desc" : "questeditplus.toast.dep_removed.desc";
            Minecraft.getInstance().getToasts().addToast(new CustomToast(
                    Component.translatable(key),
                    added ? Icons.ADD : Icons.REMOVE,
                    Component.translatable(descKey, source.getTitle(), target.getTitle())));
        }
    }
}
