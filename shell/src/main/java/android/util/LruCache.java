package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wyrm Desktop: Android's LruCache (size-bounded, least recently used out
 * first, thread-safe), as the shared avatar and trail image caches use it.
 */
public class LruCache<K, V> {
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(0, 0.75f, true);
    private final int maxSize;
    private int size;

    public LruCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize <= 0");
        this.maxSize = maxSize;
    }

    protected int sizeOf(K key, V value) { return 1; }

    public final synchronized V get(K key) {
        if (key == null) throw new NullPointerException("key == null");
        return map.get(key);
    }

    public final synchronized V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("key == null || value == null");
        size += sizeOf(key, value);
        V previous = map.put(key, value);
        if (previous != null) size -= sizeOf(key, previous);
        trimToSize(maxSize);
        return previous;
    }

    public final synchronized V remove(K key) {
        if (key == null) throw new NullPointerException("key == null");
        V previous = map.remove(key);
        if (previous != null) size -= sizeOf(key, previous);
        return previous;
    }

    public final synchronized void evictAll() { trimToSize(-1); }

    public final synchronized int size() { return size; }

    public final synchronized int maxSize() { return maxSize; }

    private void trimToSize(int limit) {
        while (size > limit && !map.isEmpty()) {
            Map.Entry<K, V> eldest = map.entrySet().iterator().next();
            map.remove(eldest.getKey());
            size -= sizeOf(eldest.getKey(), eldest.getValue());
        }
    }
}
