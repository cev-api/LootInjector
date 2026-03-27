package dev.cevapi.lootinjector.common;

public final class ChanceUtil {
    private ChanceUtil() {
    }

    public static double clamp(double chance) {
        if (Double.isNaN(chance) || Double.isInfinite(chance)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(100.0D, chance));
    }

    public static String format(double chance) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", clamp(chance));
    }
}
