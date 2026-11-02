package dev.mobiledock;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * The dimmed dock: a quiet clock and the current song on black. It moves a little every update,
 * so no pixel stays lit in the same place for hours.
 */
final class NightView extends FrameLayout {
    private final LinearLayout box;
    private final TextView time, date, song;
    private final DockClient client;
    private final Random random = new Random();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            if (running) postDelayed(this, 20_000);
        }
    };

    NightView(Context c, DockClient client) {
        super(c);
        this.client = client;
        setBackgroundColor(0xFF000000);
        setClickable(true);  // the first touch only wakes the dock
        box = Ui.column(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        time = Ui.text(c, "", 72, 0xFF8B919A);
        time.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        box.addView(time);
        date = Ui.text(c, "", 16, 0xFF5C626B);
        box.addView(date);
        song = Ui.text(c, "", 15, 0xFF5C626B);
        song.setSingleLine(true);
        song.setEllipsize(android.text.TextUtils.TruncateAt.END);
        song.setPadding(0, Ui.dp(c, 18), 0, 0);
        box.addView(song);
        addView(box, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    void start() {
        running = true;
        removeCallbacks(tick);
        post(tick);
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    private void update() {
        Date now = new Date();
        time.setText(android.text.format.DateFormat.getTimeFormat(getContext()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        int shift = Ui.dp(getContext(), 24);
        box.setTranslationX(random.nextInt(2 * shift + 1) - shift);
        box.setTranslationY(random.nextInt(2 * shift + 1) - shift);
        client.request("media.status", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                boolean playing = r.optBoolean("ok") && r.optBoolean("playing") && r.optString("title").length() > 0;
                song.setText(playing ? r.optString("title") + "  ·  " + r.optString("artist") : "");
            }
        });
    }
}
