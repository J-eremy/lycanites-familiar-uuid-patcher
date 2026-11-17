package com.lycanitesmobs.core.pets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lycanitesmobs.LycanitesMobs;
import com.lycanitesmobs.core.entity.ExtendedPlayer;
import com.lycanitesmobs.core.info.CreatureInfo;
import com.lycanitesmobs.core.info.CreatureManager;
import com.lycanitesmobs.core.info.Variant;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.commons.io.IOUtils;

/**
 * Drop-in replacement for Lycanites Mobs 2.0.8.10's PlayerFamiliars.
 *
 * Behaviour is identical to the shipped class except for one thing: the UUID that is
 * sent to https://service.lycanitesmobs.com/api/v1/familiars is taken from
 * {@link #REPORTED_UUID} instead of the local player's UUID, while the returned
 * familiars are still stored under the local player's UUID (otherwise the pet manager
 * and the per-player lookup would never see them).
 *
 * REPORTED_UUID resolution order:
 *   1. -Dlycanites.familiar.uuid=<uuid>        (JVM argument)
 *   2. lycanitesmobs-familiar-uuid.txt          (first non-comment line, sitting next to the mod jar)
 *   3. config/lycanitesmobs-familiar-uuid.txt  (first non-comment line, relative to game dir)
 *   4. embedded default UUID
 * A value of "off" or "none" (any case) restores the stock behaviour.
 */
public class PlayerFamiliars {
    public static PlayerFamiliars INSTANCE = new PlayerFamiliars();

    /** Embedded fallback reported UUID. */
    private static final String DEFAULT_REPORTED_UUID = "a0aed996-9645-44e2-9468-5679fc25f44a";

    /** UUID sent upstream instead of the local player's UUID, or null for stock behaviour. */
    public static final UUID REPORTED_UUID = resolveReportedUuid();

    public Map<UUID, Map<UUID, PetEntry>> playerFamiliars = new HashMap<UUID, Map<UUID, PetEntry>>();
    public Map<UUID, Long> playerFamiliarLoadedTimes = new HashMap<UUID, Long>();
    public List<String> familiarBlacklist = new ArrayList<String>();
    public SSLContext sslContext;

    public PlayerFamiliars() {
        // Kept exactly as shipped: the service certificate has since expired, so a
        // verifying SSLContext would make the familiar lookup fail outright.
        TrustManager[] trustAllCerts = new TrustManager[]{new TrustAllCertificates()};
        try {
            this.sslContext = SSLContext.getInstance("SSL");
            this.sslContext.init(null, trustAllCerts, new SecureRandom());
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Reads the configured reported UUID, or null when the feature is switched off. */
    private static UUID resolveReportedUuid() {
        String value = System.getProperty("lycanites.familiar.uuid");
        String source = "JVM argument";

        if (isBlank(value)) {
            File file = jarAdjacentFile();
            value = readFirstLine(file);
            if (!isBlank(value)) {
                source = "file next to the mod jar (" + file.getName() + ")";
            }
        }

        if (isBlank(value)) {
            File file = new File("config", "lycanitesmobs-familiar-uuid.txt");
            value = readFirstLine(file);
            if (!isBlank(value)) {
                source = "game config (" + file.getPath() + ")";
            }
        }

        if (isBlank(value)) {
            value = DEFAULT_REPORTED_UUID;
            source = "built-in default";
        }
        value = value.trim();

        if ("off".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            LycanitesMobs.logInfo("", "[Familiars] UUID override disabled, using the local player's UUID.");
            return null;
        }

        try {
            UUID uuid = UUID.fromString(value);
            LycanitesMobs.logInfo("", "[Familiars] Reporting UUID " + uuid + " (from " + source
                    + ") to the online familiars service.");
            return uuid;
        }
        catch (Throwable throwable) {
            LycanitesMobs.logInfo("", "[Familiars] Invalid UUID '" + value + "' from " + source
                    + ", falling back to " + DEFAULT_REPORTED_UUID + ".");
            return UUID.fromString(DEFAULT_REPORTED_UUID);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** First non-empty line that is not a comment, or null if the file is absent/unreadable. */
    private static String readFirstLine(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), Charset.defaultCharset())) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                return trimmed;
            }
        }
        catch (Throwable throwable) {
            LycanitesMobs.logInfo("", "[Familiars] Could not read " + file + ": " + throwable);
        }
        return null;
    }

    /**
     * The config file sitting next to the mod jar, so players can drop two files into
     * mods/ without editing anything else. Returns null when not running from a jar.
     */
    private static File jarAdjacentFile() {
        try {
            java.security.CodeSource codeSource = PlayerFamiliars.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            File jar = new File(codeSource.getLocation().toURI());
            if (jar.isFile()) {
                return new File(jar.getParentFile(), "lycanitesmobs-familiar-uuid.txt");
            }
        }
        catch (Throwable throwable) {
            // Security manager, non-file URL (dev environment), etc. - fall through.
        }
        return null;
    }

    /** Original entry point; stores entries under the UUID the service reports. */
    public void parseFamiliarJSON(String jsonString) {
        this.parseFamiliarJSON(jsonString, null);
    }

    /**
     * @param ownerUuid when non-null, familiars are attached to this UUID regardless of the
     *                  minecraft_uuid field in the response.
     */
    public void parseFamiliarJSON(String jsonString, UUID ownerUuid) {
        try {
            JsonParser jsonParser = new JsonParser();
            JsonObject json = jsonParser.parse(jsonString).getAsJsonObject();
            JsonArray jsonArray = json.getAsJsonArray("data");
            for (JsonElement jsonElement : jsonArray) {
                try {
                    JsonObject familiarJson = jsonElement.getAsJsonObject();
                    UUID reported_uuid = UUID.fromString(familiarJson.get("minecraft_uuid").getAsString());
                    if (this.familiarBlacklist.contains(reported_uuid.toString())) {
                        continue;
                    }
                    UUID minecraft_uuid = ownerUuid != null ? ownerUuid : reported_uuid;
                    if (ownerUuid != null && this.familiarBlacklist.contains(minecraft_uuid.toString())) {
                        continue;
                    }

                    UUID familiar_uuid = UUID.fromString(familiarJson.get("familiar_uuid").getAsString());
                    String familiar_species = familiarJson.get("familiar_species").getAsString();
                    CreatureInfo creatureInfo = CreatureManager.getInstance().getCreature(familiar_species);
                    int familiar_subspecies = familiarJson.get("familiar_subspecies").getAsInt();
                    familiar_species = this.handleLegacySpecies(familiar_species);
                    int familiar_variant = familiarJson.get("familiar_variant").getAsInt();
                    Variant creatureVariant = creatureInfo != null ? creatureInfo.getSubspecies(familiar_subspecies).getVariant(familiar_variant) : null;
                    String familiar_name = familiarJson.get("familiar_name").getAsString();
                    String familiar_color = familiarJson.get("familiar_color").getAsString();
                    double familiar_size = familiarJson.get("familiar_size").getAsDouble();
                    if (familiar_size <= 0.0) {
                        familiar_size = 0.5;
                        if (creatureVariant != null && creatureVariant.scale != 1.0) {
                            familiar_size *= 1.0 / creatureVariant.scale;
                        }
                    }

                    PetEntryFamiliar familiarEntry = new PetEntryFamiliar(familiar_uuid, null, familiar_species.toLowerCase());
                    familiarEntry.setEntitySubspecies(familiar_subspecies);
                    familiarEntry.setEntityVariant(familiar_variant);
                    familiarEntry.setEntitySize(familiar_size);
                    if (!"".equals(familiar_name)) {
                        familiarEntry.setEntityName(familiar_name);
                    }
                    familiarEntry.setColor(familiar_color);

                    if (!this.playerFamiliars.containsKey(minecraft_uuid)) {
                        this.playerFamiliars.put(minecraft_uuid, new HashMap<UUID, PetEntry>());
                    }
                    if (!this.playerFamiliars.get(minecraft_uuid).containsKey(familiar_uuid)) {
                        this.playerFamiliars.get(minecraft_uuid).put(familiar_uuid, familiarEntry);
                        continue;
                    }
                    PetEntry existingEntry = this.playerFamiliars.get(minecraft_uuid).get(familiar_uuid);
                    existingEntry.copy(familiarEntry);
                }
                catch (Exception exception) {
                    // Malformed entry, skip it exactly like the original.
                }
            }
        }
        catch (Exception exception) {
            // Malformed response, skip it exactly like the original.
        }
    }

    public Map<UUID, PetEntry> getFamiliarsForPlayer(EntityPlayer player) {
        long currentTime = System.currentTimeMillis() / 1000L;
        long loadedTime = this.getPlayerFamiliarLoadedTime(player);
        if (loadedTime < 0L || currentTime - loadedTime > 1800L) {
            this.updatePlayerFamiliarLoadedTime(player);
            FamiliarLoader familiarLoader = new FamiliarLoader(player);
            Thread thread = new Thread(familiarLoader);
            thread.start();
        }

        Map<UUID, PetEntry> playerFamiliarEntries = new HashMap<UUID, PetEntry>();
        if (this.playerFamiliars.containsKey(player.func_110124_au())) {
            playerFamiliarEntries = this.playerFamiliars.get(player.func_110124_au());
            for (PetEntry familiarEntry : playerFamiliarEntries.values()) {
                if (familiarEntry.host != null) {
                    continue;
                }
                familiarEntry.host = player;
            }
        }
        return playerFamiliarEntries;
    }

    public long getPlayerFamiliarLoadedTime(EntityPlayer player) {
        UUID uuid = player.func_110124_au();
        if (!this.playerFamiliarLoadedTimes.containsKey(uuid)) {
            return -1L;
        }
        return this.playerFamiliarLoadedTimes.get(uuid);
    }

    public void updatePlayerFamiliarLoadedTime(EntityPlayer player) {
        this.playerFamiliarLoadedTimes.put(player.func_110124_au(), System.currentTimeMillis() / 1000L);
    }

    public String handleLegacySpecies(String species) {
        if (species.equalsIgnoreCase("apollyon")) return "archvile";
        if (species.equalsIgnoreCase("belphegor")) return "belph";
        if (species.equalsIgnoreCase("behemophet")) return "behemoth";
        if (species.equalsIgnoreCase("malwrath")) return "cacodemon";
        if (species.equalsIgnoreCase("naxiris")) return "beholder";
        if (species.equalsIgnoreCase("zephyr")) return "djinn";
        if (species.equalsIgnoreCase("raidra")) return "zephyr";
        if (species.equalsIgnoreCase("ningen")) return "dweller";
        if (species.equalsIgnoreCase("cherufe")) return "lobber";
        if (species.equalsIgnoreCase("ostimien")) return "lurker";
        if (species.equalsIgnoreCase("kathoga")) return "pinky";
        if (species.equalsIgnoreCase("brucha")) return "quillbeast";
        if (species.equalsIgnoreCase("stryder")) return "strider";
        if (species.equalsIgnoreCase("lycosa")) return "tarantula";
        if (species.equalsIgnoreCase("jouste")) return "joust";
        return species;
    }

    public static class FamiliarLoader implements Runnable {
        EntityPlayer player;

        public FamiliarLoader(EntityPlayer player) {
            this.player = player;
        }

        @Override
        public void run() {
            this.getForPlayerUUID(this.player.func_110124_au());
            ExtendedPlayer extendedPlayer = ExtendedPlayer.getForPlayer(this.player);
            if (extendedPlayer != null) {
                extendedPlayer.loadFamiliars();
            }
            INSTANCE.updatePlayerFamiliarLoadedTime(this.player);
        }

        private void getForPlayerUUID(UUID ownerUuid) {
            UUID queryUuid = REPORTED_UUID != null ? REPORTED_UUID : ownerUuid;
            String jsonString;
            try {
                URL url = new URL("https://service.lycanitesmobs.com/api/v1/familiars?minecraft_uuid=" + queryUuid.toString());
                HttpsURLConnection urlConnection = (HttpsURLConnection)url.openConnection();
                urlConnection.setSSLSocketFactory(PlayerFamiliars.INSTANCE.sslContext.getSocketFactory());
                urlConnection.setRequestProperty("Authorization", "Bearer 7ed1f44cbc1aff693e604075f23d56402983a4a0");
                String osName = System.getProperty("os.name");
                urlConnection.setRequestProperty("User-Agent", "Minecraft 1.12.2 (" + osName + ") LycanitesMobs " + "2.0.8.10");
                try (InputStream inputStream = urlConnection.getInputStream()) {
                    jsonString = IOUtils.toString(inputStream, (Charset)null);
                }
                LycanitesMobs.logInfo("", "Online familiars loaded successfully for " + queryUuid + ".");
            }
            catch (Throwable e) {
                LycanitesMobs.logInfo("", "Unable to access the online familiars service.");
                e.printStackTrace();
                return;
            }
            // Key the entries by the real player's UUID so PetManager and the per-player
            // lookup keep working; only the upstream query used the reported UUID.
            INSTANCE.parseFamiliarJSON(jsonString, ownerUuid);
        }
    }

    /**
     * Package-private on purpose: with a private nested class javac emits a
     * synthetic accessor constructor that references a marker class
     * (PlayerFamiliars$1). Keeping the class and its constructor package-private
     * avoids that, so the jar contains exactly the three classes we ship.
     */
    static final class TrustAllCertificates implements X509TrustManager {
        TrustAllCertificates() {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certs, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certs, String authType) {
        }
    }
}
