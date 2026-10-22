package app.autopilot.probe;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/** Gives the isolated request UID the same foreground network policy as a user opening an app. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView label=new TextView(this);label.setText("Autopilot separate UID tunnel verification");setContentView(label);
    }
}
