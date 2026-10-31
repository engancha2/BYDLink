package android.hardware.bydauto.bodywork;

public abstract class AbsBYDAutoBodyworkListener {
    public void onDoorStateChanged(int doorState) {}
    public void onSafetyBeltStatusChanged(int position, int status) {}
}
