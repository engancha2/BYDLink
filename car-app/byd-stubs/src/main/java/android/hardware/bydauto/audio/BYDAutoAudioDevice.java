package android.hardware.bydauto.audio;

import android.content.Context;

public class BYDAutoAudioDevice {
    public static BYDAutoAudioDevice getInstance(Context ctx) { return new BYDAutoAudioDevice(); }
    public void registerListener(AbsBYDAutoAudioListener l, int[] featureIds) {}
    public void unregisterListener(AbsBYDAutoAudioListener l) {}
}
