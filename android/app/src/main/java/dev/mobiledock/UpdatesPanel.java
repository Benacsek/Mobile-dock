package dev.mobiledock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Versions on both sides, the PC's update status, and a Check now button. Part of the Settings list. */
final class UpdatesPanel extends LinearLayout {
    private final DockClient client;
    private final PhoneUpdater updater;
    private final TextView pc, status;
    private final Button check;

    UpdatesPanel(Context c, DockClient client, PhoneUpdater updater) {
        super(c);
        this.client = client;
        this.updater = updater;
        setOrientation(VERTICAL);
        addView(Ui.header(c, "Updates"));
        LinearLayout card = Ui.card(c);
        card.addView(Ui.listRow(c, Icon.Kind.TOUCHPAD, 0xFF8E8E93, "This phone", Ui.value(c, phoneVersion(c))));
        card.addView(Ui.hairline(c, 54));
        pc = Ui.value(c, "?");
        card.addView(Ui.listRow(c, Icon.Kind.MONITOR, 0xFF8E8E93, "PC app", pc));
        addView(card);

        status = Ui.text(c, "", 13, Ui.MUTED);
        status.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 10));
        addView(status);

        check = Ui.accentButton(c, "Check now");
        check.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });
        LinearLayout actions = Ui.row(c);
        actions.addView(check, Ui.wrap(c, 4));
        Button diag = Ui.button(c, "Copy diagnostics");
        diag.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDiagnostics();
            }
        });
        actions.addView(diag, Ui.wrap(c, 4));
        addView(actions);
        TextView note = Ui.text(c, "The PC downloads new versions from the GitHub release. The phone gets its update "
                + "from the PC and checks it before you install. Nothing else is contacted.", 13, Ui.MUTED);
        note.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 16));
        addView(note);
    }

    /** Version, phone model and the local error log, ready to paste into a bug report. Nothing is sent. */
    @SuppressWarnings("deprecation")
    private void copyDiagnostics() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Mobile Dock diagnostics",
                CrashLog.diagnostics(getContext())));
        android.widget.Toast.makeText(getContext(), "Diagnostics copied. Nothing was sent.",
                android.widget.Toast.LENGTH_SHORT).show();
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
