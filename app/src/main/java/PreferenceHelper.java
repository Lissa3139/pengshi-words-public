import android.content.Context;
import android.content.SharedPreferences;

/**
 * Compatibility bridge for the embedded Sherpa model dex. The original
 * voice package used this tiny default-package helper only for speed and
 * speaker-id preferences; the main application owns the visible settings.
 */
public final class PreferenceHelper {
    private final SharedPreferences preferences;

    public PreferenceHelper(Context context) {
        preferences = context.getSharedPreferences("embedded-sherpa-tts", Context.MODE_PRIVATE);
    }

    public float getSpeed() {
        return preferences.getFloat("speed", 1.0f);
    }

    public int getSid() {
        return preferences.getInt("sid", 0);
    }
}
