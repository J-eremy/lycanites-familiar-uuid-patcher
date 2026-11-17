package com.lycanitesmobs;

/**
 * Test-only stand-in for the real mod class, so PlayerFamiliars can be loaded
 * without Minecraft/Forge on the classpath. Placed first on the classpath, it
 * shadows the real com.lycanitesmobs.LycanitesMobs from the jar.
 */
public class LycanitesMobs {
    public static void logInfo(String category, String message) {
        System.out.println("[log] " + message);
    }
}
