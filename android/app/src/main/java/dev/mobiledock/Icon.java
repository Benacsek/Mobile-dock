package dev.mobiledock;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Phosphor icons (see IconPaths), drawn as vector paths. Sharp at any size, no image files, and they
 * work on Android 4.1, which has no VectorDrawable.
 */
final class Icon extends Drawable {
    enum Kind {
        PLAY, PAUSE, PREV, NEXT, SHUFFLE, REPEAT, REPEAT_ONE, VOL_DOWN, VOL_UP, MUTE,
        APPS, MUSIC, TOUCHPAD, KEYBOARD, CAMERA, RECORD, STOP, POWER, BACK, PLUS, REFRESH,
        COMMAND, CHART, SEND,
        SLIDERS, SUN, MONITOR, LOCK, MOON, PICTURE, FILE, SCISSORS, GRIP, EXPAND, SHRINK, CLIPBOARD, BACK5, FORWARD5, SEARCH, GLOBE, PIN, CLOSE, CHEVRON, GEAR, CLOCK, SPEAKER, CHECK
    }

    /** Each outline is parsed once and shared. They are in a 256 by 256 box. */
    private static final Path[] SHAPES = new Path[IconPaths.PATHS.length];

    private final Kind kind;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path drawn = new Path();
    private final Matrix fit = new Matrix();

    Icon(Kind kind, int color) {
        this.kind = kind;
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        label.setColor(color);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
    }

    void setColor(int color) {
        fill.setColor(color);
        label.setColor(color);
        invalidateSelf();
    }

    private static Path shape(Kind kind) {
        int i = kind.ordinal();
        if (SHAPES[i] == null) SHAPES[i] = parse(IconPaths.PATHS[i]);
        return SHAPES[i];
    }

    /** Reads "M x y", "L x y", "C x1 y1 x2 y2 x y" and "Z", separated by spaces. */
    private static Path parse(String d) {
        Path p = new Path();
        String[] t = d.split(" ");
        int i = 0;
        while (i < t.length) {
            char cmd = t[i++].charAt(0);
            if (cmd == 'Z') {
                p.close();
            } else if (cmd == 'M') {
                p.moveTo(Float.parseFloat(t[i]), Float.parseFloat(t[i + 1]));
                i += 2;
            } else if (cmd == 'L') {
                p.lineTo(Float.parseFloat(t[i]), Float.parseFloat(t[i + 1]));
                i += 2;
            } else {
                p.cubicTo(Float.parseFloat(t[i]), Float.parseFloat(t[i + 1]), Float.parseFloat(t[i + 2]),
                        Float.parseFloat(t[i + 3]), Float.parseFloat(t[i + 4]), Float.parseFloat(t[i + 5]));
                i += 6;
            }
        }
        return p;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float ox = b.left + (b.width() - s) / 2f, oy = b.top + (b.height() - s) / 2f;
        // Phosphor fills almost its whole box. 80 percent keeps the old margin around each icon.
        fit.setScale(s * 0.8f / 256f, s * 0.8f / 256f);
        fit.postTranslate(ox + s * 0.1f, oy + s * 0.1f);
        shape(kind).transform(fit, drawn);
        c.drawPath(drawn, fill);
        if (kind == Kind.BACK5 || kind == Kind.FORWARD5) {
            label.setTextSize(s * 0.28f);  // the 5 sits inside the circular arrow
            c.drawText("5", ox + s * 0.5f, oy + s * 0.6f, label);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        label.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        label.setColorFilter(cf);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
