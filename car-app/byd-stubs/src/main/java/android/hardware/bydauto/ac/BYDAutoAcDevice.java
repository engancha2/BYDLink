package android.hardware.bydauto.ac;

import android.content.Context;

/** Climatización. */
public class BYDAutoAcDevice {
    public static BYDAutoAcDevice getInstance(Context ctx) { return new BYDAutoAcDevice(); }
    /** Temperatura exterior. */
    public float getOutCarTemperature() { return 0f; }
    /** 0 = Celsius, 1 = Fahrenheit (a confirmar). */
    public int getTemperatureUnit() { return 0; }
    public void setAcState(boolean on) {}
    public void setTemperature(float temp) {}
}
