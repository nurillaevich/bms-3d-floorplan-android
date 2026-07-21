package uz.bms.floorplan3d;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The call screen: a native full-screen panel, not a web page.
 *
 * It is the one thing in this app that has to appear on its own, over whatever
 * the tablet was showing, possibly with the screen off — so it is drawn with
 * plain views and shows over the keyguard. Routing it through the WebView would
 * mean the kiosk page had to be alive and on the right route for a doorbell to
 * be answerable, which is exactly the failure a wall intercom cannot have.
 */
public class CallActivity extends Activity implements Intercom.Listener {

    private Intercom intercom;
    private TextView name;
    private TextView status;
    private Button accept;
    private Button hangup;
    private Button mute;

    private final Handler tick = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        intercom = Intercom.get(this);

        // Wake the panel and show over the lock screen: a call nobody can see is
        // not a call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(32 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0E1014);
        root.setPadding(pad, pad, pad, pad);

        TextView kicker = new TextView(this);
        kicker.setText("ИНТЕРКОМ");
        kicker.setTextColor(0xFFF3A83C);
        kicker.setTextSize(13);
        kicker.setLetterSpacing(0.25f);
        kicker.setGravity(Gravity.CENTER);
        root.addView(kicker);

        name = new TextView(this);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(40);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = Math.round(12 * d);
        name.setLayoutParams(np);
        root.addView(name);

        status = new TextView(this);
        status.setTextColor(0xFF99A0AC);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = Math.round(10 * d);
        status.setLayoutParams(stp);
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = Math.round(44 * d);
        row.setLayoutParams(rp);

        mute = bigButton("Микрофон", 0xFF2A2F3A, d);
        mute.setOnClickListener(v -> intercom.setMuted(!intercom.muted()));
        row.addView(mute);

        accept = bigButton("Ответить", 0xFF2E9E5B, d);
        accept.setOnClickListener(v -> intercom.accept());
        row.addView(accept);

        hangup = bigButton("Завершить", 0xFFC8362F, d);
        hangup.setOnClickListener(v -> {
            if (intercom.state() == Intercom.State.INCOMING) intercom.reject();
            else intercom.hangup();
            // The state change closes the screen; if the peer is already gone,
            // don't leave the operator staring at a dead call screen either.
            finish();
        });
        row.addView(hangup);

        root.addView(row);
        setContentView(root);
    }

    private Button bigButton(String text, int color, float d) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(17);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(16 * d);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(Math.round(190 * d), Math.round(74 * d));
        lp.leftMargin = Math.round(10 * d);
        lp.rightMargin = Math.round(10 * d);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        intercom.addListener(this);
        intercom.clearIncomingNotification();
        onIntercomChanged();
        startTicker();
    }

    @Override
    protected void onPause() {
        intercom.removeListener(this);
        stopTicker();
        super.onPause();
    }

    @Override
    public void onIntercomChanged() {
        Intercom.State s = intercom.state();
        String err = intercom.takeError();
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();

        if (s == Intercom.State.IDLE) {
            finish();
            return;
        }
        name.setText(intercom.peerName());
        accept.setVisibility(s == Intercom.State.INCOMING ? View.VISIBLE : View.GONE);
        mute.setVisibility(s == Intercom.State.ACTIVE ? View.VISIBLE : View.GONE);
        mute.setText(intercom.muted() ? "Микрофон выкл." : "Микрофон");
        switch (s) {
            case INCOMING:
                status.setText("Входящий вызов");
                hangup.setText("Отклонить");
                break;
            case OUTGOING:
                status.setText("Вызов…");
                hangup.setText("Отмена");
                break;
            default:
                hangup.setText("Завершить");
                updateTimer();
        }
    }

    /** "Разговор 01:24" while the voice link is up. */
    private void updateTimer() {
        long since = intercom.activeSince();
        if (since <= 0) {
            status.setText("Соединение…");
            return;
        }
        long secs = Math.max(0, (System.currentTimeMillis() - since) / 1000L);
        status.setText(String.format(java.util.Locale.US, "Разговор %02d:%02d", secs / 60, secs % 60));
    }

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (intercom.state() == Intercom.State.ACTIVE) updateTimer();
                tick.postDelayed(this, 1000L);
            }
        };
        tick.postDelayed(ticker, 1000L);
    }

    private void stopTicker() {
        if (ticker != null) {
            tick.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onBackPressed() {
        // Back must not silently drop a live call — leave it to the buttons.
        if (intercom.state() == Intercom.State.IDLE) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        intercom.removeListener(this);
        stopTicker();
        super.onDestroy();
    }
}
