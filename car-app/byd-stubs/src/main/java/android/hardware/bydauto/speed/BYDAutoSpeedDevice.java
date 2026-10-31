package android.hardware.bydauto.speed;

import android.content.Context;

/** Velocidad del vehículo. */
public class BYDAutoSpeedDevice {
    public static BYDAutoSpeedDevice getInstance(Context ctx) { return new BYDAutoSpeedDevice(); }
    /** km/h. */
    public float getCurrentSpeed() { return 0f; }
}
