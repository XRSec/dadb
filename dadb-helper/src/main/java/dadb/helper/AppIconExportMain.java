package dadb.helper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class AppIconExportMain {
    private static final int ICON_SIZE = 96;
    private static final int MAX_ICON_THREADS = 4;
    private static final String USAGE =
            "usage: ping | runtime | context | pm | apps | " +
                    "probe_classload | probe_current | probe_systemmain | probe_systemcontext | " +
                    "probe_pmservice | probe_appglobals_pm | probe_apppm_ctors | probe_apppm_ctor | " +
                    "probe_apppm_apps | probe_apppm_appinfo <packageName> | " +
                    "probe_getpm | probe_apps | probe_appinfo <packageName> | " +
                    "iconprobe <packageName> | list <offset> <limit> <includeSystem> | " +
                    "icons <requestBase64> | " +
                    "icon <packageName> [localHash]";

    private AppIconExportMain() {}

    private static final class BatchIconResult {
        final String packageName;
        final String label;
        final String iconHash;
        final String entryName;
        final byte[] imageBytes;

        BatchIconResult(
                String packageName,
                String label,
                String iconHash,
                String entryName,
                byte[] imageBytes
        ) {
            this.packageName = packageName;
            this.label = label;
            this.iconHash = iconHash;
            this.entryName = entryName;
            this.imageBytes = imageBytes;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        String command = args[0];
        if ("ping".equals(command)) {
            System.out.println("PING_OK");
            return;
        }

        if ("runtime".equals(command)) {
            Object activityThread = resolveActivityThread();
            System.out.println(activityThread != null ? "RUNTIME_OK" : "RUNTIME_NULL");
            return;
        }

        if ("probe_classload".equals(command)) {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            System.out.println("PROBE_CLASSLOAD_OK\t" + activityThreadClass.getName());
            return;
        }

        if ("probe_current".equals(command)) {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            System.out.println(
                    activityThread != null
                            ? "PROBE_CURRENT_OK\t" + activityThread.getClass().getName()
                            : "PROBE_CURRENT_NULL"
            );
            return;
        }

        if ("probe_systemmain".equals(command)) {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
            System.out.println(
                    activityThread != null
                            ? "PROBE_SYSTEMMAIN_OK\t" + activityThread.getClass().getName()
                            : "PROBE_SYSTEMMAIN_NULL"
            );
            return;
        }

        if ("probe_pmservice".equals(command)) {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Object binder = serviceManagerClass.getMethod("getService", String.class).invoke(null, "package");
            System.out.println(
                    binder != null
                            ? "PROBE_PMSERVICE_OK\t" + binder.getClass().getName()
                            : "PROBE_PMSERVICE_NULL"
            );
            return;
        }

        if ("probe_appglobals_pm".equals(command)) {
            Object ipm = getAppGlobalsPackageManager();
            System.out.println(
                    ipm != null
                            ? "PROBE_APPGLOBALS_PM_OK\t" + ipm.getClass().getName()
                            : "PROBE_APPGLOBALS_PM_NULL"
            );
            return;
        }

        if ("probe_apppm_ctors".equals(command)) {
            Class<?> cls = Class.forName("android.app.ApplicationPackageManager");
            for (java.lang.reflect.Constructor<?> constructor : cls.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                StringBuilder builder = new StringBuilder("PROBE_APPPM_CTOR\t");
                builder.append(cls.getName()).append('(');
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(parameterTypes[i].getName());
                }
                builder.append(')');
                System.out.println(builder);
            }
            return;
        }

        if ("probe_apppm_ctor".equals(command)) {
            PackageManager packageManager = createApplicationPackageManagerWithoutContext();
            System.out.println("PROBE_APPPM_CTOR_OK\t" + packageManager.getClass().getName());
            return;
        }

        if ("probe_apppm_apps".equals(command)) {
            PackageManager packageManager = createApplicationPackageManagerWithoutContext();
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(0);
            System.out.println("PROBE_APPPM_APPS_OK\t" + installedApps.size());
            return;
        }

        if ("probe_apppm_appinfo".equals(command)) {
            if (args.length < 2) {
                System.err.println("usage: probe_apppm_appinfo <packageName>");
                System.exit(2);
                return;
            }
            PackageManager packageManager = createApplicationPackageManagerWithoutContext();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(args[1], 0);
            System.out.println("PROBE_APPPM_APPINFO_OK\t" + applicationInfo.packageName);
            return;
        }

        Context context = obtainSystemContext();
        if (context == null) {
            System.err.println("unable to obtain system context");
            System.exit(3);
            return;
        }

        if ("probe_systemcontext".equals(command)) {
            System.out.println("PROBE_SYSTEMCONTEXT_OK\t" + context.getClass().getName());
            return;
        }

        if ("context".equals(command)) {
            System.out.println("CONTEXT_OK\t" + context.getClass().getName());
            return;
        }

        if ("probe_getpm".equals(command)) {
            PackageManager packageManager = context.getPackageManager();
            System.out.println("PROBE_GETPM_OK\t" + packageManager.getClass().getName());
            return;
        }

        if ("pm".equals(command)) {
            PackageManager packageManager = context.getPackageManager();
            System.out.println("PM_OK\t" + packageManager.getClass().getName());
            return;
        }

        if ("probe_apps".equals(command)) {
            PackageManager packageManager = context.getPackageManager();
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(0);
            System.out.println("PROBE_APPS_OK\t" + installedApps.size());
            return;
        }

        if ("apps".equals(command)) {
            PackageManager packageManager = context.getPackageManager();
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(0);
            System.out.println("APPS_OK\t" + installedApps.size());
            return;
        }

        if ("probe_appinfo".equals(command)) {
            if (args.length < 2) {
                System.err.println("usage: probe_appinfo <packageName>");
                System.exit(2);
                return;
            }
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(args[1], 0);
            System.out.println("PROBE_APPINFO_OK\t" + applicationInfo.packageName);
            return;
        }

        if ("iconprobe".equals(command)) {
            if (args.length < 2) {
                System.err.println("usage: iconprobe <packageName>");
                System.exit(2);
                return;
            }
            handleIconProbeCommand(context, args[1]);
            return;
        }

        if ("list".equals(command)) {
            handleListCommand(context, args);
            return;
        }

        if ("icons".equals(command)) {
            if (args.length < 2) {
                System.err.println("usage: icons <requestBase64>");
                System.exit(2);
                return;
            }
            handleIconsCommand(context, args[1]);
            return;
        }

        if (!"icon".equals(command) || args.length < 2) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        String packageName = args[1];
        String localHash = args.length >= 3 ? args[2] : "";

        PackageManager packageManager = context.getPackageManager();
        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        String label = String.valueOf(packageManager.getApplicationLabel(applicationInfo));
        Drawable icon = loadIcon(packageName, context, packageManager, applicationInfo);
        Bitmap bitmap = drawableToBitmap(icon);
        String argbHash = hashArgb(bitmap);

        String labelBase64 =
                Base64.encodeToString(label.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        if (!localHash.isEmpty() && localHash.equals(argbHash)) {
            System.out.println("UNCHANGED");
            System.out.println(argbHash);
            System.out.println(labelBase64);
            return;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        compressBitmap(bitmap, output);

        String iconBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);

        System.out.println("CHANGED");
        System.out.println(argbHash);
        System.out.println(labelBase64);
        System.out.println(iconBase64);
    }

    private static void handleListCommand(Context context, String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: list <offset> <limit> <includeSystem>");
            System.exit(2);
            return;
        }

        int offset = Integer.parseInt(args[1]);
        int limit = Integer.parseInt(args[2]);
        boolean includeSystem = "1".equals(args[3]) || Boolean.parseBoolean(args[3]);

        PackageManager packageManager = context.getPackageManager();
        List<ApplicationInfo> installedApps = new ArrayList<>(packageManager.getInstalledApplications(0));
        Collections.sort(
                installedApps,
                new Comparator<ApplicationInfo>() {
                    @Override
                    public int compare(ApplicationInfo left, ApplicationInfo right) {
                        int systemCompare = Boolean.compare(isSystemApp(left), isSystemApp(right));
                        if (systemCompare != 0) {
                            return systemCompare;
                        }

                        int enabledCompare = Boolean.compare(!left.enabled, !right.enabled);
                        if (enabledCompare != 0) {
                            return enabledCompare;
                        }

                        return left.packageName.compareTo(right.packageName);
                    }
                }
        );

        int emitted = 0;
        int skipped = 0;
        for (ApplicationInfo appInfo : installedApps) {
            boolean isSystemApp = isSystemApp(appInfo);
            if (!includeSystem && isSystemApp) {
                continue;
            }

            if (skipped < offset) {
                skipped++;
                continue;
            }

            if (emitted >= limit) {
                break;
            }

            PackageInfo packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0);
            String label = String.valueOf(packageManager.getApplicationLabel(appInfo));
            long versionCode = getLongVersionCode(packageInfo);
            long lastUpdateTime = packageInfo.lastUpdateTime;

            System.out.println(
                    appInfo.packageName + "\t" +
                            base64(label) + "\t" +
                            (appInfo.enabled ? "1" : "0") + "\t" +
                            (isSystemApp ? "1" : "0") + "\t" +
                            base64(appInfo.sourceDir != null ? appInfo.sourceDir : "") + "\t" +
                            versionCode + "\t" +
                            lastUpdateTime
            );
            emitted++;
        }
    }

    private static void handleIconProbeCommand(Context context, String packageName) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        System.out.println("ICON_PROBE_START\t" + packageName);
        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        System.out.println("ICON_PROBE_APPINFO_OK");
        String label = String.valueOf(packageManager.getApplicationLabel(applicationInfo));
        System.out.println("ICON_PROBE_LABEL_OK\t" + label);
        Drawable icon = loadIcon(packageName, context, packageManager, applicationInfo);
        System.out.println("ICON_PROBE_LOADICON_OK\t" + icon.getClass().getName());
        Bitmap bitmap = drawableToBitmap(icon);
        System.out.println("ICON_PROBE_RENDER_OK\t" + bitmap.getWidth() + "x" + bitmap.getHeight());
    }

    private static void handleIconsCommand(Context context, String requestBase64) throws Exception {
        String requestText = new String(Base64.decode(requestBase64, Base64.NO_WRAP), StandardCharsets.UTF_8);
        PackageManager packageManager = context.getPackageManager();
        List<String> requestLines = new ArrayList<>();
        for (String line : requestText.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                requestLines.add(trimmed);
            }
        }

        int threadCount = Math.min(MAX_ICON_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<BatchIconResult>> futures = new ArrayList<>();
        try {
            for (String line : requestLines) {
                futures.add(executor.submit(() -> processBatchIconRequest(context, packageManager, line)));
            }
        } finally {
            executor.shutdown();
        }

        List<BatchIconResult> results = new ArrayList<>();
        for (Future<BatchIconResult> future : futures) {
            try {
                BatchIconResult result = future.get();
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception ignored) {
            }
        }

        List<String> manifestLines = new ArrayList<>();
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(zipBytes)) {
            for (BatchIconResult result : results) {
                if (result.imageBytes != null) {
                    zipOutputStream.putNextEntry(new ZipEntry(result.entryName));
                    zipOutputStream.write(result.imageBytes);
                    zipOutputStream.closeEntry();
                }
                manifestLines.add(
                        result.packageName + "\t" +
                                base64(result.label) + "\t" +
                                result.iconHash + "\t" +
                                result.entryName
                );
            }
        }

        String manifestBase64 =
                manifestLines.isEmpty()
                        ? "-"
                        : Base64.encodeToString(
                                joinLines(manifestLines).getBytes(StandardCharsets.UTF_8),
                                Base64.NO_WRAP
                        );
        boolean hasChangedIcons = hasChangedIcons(results);
        String zipBase64 =
                manifestLines.isEmpty() || !hasChangedIcons
                        ? "-"
                        : Base64.encodeToString(zipBytes.toByteArray(), Base64.NO_WRAP);

        System.out.println("BATCH");
        System.out.println(manifestBase64);
        System.out.println(zipBase64);
    }

    private static BatchIconResult processBatchIconRequest(
            Context context,
            PackageManager packageManager,
            String line
    ) {
        try {
            String[] parts = line.split("\t", 2);
            String packageName = parts[0].trim();
            if (packageName.isEmpty()) {
                return null;
            }

            String localHash = parts.length >= 2 ? parts[1].trim() : "";
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            String label = String.valueOf(packageManager.getApplicationLabel(applicationInfo));
            Drawable icon = loadIcon(packageName, context, packageManager, applicationInfo);
            if (icon == null) {
                return null;
            }

            Bitmap bitmap = drawableToBitmap(icon);
            String iconHash = hashArgb(bitmap);
            if (!localHash.isEmpty() && localHash.equals(iconHash)) {
                return new BatchIconResult(packageName, label, iconHash, "-", null);
            }

            ByteArrayOutputStream iconOutput = new ByteArrayOutputStream();
            compressBitmap(bitmap, iconOutput);
            String entryName = sanitizeEntryName(packageName) + ".webp";
            return new BatchIconResult(packageName, label, iconHash, entryName, iconOutput.toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String joinLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    private static boolean hasChangedIcons(List<BatchIconResult> results) {
        for (BatchIconResult result : results) {
            if (result.imageBytes != null) {
                return true;
            }
        }
        return false;
    }

    private static Context obtainSystemContext() throws Exception {
        prepareMainLooperIfNeeded();
        Object activityThread = resolveActivityThread();
        if (activityThread == null) {
            return null;
        }
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        return (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
    }

    private static void prepareMainLooperIfNeeded() {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
    }

    private static Object resolveActivityThread() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread;
        try {
            activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        } catch (Throwable ignored) {
            activityThread = null;
        }
        if (activityThread == null) {
            prepareMainLooperIfNeeded();
            activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        }
        return activityThread;
    }

    private static Object getAppGlobalsPackageManager() throws Exception {
        Class<?> appGlobalsClass = Class.forName("android.app.AppGlobals");
        return appGlobalsClass.getMethod("getPackageManager").invoke(null);
    }

    private static PackageManager createApplicationPackageManagerWithoutContext() throws Exception {
        Object ipm = getAppGlobalsPackageManager();
        if (ipm == null) {
            throw new IllegalStateException("AppGlobals.getPackageManager returned null");
        }

        Class<?> cls = Class.forName("android.app.ApplicationPackageManager");
        for (java.lang.reflect.Constructor<?> constructor : cls.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 2
                    && "android.content.pm.IPackageManager".equals(parameterTypes[1].getName())) {
                constructor.setAccessible(true);
                Object instance = constructor.newInstance(null, ipm);
                return (PackageManager) instance;
            }
        }

        throw new NoSuchMethodException("No ApplicationPackageManager constructor with IPackageManager found");
    }

    private static Drawable loadIcon(
            String packageName,
            Context context,
            PackageManager packageManager,
            ApplicationInfo applicationInfo
    ) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
                Context packageContext = context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY);
                int[] densities = {320, 240, 213};
                for (int density : densities) {
                    try {
                        Drawable drawable =
                                packageContext.getResources().getDrawableForDensity(
                                        packageInfo.applicationInfo.icon,
                                        density,
                                        null
                                );
                        if (drawable != null) {
                            return drawable;
                        }
                    } catch (Resources.NotFoundException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return applicationInfo.loadIcon(packageManager);
    }

    private static String sanitizeEntryName(String packageName) {
        return packageName.replace(':', '_');
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private static boolean isSystemApp(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    @SuppressWarnings("deprecation")
    private static long getLongVersionCode(PackageInfo packageInfo) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

    private static String base64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String hashArgb(Bitmap bitmap) throws Exception {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * 4);
        for (int pixel : pixels) {
            buffer.putInt(pixel);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(buffer.array());
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    @SuppressWarnings("deprecation")
    private static void compressBitmap(Bitmap bitmap, ByteArrayOutputStream output) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output);
        } else {
            bitmap.compress(Bitmap.CompressFormat.WEBP, 100, output);
        }
    }
}
