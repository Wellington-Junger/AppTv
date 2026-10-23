package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The remote's Settings button in Free My TV mode, from inside any app (RemoteKeys opens it). Google TV opens a side
 * panel with Wi-Fi, Bluetooth, picture and inputs, but that panel is part of Google TV's home screen, which is off in
 * Free My TV mode: without this the button did nothing. The same shortcuts, drawn over the app that is playing.
 * The Settings button again, Back or Home close it.
 */
public class QuickPanel extends Activity {
    private PanelView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new PanelView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    /** The Settings button while the panel is open: closes it, like Google TV's. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        close();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void close() {
        finish();
        overridePendingTransition(0, 0);
    }

    /** One of the TV's settings screens; the main one if this TV has no screen for that action. */
    void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("quick panel: no screen for " + action + ", opening the TV's settings");
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e2) {
                return;
            }
        }
        close();
    }

    static final class PanelView extends View implements Choreographer.FrameCallback {
        /** The grid: two columns of tiles, then the TV's settings across the panel. */
        private static final String[] SYMBOLS = {"input", "display", "volume", "wifi", "bluetooth", "accessibility", "tv_settings"};
        private static final String[] ACTIONS = {null, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SOUND_SETTINGS,
                Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS,
                Settings.ACTION_SETTINGS};
        private static final int TILES = 6, INPUTS = 0, MAX_ROWS = 8;
        private static final float PANEL_W = 680, MARGIN = 40, PAD = 36, TILE_H = 104, GAP = 20, GRID_TOP = 250,
                ROW_H = 84, ROW_GAP = 8;
        private static final int INK = 0xFF1C1C1E;

        private final QuickPanel activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring open = new Spring(0.32f, 1f, 0);
        private final Spring[] pop = new Spring[16];
        private final RectF panel = new RectF(), box = new RectF(), tmp = new RectF();
        private final Spoken spoken;
        private List<HomeApps.Item> inputs = new ArrayList<>();
        private boolean inInputs, running, okDown, backDown;
        private int focus, generation = -1;
        private long lastNanos;
        private int w, h;
        private float k = 1;
        private String[] labels;
        private String dateText = "", clockText = "";
        private Paint date, clock, heading, empty, fill, white, shadow, scrim;
        private TextPaint label;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                long now = System.currentTimeMillis();
                dateText = Ui.date(now);
                clockText = Ui.clock(now);
                invalidate();
                handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
            }
        };

        PanelView(QuickPanel a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            setFocusable(true);
            setContentDescription(Ui.tr("Quick Settings"));
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= count(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= count() ? itemText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > count()) return false;
                    itemBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        void resume() {
            if (running) return;
            running = true;
            lastNanos = 0;
            open.target = 1;
            handler.post(tick);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(tick);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = !open.settled();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            date = Ui.text(Ui.regular, 26 * k, 0xCCFFFFFF);
            clock = Ui.numbers(Ui.displaySemibold, 72 * k, 0xFFFFFFFF);
            label = new TextPaint(Ui.text(Ui.medium, 27 * k, 0xF2FFFFFF));
            heading = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            empty = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            scrim = new Paint();
        }

        private int count() {
            return inInputs ? Math.max(1, Math.min(MAX_ROWS, inputs.size())) : TILES + 1;
        }

        private String[] labels() {
            if (labels == null || generation != Ui.generation) {
                generation = Ui.generation;
                labels = new String[] {Ui.tr("Inputs"), Ui.tr("Display"), Ui.tr("Sound"), Ui.tr("Wi-Fi"),
                        Ui.tr("Bluetooth"), Ui.tr("Accessibility"), Ui.tr("TV Settings")};
            }
            return labels;
        }

        private String itemText(int i) {
            if (!inInputs) return labels()[i];
            if (inputs.isEmpty()) return Ui.tr("No inputs found");
            return inputs.get(i).label + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", count());
        }

        /** Where item i is, before the opening slide. */
        private RectF itemBox(int i) {
            float left = (1920 - MARGIN - PANEL_W + PAD) * k, right = (1920 - MARGIN - PAD) * k;
            if (inInputs) {
                float top = (GRID_TOP + i * (ROW_H + ROW_GAP)) * k;
                box.set(left, top, right, top + ROW_H * k);
            } else if (i < TILES) {
                float tileW = (right - left - GAP * k) / 2;
                float x = left + (i % 2) * (tileW + GAP * k), top = (GRID_TOP + (i / 2) * (TILE_H + GAP)) * k;
                box.set(x, top, x + tileW, top + TILE_H * k);
            } else {
                float top = (GRID_TOP + 3 * (TILE_H + GAP) + 24) * k;
                box.set(left, top, right, top + TILE_H * k);
            }
            return box;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            open.step(dt);
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < count() ? 1 : 0;
                pop[i].step(dt);
            }
            // Slides in from the right edge, like Google TV's panel; the app behind dims a little.
            float o = Ui.clamp(open.value), slide = (1 - o) * (PANEL_W + MARGIN * 2) * k;
            scrim.setColor(Ui.alpha(0x59000000, o));
            c.drawRect(0, 0, w, h, scrim);

            c.save();
            c.translate(slide, 0);
            int n = count();
            // As tall as what it holds: the last item, then the same padding as at the sides.
            panel.set((1920 - MARGIN - PANEL_W) * k, MARGIN * k, (1920 - MARGIN) * k, itemBox(n - 1).bottom + PAD * k);
            float r = 44 * k;
            tmp.set(panel);
            tmp.offset(0, 12 * k);
            shadow.setColor(0x80000000);
            c.drawRoundRect(tmp, r, r, shadow);
            fill.setColor(0xF5232B2F);
            c.drawRoundRect(panel, r, r, fill);

            float left = panel.left + PAD * k;
            if (inInputs) {
                c.drawText(Ui.tr("Inputs"), left, 170 * k, heading);
            } else {
                c.drawText(dateText, left, 116 * k, date);
                c.drawText(clockText, left - Ui.leftBearing(clock, "0"), 196 * k, clock);
            }
            for (int i = 0; i < n; i++) drawItem(c, i);
            c.restore();
        }

        private void drawItem(Canvas c, int i) {
            RectF b = itemBox(i);
            float f = Ui.clamp(pop[i].value);
            float s = 1 + 0.03f * f, cx = b.centerX(), cy = b.centerY();
            tmp.set(cx - b.width() * s / 2, cy - b.height() * s / 2, cx + b.width() * s / 2, cy + b.height() * s / 2);
            float r = 30 * k * s;
            if (f > 0.01f) {
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
            }
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(tmp, r, r, fill);
            white.setAlpha(Math.round(255 * f));
            c.drawRoundRect(tmp, r, r, white);

            String text, symbol;
            boolean dim = false;
            if (inInputs && inputs.isEmpty()) {
                c.drawText(Ui.tr("No inputs found"), tmp.left + 32 * k, tmp.centerY() + Ui.halfCap(empty), empty);
                return;
            } else if (inInputs) {
                HomeApps.Item it = inputs.get(i);
                text = it.label;
                symbol = it.symbol;
                dim = it.kind == HomeApps.INPUT && !it.connected;
            } else {
                text = labels()[i];
                symbol = SYMBOLS[i];
            }
            int ink = blend(dim ? 0x8CFFFFFF : 0xF2FFFFFF, INK, f);
            float icon = tmp.left + 44 * k;
            Symbols.draw(getContext(), c, symbol, icon, tmp.centerY(), 26 * k, ink);
            label.setColor(ink);
            float x = icon + 34 * k;
            String shown = TextUtils.ellipsize(text, label, tmp.right - 22 * k - x, TextUtils.TruncateAt.END).toString();
            c.drawText(shown, x, tmp.centerY() + Ui.halfCap(label), label);
        }

        private static int blend(int a, int b, float t) {
            int out = 0;
            for (int shift = 0; shift <= 24; shift += 8) {
                int ca = (a >>> shift) & 255, cb = (b >>> shift) & 255;
                out |= Math.round(ca + (cb - ca) * t) << shift;
            }
            return out;
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int before = focus;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) focus = inInputs ? Math.max(0, focus - 1) : focus == TILES ? TILES - 2 : focus >= 2 ? focus - 2 : focus;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) focus = inInputs ? Math.min(count() - 1, focus + 1) : focus >= TILES - 2 ? TILES : focus + 2;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && !inInputs && focus < TILES && focus % 2 == 1) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && !inInputs && focus < TILES && focus % 2 == 0) focus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (!inInputs) return false;   // closes the panel
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) show(false, INPUTS);
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
            if (focus != before) {
                invalidate();
                spoken.focusMoved();
            }
            return true;
        }

        private void activate() {
            if (inInputs) {
                if (inputs.isEmpty()) return;
                RectF b = itemBox(focus);
                if (HomeApps.open(activity, inputs.get(focus), this, Math.round(b.left), Math.round(b.top),
                        Math.round(b.width()), Math.round(b.height()))) {
                    activity.close();
                }
            } else if (focus == INPUTS) {
                show(true, 0);
            } else {
                activity.openSettings(ACTIONS[focus]);
            }
        }

        /** Between the grid and the list of inputs (live TV and the HDMI ports, read again each time). */
        private void show(boolean list, int at) {
            if (list) inputs = HomeApps.allInputs(getContext());
            inInputs = list;
            focus = at;
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
