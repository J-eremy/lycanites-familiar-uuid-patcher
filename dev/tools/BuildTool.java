import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Cross-platform build/verify/test driver for the Lycanites Mobs familiar-UUID patch.
 *
 * Requires a JDK (javac + java); needs no shell, zip, unzip, curl or PowerShell.
 * Run from the patch directory:
 *
 *   java BuildTool build  <original.jar> <output.jar>
 *   java BuildTool verify <original.jar> <patched.jar>
 *   java BuildTool test   <patched.jar>
 *   java BuildTool all    <original.jar> <output.jar>
 *
 * Compiled to patch/build/tool by build.sh / build.ps1.
 */
public final class BuildTool {

    private static final String GSON_VERSION = "2.8.0";
    private static final String COMMONS_IO_VERSION = "2.5";

    private static final String CLS = "com/lycanitesmobs/core/pets/PlayerFamiliars";
    private static final String STALE = CLS + "$1.class";

    /** Entries replaced in the original jar, mapped to their compiled file name. */
    private static final String[] REPLACEMENTS = {
        CLS + ".class",
        CLS + "$FamiliarLoader.class",
        CLS + "$TrustAllCertificates.class"
    };

    private static final Path PATCH_DIR = Paths.get("").toAbsolutePath();

    public static void main(String[] args) {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        try {
            if ("build".equals(command)) {
                Path out = require(args, 2);
                build(Paths.get(args[1]), out);
                verify(Paths.get(args[1]), out);
            }
            else if ("verify".equals(command)) {
                verify(Paths.get(args[1]), require(args, 2));
            }
            else if ("test".equals(command)) {
                test(Paths.get(args[1]));
            }
            else if ("all".equals(command)) {
                Path out = require(args, 2);
                build(Paths.get(args[1]), out);
                verify(Paths.get(args[1]), out);
                test(out);
            }
            else {
                usage();
                System.exit(2);
            }
        }
        catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Path require(String[] args, int index) {
        if (args.length <= index) {
            usage();
            System.exit(2);
        }
        return Paths.get(args[index]);
    }

    private static void usage() {
        System.out.println("usage:");
        System.out.println("  java BuildTool build  <original.jar> <output.jar>");
        System.out.println("  java BuildTool verify <original.jar> <patched.jar>");
        System.out.println("  java BuildTool test   <patched.jar>");
        System.out.println("  java BuildTool all    <original.jar> <output.jar>");
        System.out.println("run from the patch directory");
    }

    // ------------------------------------------------------------------ build

    static void build(Path original, Path output) throws Exception {
        if (!Files.isRegularFile(original)) {
            throw new IOException("original jar not found: " + original.toAbsolutePath());
        }
        Path libs = PATCH_DIR.resolve("libs");
        Path classes = PATCH_DIR.resolve("build").resolve("classes");
        Path stubsOut = PATCH_DIR.resolve("build").resolve("stubs");
        Files.createDirectories(libs);
        Files.createDirectories(classes);
        Files.createDirectories(stubsOut);
        if (Files.exists(classes)) {
            deleteTree(classes);
        }
        Files.createDirectories(classes);

        Path gson = fetch(libs.resolve("gson-" + GSON_VERSION + ".jar"),
                "https://repo1.maven.org/maven2/com/google/code/gson/gson/" + GSON_VERSION
                        + "/gson-" + GSON_VERSION + ".jar");
        Path commonsIo = fetch(libs.resolve("commons-io-" + COMMONS_IO_VERSION + ".jar"),
                "https://repo1.maven.org/maven2/commons-io/commons-io/" + COMMONS_IO_VERSION
                        + "/commons-io-" + COMMONS_IO_VERSION + ".jar");

        System.out.println("==> compiling Minecraft signature stubs");
        compile(stubsOut, null, Arrays.asList(
                PATCH_DIR.resolve("stubs/net/minecraft/entity/EntityLivingBase.java"),
                PATCH_DIR.resolve("stubs/net/minecraft/entity/player/EntityPlayer.java")));

        System.out.println("==> compiling patched PlayerFamiliars");
        String classpath = String.join(File.pathSeparator,
                original.toAbsolutePath().toString(),
                gson.toAbsolutePath().toString(),
                commonsIo.toAbsolutePath().toString(),
                stubsOut.toAbsolutePath().toString());
        compile(classes, classpath, Arrays.asList(
                PATCH_DIR.resolve("src/com/lycanitesmobs/core/pets/PlayerFamiliars.java")));

        System.out.println("==> writing " + output.toAbsolutePath());
        assertNoStrayClasses(classes);
        patchJar(original, output, classes);
    }

    /**
     * Fails if compilation produced a class that is not packaged. A missing
     * PlayerFamiliars$N.class would surface at runtime as NoClassDefFoundError
     * (javac emits synthetic marker/accessor classes in some cases).
     */
    private static void assertNoStrayClasses(Path classes) throws IOException {
        Set<String> packaged = new HashSet<String>(Arrays.asList(REPLACEMENTS));
        List<String> stray = new ArrayList<String>();
        try (java.util.stream.Stream<Path> stream = Files.walk(classes)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!path.toString().endsWith(".class")) {
                    continue;
                }
                String name = classes.relativize(path).toString().replace(File.separatorChar, '/');
                if (!packaged.contains(name)) {
                    stray.add(name);
                }
            }
        }
        if (!stray.isEmpty()) {
            throw new IllegalStateException(
                    "compilation produced classes that would not be packaged: " + stray
                    + " - add them to REPLACEMENTS or remove the construct that creates them");
        }
    }

    private static Path fetch(Path target, String url) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        System.out.println("==> downloading " + target.getFileName());
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status != 200) {
            throw new IOException("HTTP " + status + " for " + url);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {
            copy(in, out);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Compiles the given sources into outDir using the in-process JDK compiler. */
    private static void compile(Path outDir, String classpath, List<Path> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No Java compiler available. Run with a JDK, not a JRE.");
        }
        List<String> options = new ArrayList<String>();
        options.addAll(releaseArgs());
        options.add("-nowarn");
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-d");
        options.add(outDir.toAbsolutePath().toString());
        if (classpath != null) {
            options.add("-cp");
            options.add(classpath);
        }

        List<File> files = new ArrayList<File>();
        for (Path source : sources) {
            files.add(source.toAbsolutePath().toFile());
        }

        StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        try {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(files);
            StringWriter diagnostics = new StringWriter();
            boolean ok = compiler.getTask(diagnostics, fileManager, null, options, null, units).call();
            if (!ok) {
                throw new IllegalStateException("javac failed:\n" + diagnostics);
            }
        }
        finally {
            fileManager.close();
        }
    }

    /** Targets Java 8 bytecode on every JDK from 8 onwards. */
    private static List<String> releaseArgs() {
        String version = System.getProperty("java.specification.version", "");
        if (version.startsWith("1.")) {
            // JDK 8: --release does not exist, and 8 is the default target anyway.
            return Arrays.asList("-source", "8", "-target", "8");
        }
        return Arrays.asList("--release", "8");
    }

    /**
     * Rewrites the jar: every original entry is copied byte for byte (same CRC,
     * same order) except the three patched classes, and the stale anonymous
     * TrustManager class is dropped.
     */
    private static void patchJar(Path original, Path output, Path classes) throws IOException {
        if (original.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new IOException("output jar must differ from the original jar");
        }
        Map<String, Path> replacements = new LinkedHashMap<String, Path>();
        for (String name : REPLACEMENTS) {
            Path file = classes.resolve(name);
            if (!Files.isRegularFile(file)) {
                throw new IOException("missing compiled class: " + file);
            }
            replacements.put(name, file);
        }

        Path tmp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Set<String> written = new HashSet<String>();

        try (ZipFile zip = new ZipFile(original.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (STALE.equals(name)) {
                    continue;
                }
                ZipEntry out = new ZipEntry(name);
                if (entry.getTime() != -1) {
                    out.setTime(entry.getTime());
                }
                if (entry.getComment() != null) {
                    out.setComment(entry.getComment());
                }
                out.setMethod(entry.getMethod());
                if (entry.getMethod() == ZipEntry.STORED) {
                    out.setSize(entry.getSize());
                    out.setCrc(entry.getCrc());
                }
                zos.putNextEntry(out);
                if (replacements.containsKey(name)) {
                    Files.copy(replacements.get(name), zos);
                    written.add(name);
                }
                else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        copy(in, zos);
                    }
                }
                zos.closeEntry();
            }
            for (Map.Entry<String, Path> replacement : replacements.entrySet()) {
                if (written.contains(replacement.getKey())) {
                    continue;
                }
                ZipEntry out = new ZipEntry(replacement.getKey());
                zos.putNextEntry(out);
                Files.copy(replacement.getValue(), zos);
                zos.closeEntry();
            }
        }
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    // ----------------------------------------------------------------- verify

    static void verify(Path original, Path patched) throws Exception {
        System.out.println("== verifying " + patched.getFileName());
        int failures = 0;

        Map<String, String> before = crcs(original);
        Map<String, String> after = crcs(patched);

        Set<String> changed = new HashSet<String>();
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                changed.add(name);
            }
            else if (!before.get(name).equals(after.get(name))) {
                changed.add(name);
            }
        }
        for (String name : after.keySet()) {
            if (!before.containsKey(name)) {
                changed.add(name);
            }
        }

        Set<String> allowed = new HashSet<String>(Arrays.asList(
                CLS + ".class",
                CLS + "$FamiliarLoader.class",
                CLS + "$TrustAllCertificates.class",
                STALE));
        Set<String> unexpected = new HashSet<String>(changed);
        unexpected.removeAll(allowed);
        failures += check(unexpected.isEmpty(), "only PlayerFamiliars* entries changed"
                + (unexpected.isEmpty() ? "" : " (unexpected: " + unexpected + ")"));

        int expectedSize = before.size()
                - (before.containsKey(STALE) ? 1 : 0)
                + (before.containsKey(CLS + "$TrustAllCertificates.class") ? 0 : 1);
        failures += check(expectedSize == after.size(),
                "entry count " + before.size() + " -> " + after.size()
                        + " (expected " + expectedSize + ": stale $1.class dropped, new class added)");

        byte[] cls;
        try (ZipFile zip = new ZipFile(patched.toFile())) {
            ZipEntry entry = zip.getEntry(CLS + ".class");
            if (entry == null) {
                throw new IOException("patched jar has no " + CLS + ".class");
            }
            cls = readAll(zip.getInputStream(entry));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean anySignature = false;
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) {
                    anySignature = true;
                }
            }
            failures += check(!anySignature, "jar is not signed (no re-signing needed)");
        }

        failures += check(cls.length > 8 && (cls[6] & 0xFF) == 0 && (cls[7] & 0xFF) == 52,
                "PlayerFamiliars.class is Java 8 bytecode (major 52)");

        String constants = new String(cls, StandardCharsets.ISO_8859_1);
        failures += check(constants.contains("func_110124_au"),
                "calls EntityPlayer.func_110124_au (SRG getUniqueID)");
        failures += check(constants.contains("()Ljava/util/UUID;"),
                "UUID return descriptor present");
        failures += check(constants.contains("parseFamiliarJSON"),
                "original parseFamiliarJSON entry point preserved");

        System.out.println(failures == 0
                ? "all structural checks passed"
                : "FAILED (" + failures + " problem(s))");
        if (failures != 0) {
            throw new IllegalStateException("verification failed");
        }
    }

    private static int check(boolean condition, String what) {
        System.out.println((condition ? "ok:   " : "FAIL: ") + what);
        return condition ? 0 : 1;
    }

    private static Map<String, String> crcs(Path jar) throws IOException {
        Map<String, String> map = new TreeMap<String, String>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                map.put(entry.getName(), Long.toHexString(entry.getCrc()));
            }
        }
        return map;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------- test

    static void test(Path jar) throws Exception {
        if (!Files.isRegularFile(jar)) {
            throw new IOException("patched jar not found: " + jar.toAbsolutePath());
        }
        Path libs = PATCH_DIR.resolve("libs");
        Path gson = libs.resolve("gson-" + GSON_VERSION + ".jar");
        Path commonsIo = libs.resolve("commons-io-" + COMMONS_IO_VERSION + ".jar");
        if (!Files.isRegularFile(gson) || !Files.isRegularFile(commonsIo)) {
            throw new IOException("run `BuildTool build` first (libs/ is missing dependencies)");
        }

        Path work = Files.createTempDirectory("lyc-familiar-test");
        try {
            Path stubOut = work.resolve("stub");
            Path testOut = work.resolve("test");
            Files.createDirectories(stubOut);
            Files.createDirectories(testOut);

            System.out.println("==> compiling test stubs");
            compile(stubOut, null, Arrays.asList(
                    PATCH_DIR.resolve("stubs/net/minecraft/entity/EntityLivingBase.java"),
                    PATCH_DIR.resolve("stubs/net/minecraft/entity/player/EntityPlayer.java"),
                    PATCH_DIR.resolve("test/stub/com/lycanitesmobs/LycanitesMobs.java")));

            String cp = String.join(File.pathSeparator,
                    stubOut.toAbsolutePath().toString(),
                    jar.toAbsolutePath().toString(),
                    gson.toAbsolutePath().toString(),
                    commonsIo.toAbsolutePath().toString());

            System.out.println("==> compiling test");
            compile(testOut, cp, Arrays.asList(PATCH_DIR.resolve("test/Test.java")));

            String stubCp = stubOut.toAbsolutePath().toString();
            String depsCp = gson.toAbsolutePath() + File.pathSeparator
                    + commonsIo.toAbsolutePath() + File.pathSeparator
                    + testOut.toAbsolutePath();
            // Use a copy in the work directory: the jar under test may sit next to a
            // lycanitesmobs-familiar-uuid.txt (the patcher writes one), which would
            // otherwise override the game-config scenarios below.
            Path testJar = work.resolve("patched-under-test.jar");
            Files.copy(jar, testJar, StandardCopyOption.REPLACE_EXISTING);
            String runCp = stubCp + File.pathSeparator + testJar.toAbsolutePath()
                    + File.pathSeparator + depsCp;
            String defaultUuid = sourceDefaultUuid();

            int failures = 0;
            failures += scenario(work, runCp, "default (no config)", defaultUuid, null, null);
            failures += scenario(work, runCp, "config file", "11111111-2222-3333-4444-555555555555",
                    "# comment\n11111111-2222-3333-4444-555555555555\n", null);
            failures += scenario(work, runCp, "jvm arg beats file", "99999999-8888-7777-6666-555555555555",
                    "11111111-2222-3333-4444-555555555555\n",
                    "-Dlycanites.familiar.uuid=99999999-8888-7777-6666-555555555555");
            failures += scenario(work, runCp, "file=off restores stock", "null", "off\n", null);
            failures += scenario(work, runCp, "invalid value falls back", defaultUuid, "not-a-uuid\n", null);
            failures += scenario(work, runCp, "no config dir", defaultUuid, null, null);
            failures += jarScenario(work, stubCp, depsCp, testJar,
                    "33333333-4444-5555-6666-777777777777", "jar-adjacent");

            System.out.println();
            if (failures != 0) {
                throw new IllegalStateException("test failed (" + failures + " problem(s))");
            }
            System.out.println("all checks passed");
        }
        finally {
            deleteTree(work);
        }
    }

    /** Expected default, read from the patched source so it is never duplicated. */
    private static String sourceDefaultUuid() throws IOException {
        String source = new String(Files.readAllBytes(
                PATCH_DIR.resolve("src/com/lycanitesmobs/core/pets/PlayerFamiliars.java")),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "DEFAULT_REPORTED_UUID\\s*=\\s*\"([0-9a-fA-F-]{36})\"").matcher(source);
        if (!matcher.find()) {
            throw new IllegalStateException("could not read DEFAULT_REPORTED_UUID from source");
        }
        return matcher.group(1);
    }

    private static int scenario(Path work, String classpath, String name, String expected,
                                String fileContent, String jvmArg) throws Exception {
        Path game = work.resolve(name.replaceAll("[^A-Za-z0-9]+", "-"));
        Files.createDirectories(game);
        if (fileContent != null) {
            Path config = game.resolve("config");
            Files.createDirectories(config);
            Files.write(config.resolve("lycanitesmobs-familiar-uuid.txt"),
                    fileContent.getBytes(StandardCharsets.UTF_8));
        }
        return assertScenario(name, expected, runTest(game, classpath, jvmArg));
    }

    /**
     * Exercise the "config file next to the mod jar" path: the test classpath must
     * point at a jar (not a directory) for the code source to resolve.
     */
    private static int jarScenario(Path work, String stubCp, String depsCp, Path jar,
                                   String expected, String name) throws Exception {
        Path game = work.resolve("jar-adjacent");
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Path copied = mods.resolve("lycanitesmobs-test.jar");
        Files.copy(jar, copied, StandardCopyOption.REPLACE_EXISTING);
        Files.write(mods.resolve("lycanitesmobs-familiar-uuid.txt"),
                (expected + "\n").getBytes(StandardCharsets.UTF_8));

        String classpath = stubCp + File.pathSeparator + copied.toAbsolutePath()
                + File.pathSeparator + depsCp;
        return assertScenario(name, expected, runTest(game, classpath, null));
    }

    private static String runTest(Path game, String classpath, String jvmArg) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        if (jvmArg != null) {
            command.add(jvmArg);
        }
        command.add("-cp");
        command.add(classpath);
        command.add("Test");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(game.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            copy(in, buffer);
        }
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "TIMEOUT";
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int assertScenario(String name, String expected, String output) {
        String actual = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith("RESULT ")) {
                actual = line.substring("RESULT ".length()).trim();
                break;
            }
        }

        int failures = 0;
        if (!expected.equals(actual)) {
            failures++;
        }
        boolean apiOk = output.contains("API OK");
        if (!apiOk) {
            failures++;
        }

        System.out.println((failures == 0 ? "PASS " : "FAIL ") + pad(name, 28)
                + " expected=" + pad(expected, 37) + " actual=" + actual);
        if (!apiOk) {
            for (String line : output.split("\\R")) {
                if (line.startsWith("API FAIL")) {
                    System.out.println("      " + line);
                }
            }
        }
        if (failures != 0 && actual != null) {
            System.out.println("      --- child output ---");
            for (String line : output.split("\\R")) {
                System.out.println("      " + line);
            }
        }
        return failures;
    }

    private static String pad(String value, int width) {
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String javaExecutable() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        Path candidate = bin.resolve("java");
        if (!Files.isRegularFile(candidate)) {
            candidate = bin.resolve("java.exe");
        }
        return candidate.toAbsolutePath().toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException ignored) {
                        // best effort
                    }
                });
    }
}
