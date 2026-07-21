package uz.bms.floorplan3d;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * "Кому позвонить": the other tablets, read live from the Home Assistant
 * directory. Nothing here is configured — a panel appears the moment it is
 * given a name, and disappears when it is switched off.
 */
public class PeersActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView hint;
    private Button refresh;
    private float d;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF14161B);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Интерком");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        root.addView(title);

        hint = new TextView(this);
        hint.setTextColor(0xFF99A0AC);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Math.round(6 * d);
        hint.setLayoutParams(hp);
        root.addView(hint);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(16 * d);
        list.setLayoutParams(lp);
        root.addView(list);

        refresh = new Button(this);
        refresh.setText("Обновить список");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Math.round(20 * d);
        refresh.setLayoutParams(rp);
        refresh.setOnClickListener(v -> load());
        root.addView(refresh);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(0xFF14161B);
        scroller.setFillViewport(true);
        scroller.addView(root);
        setContentView(scroller);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A call already in flight owns the screen — don't make the user find it.
        if (Intercom.get(this).busy()) {
            startActivity(new Intent(this, CallActivity.class));
            finish();
            return;
        }
        String me = Intercom.deviceName(this);
        hint.setText("Этот планшет: " + (me.isEmpty() ? "имя не задано" : me));
        load();
    }

    private void load() {
        refresh.setEnabled(false);
        message("Загрузка…");
        new Thread(() -> {
            try {
                final List<IntercomPeer> peers = IntercomRegistry.peers(this);
                ui.post(() -> {
                    refresh.setEnabled(true);
                    show(peers);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    refresh.setEnabled(true);
                    message("Не удалось получить список: " + e.getMessage());
                });
            }
        }).start();
    }

    private void show(List<IntercomPeer> peers) {
        list.removeAllViews();
        if (peers.isEmpty()) {
            message("Других планшетов пока нет.\n"
                    + "Включите интерком и задайте имя на втором планшете — "
                    + "он появится здесь сам.");
            return;
        }
        for (final IntercomPeer p : peers) list.addView(row(p));
    }

    private View row(final IntercomPeer p) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1E25);
        bg.setCornerRadius(14 * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0xFF2E333D);
        item.setBackground(bg);
        int ip = Math.round(16 * d);
        item.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(10 * d);
        item.setLayoutParams(lp);

        TextView n = new TextView(this);
        n.setText(p.name);
        n.setTextColor(p.online() ? 0xFFFFFFFF : 0xFF7A828E);
        n.setTextSize(20);
        item.addView(n);

        TextView sub = new TextView(this);
        sub.setText(p.online() ? p.ip : (p.ip + " · не в сети"));
        sub.setTextColor(0xFF677079);
        sub.setTextSize(12);
        item.addView(sub);

        item.setOnClickListener(v -> {
            Intercom in = Intercom.get(this);
            if (!in.call(p)) {
                Toast.makeText(this, "Уже идёт разговор", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, CallActivity.class));
        });
        return item;
    }

    private void message(String text) {
        list.removeAllViews();
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF99A0AC);
        t.setTextSize(14);
        t.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(8 * d);
        t.setLayoutParams(lp);
        list.addView(t);
    }
}
