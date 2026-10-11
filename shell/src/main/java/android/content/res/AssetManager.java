package android.content.res;

import java.io.FileNotFoundException;
import java.io.InputStream;

/** Wyrm Desktop: an APK's assets are the app's resources under assets/. */
public final class AssetManager {
    public InputStream open(String name) throws FileNotFoundException {
        InputStream stream = AssetManager.class.getClassLoader().getResourceAsStream("assets/" + name);
        if (stream == null) throw new FileNotFoundException(name);
        return stream;
    }
}
