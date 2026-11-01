package android.hardware.bydauto.audio;

import android.hardware.bydauto.BYDAutoEventValue;

public abstract class AbsBYDAutoAudioListener {
    /** Evento genérico por feature id (p. ej. AUDIO_VOICE_INPUT_KEY_STATUS, el botón de voz). */
    public void onDataEventChanged(int eventType, BYDAutoEventValue value) {}
}
