package ml.easychad.lax.android;

/**
 * Created by mtwain on 11.12.14.
 */
public enum TrialController {
    INSTANCE;

    private boolean isAppEnable = true;

    public boolean isAppEnable() {
        return isAppEnable;
    }

    public void setAppEnable(boolean isAppEnable) {
        this.isAppEnable = isAppEnable;
    }
}
