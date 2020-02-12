package net.kriomant.gortrans;

import android.graphics.drawable.Drawable;
import com.google.android.maps.ItemizedOverlay;
import com.google.android.maps.OverlayItem;

/**
 * Class intended to expose protected static members boundCenterBottom and boundCenter to Scala code.
 */
public abstract class ItemizedOverlayBridge<Item extends OverlayItem> extends ItemizedOverlay<Item> {
    public ItemizedOverlayBridge(Drawable defaultMarker) {
        super(defaultMarker);
    }

    public static Drawable boundCenterBottom_(Drawable drawable) {
        return ItemizedOverlay.boundCenterBottom(drawable);
    }

    public static Drawable boundCenter_(Drawable drawable) {
        return ItemizedOverlay.boundCenter(drawable);
    }
}
