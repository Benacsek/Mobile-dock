package dev.mobiledock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * iOS controls drawn in code, so they look the same on Android 4.1 and on Android 16:
 * the switch, the segmented control, the capsule slider and the floating glass panel.
 */
final class Kit {
    private Kit() {
    }

    /** Called with the new state when a switch flips. */
    interface OnFlip {
        void flipped(boolean on);
    }

    /** Apple's switch: 51 x 31, green when on, a white knob that slides. */
    static final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean on;
        private float pos;  // 0 off, 1 on, animated between
        private OnFlip listener;

        Switch(Context c, boolean on) {
            super(c);
            this.on = on;
            pos = on ? 1 : 0;
            setClickable(true);
            setFocusable(true);
            setContentDescription(on ? "On" : "Off");
            int w = Ui.dp(c, 51), h = Ui.dp(c, 31);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.leftMargin = Ui.dp(c, 8);
            setLayoutParams(lp);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    set(!Switch.this.on, true);
                    if (listener != null) listener.flipped(Switch.this.on);
                }
            });
        }

        void setOnFlip(OnFlip l) {
            listener = l;
        }

        boolean isOn() {
            return on;
        }

        void set(boolean value, boolean animate) {
            on = value;
            setContentDescription(on ? "On" : "Off");
            if (!animate) {
                pos = on ? 1 : 0;
                invalidate();
                return;
            }
            post(new Runnable() {
                @Override
                public void run() {
                    float target = on ? 1 : 0;
                    pos += (target - pos) * 0.45f;
                    if (Math.abs(target - pos) < 0.02f) pos = target;
                    invalidate();
                    if (pos != target) postDelayed(this, 16);
                }
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            track.set(0, 0, w, h);
            paint.setColor(blend(Ui.SURFACE_HI, Ui.GREEN, pos));
            c.drawRoundRect(track, r, r, paint);
            float knob = r - Ui.dp(getContext(), 2);
            float cx = r + (w - 2 * r) * pos;
            paint.setColor(0x26000000);
            c.drawCircle(cx, r + Ui.dp(getContext(), 1), knob + 0.5f, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(cx, r, knob, paint);
        }
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    /** Draws a CheckBox as an iOS switch at the end of its label. Its listener keeps working unchanged. */
    static void asSwitch(Context c, android.widget.CheckBox box) {
        box.setButtonDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[] {android.R.attr.state_checked}, new SwitchShape(c, true));
        s.addState(new int[] {}, new SwitchShape(c, false));
        s.setBounds(0, 0, Ui.dp(c, 51), Ui.dp(c, 31));
        box.setCompoundDrawables(null, null, s, null);
        box.setCompoundDrawablePadding(Ui.dp(c, 12));
        box.setTypeface(Ui.regular());
        box.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
        box.setMinHeight(Ui.dp(c, 48));
        box.setPadding(Ui.dp(c, 4), 0, 0, 0);
    }

    /** A still picture of the switch, for places that need a Drawable. */
    private static final class SwitchShape extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean on;
        private final float px;

        SwitchShape(Context c, boolean on) {
            this.on = on;
            px = Ui.dp(c, 1);
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float r = b.height() / 2f;
            paint.setColor(on ? Ui.GREEN : Ui.SURFACE_HI);
            c.drawRoundRect(new RectF(b), r, r, paint);
            float cx = on ? b.right - r : b.left + r;
            paint.setColor(0x26000000);
            c.drawCircle(cx, b.centerY() + px, r - 2 * px + 0.5f, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(cx, b.centerY(), r - 2 * px, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Called with the index of the chosen segment. */
    interface OnPick {
        void picked(int index);
    }

    /** Apple's segmented control: a gray capsule, the chosen segment raised. At most 5 segments on a phone. */
    static LinearLayout segmented(Context c, String[] items, int chosen, final OnPick pick) {
        final LinearLayout bar = Ui.row(c);
        bar.setBackground(Ui.rounded(c, Ui.SURFACE_HI, 19));
        int p = Ui.dp(c, 2);
        bar.setPadding(p, p, p, p);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView seg = (i == chosen ? Ui.strong(c, items[i], 15, Ui.TEXT) : Ui.text(c, items[i], 15, Ui.TEXT));
            seg.setGravity(Gravity.CENTER);
            seg.setSingleLine(true);
            if (i == chosen) seg.setBackground(Ui.rounded(c, Ui.DARK ? 0xFF636366 : 0xFFFFFFFF, 17));
            seg.setClickable(true);
            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.picked(index);
                }
            });
            bar.addView(seg, new LinearLayout.LayoutParams(0, Ui.dp(c, 36), 1f));
        }
        bar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 40)));
        return bar;
    }

    /** Restyles a SeekBar like an iOS slider: thin track, accent fill, a white capsule thumb. */
    static void slider(Context c, SeekBar bar) {
        GradientDrawable back = Ui.rounded(c, Ui.SURFACE_HI, 3);
        GradientDrawable fill = Ui.rounded(c, Ui.ACCENT, 3);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(fill, Gravity.LEFT,
                android.graphics.drawable.ClipDrawable.HORIZONTAL);
        LayerDrawable track = new LayerDrawable(new Drawable[] {back, clip});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        int inset = Ui.dp(c, 9);
        track.setLayerInset(0, 0, inset, 0, inset);
        track.setLayerInset(1, 0, inset, 0, inset);
        bar.setProgressDrawable(track);
        GradientDrawable thumb = Ui.rounded(c, 0xFFFFFFFF, 12);
        thumb.setSize(Ui.dp(c, 38), Ui.dp(c, 24));
        thumb.setStroke(1, Ui.DARK ? 0x00000000 : 0x1F000000);
        bar.setThumb(thumb);
        bar.setThumbOffset(Ui.dp(c, 4));
        bar.setMinimumHeight(Ui.dp(c, 24));
        int pad = Ui.dp(c, 19);
        bar.setPadding(pad, 0, pad, 0);
        bar.setBackground(null);
    }

    /**
     * The floating panel for navigation, like Liquid Glass: a see-through tint, a bright rim and a soft shadow.
     * Only a plain background sits behind it here, where a blur would look the same, so no blur is needed.
     */
    static Drawable glass(Context c, float radiusDp) {
        float r = Ui.dp(c, radiusDp);
        Drawable[] layers = new Drawable[5];
        int[] shadow = Ui.DARK ? new int[] {0x14000000, 0x1E000000, 0x2A000000} : new int[] {0x05000000, 0x08000000, 0x0C000000};
        for (int i = 0; i < 3; i++) {
            GradientDrawable s = new GradientDrawable();
            s.setColor(shadow[i]);
            s.setCornerRadius(r + Ui.dp(c, 2 * (3 - i)));
            layers[i] = s;
        }
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Ui.GLASS);
        fill.setCornerRadius(r);
        layers[3] = fill;
        GradientDrawable rim = new GradientDrawable();
        rim.setColor(0x00000000);
        rim.setCornerRadius(r);
        rim.setStroke(Math.max(1, Ui.dp(c, 0.75f)), Ui.GLASS_RIM);
        layers[4] = rim;
        LayerDrawable d = new LayerDrawable(layers);
        int sh = Ui.dp(c, 6);
        for (int i = 0; i < 3; i++) {
            int g = Ui.dp(c, 2 * i);
            d.setLayerInset(i, g, g + Ui.dp(c, 2), g, Math.max(0, g - Ui.dp(c, 2)));
        }
        d.setLayerInset(3, sh, sh, sh, sh);
        d.setLayerInset(4, sh, sh, sh, sh);
        return d;
    }

    /** How far the glass shape sits inside its view, so children can line up with the visible panel. */
    static int glassInset(Context c) {
        return Ui.dp(c, 6);
    }

    /** Swallows touches on a view so they do not fall through to what is behind it. */
    static void blockTouches(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                return true;
            }
        });
    }
}
