package dev.mobiledock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Versions on both sides, the PC's update status, and a Check now button. */
final class UpdatesPanel extends ScrollView {
    private final DockClient client;
    private final PhoneUpdater updater;
    private final TextView pc, status;
    private final Button check;

    UpdatesPanel(Context c, DockClient client, PhoneUpdater updater) {
        super(c);
        this.client = client;
        this.updater = updater;
        LinearLayout box = Ui.column(c);
        addView(box);
        box.addView(Ui.title(c, "Updates"), Ui.fillWidth(c, 4));

        LinearLayout cards = Ui.row(c);
        cards.addView(card(c, "This phone", Ui.text(c, phoneVersion(c), 22, Ui.TEXT)), cardLp(c));
        pc = Ui.text(c, "?", 22, Ui.TEXT);
        cards.addView(card(c, "PC app", pc), cardLp(c));
        box.addView(cards, Ui.fillWidth(c, 0));

        status = Ui.text(c, "", 14, Ui.MUTED);
        status.setPadding(Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8));
        box.addView(status, Ui.fillWidth(c, 4));

        check = Ui.accentButton(c, "Check now");
        check.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });
        box.addView(check, Ui.wrap(c, 4));
        box.addView(Ui.text(c, "The PC downloads new versions from the GitHub release. The phone gets its update "
                + "from the PC and checks it before you install. Nothing else is contacted.", 13, Ui.MUTED),
                Ui.fillWidth(c, 6));
    }

    private static View card(Context c, String label, TextView value) {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.rounded(c, Ui.SURFACE, 18));
        int p = Ui.dp(c, 16);
        card.setPadding(p, p, p, p);
        TextView l = Ui.text(c, label, 13, Ui.ACCENT);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(l);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(value);
        return card;
    }

    private static LinearLayout.LayoutParams cardLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(c, 5);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private static String phoneVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Called when the page opens (no network check) or from Check now (asks the PC to check GitHub). */
    void refresh(final boolean checkNow) {
        if (checkNow) {
            check.setEnabled(false);
            status.setText("Checking");
        }
        client.request(checkNow ? "update.refresh" : "update.status", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                check.setEnabled(true);
                if (!r.optBoolean("ok")) {
                    status.setText(r.optString("error", "The PC app is too old for this page. Update it first."));
                    return;
                }
                pc.setText(r.optString("pc_version", "?"));
                StringBuilder sb = new StringBuilder(r.optString("status", ""));
                String phone = r.optString("phone_ready", "");
                if (phone.length() > 0) {
                    sb.append(sb.length() > 0 ? "\n" : "").append("Phone version kept on the PC: ").append(phone);
                }
                status.setText(sb.length() > 0 ? sb.toString() : "Up to date.");
                // The phone compares version codes itself and offers Install only when the PC copy is newer.
                updater.check();
            }
        });
    }
}
