package uz.bms.floorplan3d;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** First-run / re-config screen: Home Assistant address + long-lived token. */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final SharedPreferences p = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        final float d = getResources().getDisplayMetrics().density;
        final int pad = Math.round(24 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF14161B);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(Math.round(460 * d), -2);
        box.setLayoutParams(boxLp);

        TextView title = new TextView(this);
        title.setText("BMS 3D Floor Plan");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Адрес Home Assistant и долгосрочный токен доступа. Планшет и HA в одной сети Wi-Fi.");
        hint.setTextColor(0xFF99A0AC);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Math.round(8 * d);
        hint.setLayoutParams(hp);
        box.addView(hint);

        final EditText url = field(p.getString(MainActivity.KEY_URL, ""), "http://192.168.1.50:8123",
                InputType.TYPE_TEXT_VARIATION_URI, d, box);
        final EditText token = field(p.getString(MainActivity.KEY_TOKEN, ""), "Долгосрочный токен",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, d, box);

        Button save = new Button(this);
        save.setText("Сохранить и подключиться");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Math.round(18 * d);
        save.setLayoutParams(sp);
        save.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            String t = token.getText().toString().trim();
            if (u.isEmpty() || t.isEmpty()) {
                Toast.makeText(this, "Заполните оба поля", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!u.matches("(?i)^https?://.*")) u = "http://" + u;
            p.edit().putString(MainActivity.KEY_URL, u).putString(MainActivity.KEY_TOKEN, t).apply();
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        box.addView(save);

        root.addView(box);
        setContentView(root);
    }

    private EditText field(String value, String hint, int inputType, float d, LinearLayout parent) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF677079);
        int gp = Math.round(10 * d);
        e.setPadding(gp, gp, gp, gp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(12 * d);
        e.setLayoutParams(lp);
        parent.addView(e);
        return e;
    }

    @Override
    public void onBackPressed() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        // Only allow leaving settings once configured.
        if (!p.getString(MainActivity.KEY_URL, "").isEmpty()
                && !p.getString(MainActivity.KEY_TOKEN, "").isEmpty()) {
            super.onBackPressed();
        }
    }
}
