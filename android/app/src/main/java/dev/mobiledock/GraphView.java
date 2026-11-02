package dev.mobiledock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Scrolling line graph of the last 60 samples, like Task Manager. One or two series. */
final class GraphView extends View {
    static final int POINTS = 60;
    private final float[][] data;
    private final int[] colors;
    private int count;
    private float fixedMax;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    /** fixedMax 0 means the graph scales to the largest value shown. */
    GraphView(Context c, float fixedMax, int... colors) {
        super(c);
        this.fixedMax = fixedMax;
        this.colors = colors;
        data = new float[colors.length][POINTS];
        grid.setColor(Ui.LINE);
        grid.setStrokeWidth(1);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2));
        line.setStrokeJoin(Paint.Join.ROUND);
        area.setStyle(Paint.Style.FILL);
    }

    void add(float... values) {
        for (int s = 0; s < data.length; s++) {
            System.arraycopy(data[s], 1, data[s], 0, POINTS - 1);
            data[s][POINTS - 1] = s < values.length ? Math.max(0, values[s]) : 0;
        }
        count = Math.min(POINTS, count + 1);
        invalidate();
    }

    private float max() {
        if (fixedMax > 0) return fixedMax;
        float m = 1;
        for (float[] series : data) for (float v : series) m = Math.max(m, v);
        return m * 1.15f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        for (int i = 1; i < 4; i++) c.drawLine(0, h * i / 4f, w, h * i / 4f, grid);
        for (int i = 1; i < 6; i++) c.drawLine(w * i / 6f, 0, w * i / 6f, h, grid);
        if (count < 2) return;
        float max = max(), step = w / (POINTS - 1);
        for (int s = data.length - 1; s >= 0; s--) {
            path.reset();
            int first = POINTS - count;
            for (int i = first; i < POINTS; i++) {
                float x = i * step, y = h - h * Math.min(1f, data[s][i] / max);
                if (i == first) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            line.setColor(colors[s]);
            c.drawPath(path, line);
            path.lineTo(w, h);
            path.lineTo(first * step, h);
            path.close();
            area.setColor((colors[s] & 0x00FFFFFF) | 0x33000000);
            c.drawPath(path, area);
        }
    }
}
