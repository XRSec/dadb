package dadb.helper;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.AttributionSource;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@SuppressLint("PrivateApi")
public final class AppIconExportMain {
    private static final String HELPER_VERSION = "1.3.1";
    private static final String VERSION_MISMATCH_MARKER = "DADB_HELPER_VERSION_MISMATCH";
    private static final int ICON_SIZE = 48;
    private static final int MAX_ICON_THREADS = 4;
    private static final String MISSING_APP_FIELD = "M";
    private static final String USAGE =
            "usage: ping | runtime | context | pm | apps | " +
                    "probe_classload | probe_current | probe_systemmain | probe_systemcontext | " +
                    "probe_pmservice | probe_appglobals_pm | probe_apppm_ctors | probe_apppm_ctor | " +
                    "probe_apppm_apps | probe_apppm_appinfo <packageName> | " +
                    "probe_getpm | probe_apps | probe_appinfo <packageName> | " +
                    "iconprobe <packageName> | list <offset> <limit> <includeSystem> | " +
                    "appdata <includeUser> <includeSystem> <includeEnabled> <includeDisabled> <fields> <packagesBase64> | " +
                    "queries <requestBase64> | " +
                    "input_text <textBase64> | " +
                    "icons <requestBase64> | " +
                    "icon <packageName> [localHash]";

    private AppIconExportMain() {}

    private record BatchIconResult(String packageName, String label, String iconHash,
                                   String entryName, byte[] imageBytes) {
    }

    private record BatchQueryResult(String key, String value, String error) {
    }

    private record PmPackageInfo(String packageName, String sourceDir) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        if ("invoke".equals(args[0])) {
            if (args.length < 3) {
                System.err.println("usage: invoke <version> <command> [args]");
                System.exit(2);
                return;
            }
            if (!HELPER_VERSION.equals(args[1])) {
                System.out.println(VERSION_MISMATCH_MARKER);
                return;
            }
            args = Arrays.copyOfRange(args, 2, args.length);
        }

        String command = args[0];
        if ("ping".equals(command)) {
            System.out.println("PING_OK");
            return;
        }

        if ("runtime".equals(command)) {
            resolveActivityThread();
            System.out.println("RUNTIME_OK");
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

        if ("queries".equals(command)) {
            if (args.length < 2) {
                System.err.println("usage: queries <requestBase64>");
                System.exit(2);
                return;
            }
            handleQueriesCommand(args[1]);
            return;
        }

        Context context;
        Throwable contextError = null;
        try {
            context = obtainSystemContext();
        } catch (Throwable error) {
            context = null;
            contextError = error;
        }
        if ("appdata".equals(command)) {
            handleAppDataCommand(context, args);
            return;
        }
        if (context == null && requiresSystemContext(command)) {
            System.err.println(
                    "unable to obtain system context" +
                            (contextError != null ? ": " + describeError(contextError) : "")
            );
            System.exit(3);
            return;
        }

        if ("probe_systemcontext".equals(command)) {
            Context systemContext = Objects.requireNonNull(context, "System context is required");
            System.out.println("PROBE_SYSTEMCONTEXT_OK\t" + systemContext.getClass().getName());
            return;
        }

        if ("context".equals(command)) {
            Context systemContext = Objects.requireNonNull(context, "System context is required");
            System.out.println("CONTEXT_OK\t" + systemContext.getClass().getName());
            return;
        }

        if ("input_text".equals(command)) {
            if (args.length < 2) {
                System.err.println("usage: input_text <textBase64>");
                System.exit(2);
                return;
            }
            handleInputTextCommand(
                    Objects.requireNonNull(context, "System context is required"),
                    args[1]
            );
            return;
        }

        if ("probe_getpm".equals(command)) {
            PackageManager packageManager = getPackageManager(context);
            System.out.println("PROBE_GETPM_OK\t" + packageManager.getClass().getName());
            return;
        }

        if ("pm".equals(command)) {
            PackageManager packageManager = getPackageManager(context);
            System.out.println("PM_OK\t" + packageManager.getClass().getName());
            return;
        }

        if ("probe_apps".equals(command)) {
            PackageManager packageManager = getPackageManager(context);
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(0);
            System.out.println("PROBE_APPS_OK\t" + installedApps.size());
            return;
        }

        if ("apps".equals(command)) {
            PackageManager packageManager = getPackageManager(context);
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
            PackageManager packageManager = getPackageManager(context);
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
            handleIconProbeCommand(getPackageManager(context), args[1]);
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
            handleIconsCommand(getPackageManager(context), args[1]);
            return;
        }

        if (!"icon".equals(command) || args.length < 2) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        String packageName = args[1];
        String localHash = args.length >= 3 ? args[2] : "";

        PackageManager packageManager = getPackageManager(context);
        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        String label = String.valueOf(packageManager.getApplicationLabel(applicationInfo));
        Bitmap bitmap = loadIconBitmap(packageManager, applicationInfo);
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

    private static void handleListCommand(Context context, String[] args) {
        if (args.length < 4) {
            System.err.println("usage: list <offset> <limit> <includeSystem>");
            System.exit(2);
            return;
        }

        int offset = Integer.parseInt(args[1]);
        int limit = Integer.parseInt(args[2]);
        boolean includeSystem = "1".equals(args[3]) || Boolean.parseBoolean(args[3]);

        if (context == null) {
            handleListCommandWithoutContext(offset, limit, includeSystem);
            return;
        }

        try {
            PackageManager packageManager = context.getPackageManager();
            List<ApplicationInfo> installedApps = new ArrayList<>(packageManager.getInstalledApplications(0));
            installedApps.sort((left, right) -> {
                int systemCompare = Boolean.compare(isSystemApp(left), isSystemApp(right));
                if (systemCompare != 0) {
                    return systemCompare;
                }

                int enabledCompare = Boolean.compare(!left.enabled, !right.enabled);
                if (enabledCompare != 0) {
                    return enabledCompare;
                }

                return left.packageName.compareTo(right.packageName);
            });

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
            return;
        } catch (Throwable ignored) {
            handleListCommandWithoutContext(offset, limit, includeSystem);
            return;
        }
    }

    private static void handleListCommandWithoutContext(int offset, int limit, boolean includeSystem) {
        List<PmPackageInfo> packages = listPackagesViaPm(
                buildPmListPackagesCommand(
                        true,
                        includeSystem,
                        true,
                        true
                )
        );
        if (packages.isEmpty()) {
            return;
        }

        Set<String> enabledPackages = queryPackagesAsSet(
                buildPmListPackagesCommand(
                        true,
                        includeSystem,
                        true,
                        false
                )
        );
        Set<String> disabledPackages = queryPackagesAsSet(
                buildPmListPackagesCommand(
                        true,
                        includeSystem,
                        false,
                        true
                )
        );
        boolean hasEnabledData = !enabledPackages.isEmpty() || !disabledPackages.isEmpty();

        packages.sort((left, right) -> {
            boolean leftSystem = isSystemPath(left.sourceDir);
            boolean rightSystem = isSystemPath(right.sourceDir);
            int systemCompare = Boolean.compare(leftSystem, rightSystem);
            if (systemCompare != 0) {
                return systemCompare;
            }

            boolean leftEnabled = inferEnabledState(left.packageName, hasEnabledData, enabledPackages, disabledPackages);
            boolean rightEnabled = inferEnabledState(right.packageName, hasEnabledData, enabledPackages, disabledPackages);
            int enabledCompare = Boolean.compare(!leftEnabled, !rightEnabled);
            if (enabledCompare != 0) {
                return enabledCompare;
            }

            return left.packageName.compareTo(right.packageName);
        });

        PackageManager packageManager = null;
        try {
            packageManager = createApplicationPackageManagerWithoutContext();
        } catch (Exception ignored) {
        }

        int emitted = 0;
        int skipped = 0;
        for (PmPackageInfo packageInfoRecord : packages) {
            if (skipped < offset) {
                skipped++;
                continue;
            }
            if (emitted >= limit) {
                break;
            }

            String packageName = packageInfoRecord.packageName;
            String sourceDir = packageInfoRecord.sourceDir != null ? packageInfoRecord.sourceDir : "";
            boolean systemApp = isSystemPath(sourceDir);
            boolean enabled = inferEnabledState(packageName, hasEnabledData, enabledPackages, disabledPackages);
            String label = packageManager != null
                    ? resolvePackageLabel(packageManager, packageName)
                    : packageName;
            System.out.println(
                    packageName + "\t" +
                            base64(label) + "\t" +
                            (enabled ? "1" : "0") + "\t" +
                            (systemApp ? "1" : "0") + "\t" +
                            base64(sourceDir) + "\t" +
                            0 + "\t" +
                            0
            );
            emitted++;
        }
    }

    private static boolean inferEnabledState(
            String packageName,
            boolean hasEnabledData,
            Set<String> enabledPackages,
            Set<String> disabledPackages
    ) {
        if (!hasEnabledData) {
            return true;
        }
        if (!enabledPackages.isEmpty()) {
            return enabledPackages.contains(packageName);
        }
        return !disabledPackages.contains(packageName);
    }

    private static String resolvePackageLabel(PackageManager packageManager, String packageName) {
        if (packageManager == null || packageName == null || packageName.isEmpty()) {
            return packageName;
        }
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            return String.valueOf(packageManager.getApplicationLabel(applicationInfo));
        } catch (Exception error) {
            return packageName;
        }
    }

    private static void handleAppDataCommand(Context context, String[] args) {
        if (args.length < 7) {
            System.err.println(
                    "usage: appdata <includeUser> <includeSystem> <includeEnabled> <includeDisabled> <fields> <packagesBase64>"
            );
            System.exit(2);
            return;
        }

        boolean includeUser = parseFlag(args[1]);
        boolean includeSystem = parseFlag(args[2]);
        boolean includeEnabled = parseFlag(args[3]);
        boolean includeDisabled = parseFlag(args[4]);
        boolean includeDetails = containsField(args[5]);
        Set<String> requestedPackages = decodePackageNames(args[6]);
        if (context == null) {
            handleAppDataCommandWithoutContext(
                    includeUser,
                    includeSystem,
                    includeEnabled,
                    includeDisabled,
                    includeDetails,
                    requestedPackages
            );
            return;
        }
        try {
            PackageManager packageManager = context.getPackageManager();
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(0);
            for (ApplicationInfo appInfo : installedApps) {
                if (!requestedPackages.isEmpty() && !requestedPackages.contains(appInfo.packageName)) {
                    continue;
                }
                boolean systemApp = isSystemApp(appInfo);
                if ((systemApp && !includeSystem) || (!systemApp && !includeUser)) {
                    continue;
                }
                if ((appInfo.enabled && !includeEnabled) || (!appInfo.enabled && !includeDisabled)) {
                    continue;
                }

                PackageInfo packageInfo = null;
                String packageInfoError = null;
                if (includeDetails) {
                    try {
                        packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0);
                    } catch (Exception error) {
                        packageInfoError = describeError(error);
                    }
                }
                String sourceDir = appInfo.sourceDir;
                String apkSizeField = MISSING_APP_FIELD;
                if (includeDetails && sourceDir != null && !sourceDir.isEmpty()) {
                    try {
                        apkSizeField = valueAppNumberField(new File(sourceDir).length());
                    } catch (Exception error) {
                        apkSizeField = errorAppField(error);
                    }
                }
                String labelField;
                try {
                    labelField = valueAppTextField(String.valueOf(packageManager.getApplicationLabel(appInfo)));
                } catch (Exception error) {
                    labelField = errorAppField(error);
                }
                String unavailablePackageInfoField = includeDetails
                        ? errorAppField(packageInfoError != null ? packageInfoError : "Package information unavailable")
                        : MISSING_APP_FIELD;
                String versionCodeField = packageInfo != null
                        ? valueAppNumberField(getLongVersionCode(packageInfo))
                        : unavailablePackageInfoField;
                String versionNameField = packageInfo == null
                        ? unavailablePackageInfoField
                        : packageInfo.versionName != null
                        ? valueAppTextField(packageInfo.versionName)
                        : MISSING_APP_FIELD;
                String minSdkField = !includeDetails || Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                        ? MISSING_APP_FIELD
                        : valueAppNumberField(appInfo.minSdkVersion);
                String targetSdkField = includeDetails
                        ? valueAppNumberField(appInfo.targetSdkVersion)
                        : MISSING_APP_FIELD;
                String firstInstallTimeField = packageInfo != null
                        ? valueAppNumberField(packageInfo.firstInstallTime)
                        : unavailablePackageInfoField;
                String lastUpdateTimeField = packageInfo != null
                        ? valueAppNumberField(packageInfo.lastUpdateTime)
                        : unavailablePackageInfoField;

                System.out.println(
                        appInfo.packageName + "\t" +
                                labelField + "\t" +
                                valueAppBooleanField(appInfo.enabled) + "\t" +
                                valueAppBooleanField(systemApp) + "\t" +
                                (sourceDir != null ? valueAppTextField(sourceDir) : MISSING_APP_FIELD) + "\t" +
                                versionCodeField + "\t" +
                                apkSizeField + "\t" +
                                versionNameField + "\t" +
                                minSdkField + "\t" +
                                targetSdkField + "\t" +
                                firstInstallTimeField + "\t" +
                                lastUpdateTimeField
                );
            }
            return;
        } catch (Throwable primaryError) {
            handleAppDataCommandWithoutContext(
                    includeUser,
                    includeSystem,
                    includeEnabled,
                    includeDisabled,
                    includeDetails,
                    requestedPackages
            );
            return;
        }
    }

    private static void handleAppDataCommandWithoutContext(
            boolean includeUser,
            boolean includeSystem,
            boolean includeEnabled,
            boolean includeDisabled,
            boolean includeDetails,
            Set<String> requestedPackages
    ) {
        String query = buildPmListPackagesCommand(includeUser, includeSystem, includeEnabled, includeDisabled);
        List<PmPackageInfo> packages = listPackagesViaPm(query);
        Set<String> disabledPackages = Set.of();
        if (includeEnabled && includeDisabled) {
            String disabledQuery = buildPmListPackagesCommand(includeUser, includeSystem, false, true);
            disabledPackages = queryPackagesAsSet(disabledQuery);
        }

        PackageManager packageManager = null;
        try {
            packageManager = createApplicationPackageManagerWithoutContext();
        } catch (Exception ignored) {
        }

        for (PmPackageInfo packageInfoRecord : packages) {
            if (!requestedPackages.isEmpty() && !requestedPackages.contains(packageInfoRecord.packageName)) {
                continue;
            }
            boolean systemApp = isSystemPath(packageInfoRecord.sourceDir);
            boolean enabled;
            if (includeEnabled != includeDisabled) {
                enabled = includeEnabled;
            } else {
                enabled = !disabledPackages.contains(packageInfoRecord.packageName);
            }
            if (enabled && !includeEnabled) {
                continue;
            }
            if (!enabled && !includeDisabled) {
                continue;
            }
            if (!systemApp && !includeUser) {
                continue;
            }
            if (systemApp && !includeSystem) {
                continue;
            }

            String sourceDir = packageInfoRecord.sourceDir;
            String apkSizeField = MISSING_APP_FIELD;
            if (includeDetails && sourceDir != null && !sourceDir.isEmpty()) {
                try {
                    apkSizeField = valueAppNumberField(new File(sourceDir).length());
                } catch (Exception error) {
                    apkSizeField = errorAppField(error);
                }
            }
            String labelField;
            if (packageManager == null) {
                labelField = MISSING_APP_FIELD;
            } else {
                try {
                    ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageInfoRecord.packageName, 0);
                    labelField = valueAppTextField(String.valueOf(packageManager.getApplicationLabel(applicationInfo)));
                } catch (Exception error) {
                    labelField = errorAppField(error);
                }
            }
            String unavailableDetailField = includeDetails
                    ? errorAppField("System context unavailable")
                    : MISSING_APP_FIELD;

            System.out.println(
                    packageInfoRecord.packageName + "\t" +
                            labelField + "\t" +
                            valueAppBooleanField(enabled) + "\t" +
                            valueAppBooleanField(systemApp) + "\t" +
                            (sourceDir != null ? valueAppTextField(sourceDir) : MISSING_APP_FIELD) + "\t" +
                            unavailableDetailField + "\t" +
                            apkSizeField + "\t" +
                            unavailableDetailField + "\t" +
                            unavailableDetailField + "\t" +
                            unavailableDetailField + "\t" +
                            unavailableDetailField + "\t" +
                            unavailableDetailField
            );
        }
    }

    private static String buildPmListPackagesCommand(
            boolean includeUser,
            boolean includeSystem,
            boolean includeEnabled,
            boolean includeDisabled
    ) {
        StringBuilder command = new StringBuilder("pm list packages --user 0 -f");
        if (!includeUser && includeSystem) {
            command.append(" --system");
        } else if (includeUser && !includeSystem) {
            command.append(" -3");
        }
        if (includeEnabled && !includeDisabled) {
            command.append(" -e");
        } else if (includeDisabled && !includeEnabled) {
            command.append(" -d");
        }
        return command.toString();
    }

    private static Set<String> queryPackagesAsSet(String command) {
        try {
            List<PmPackageInfo> packages = listPackagesViaPm(command);
            Set<String> packageNames = new HashSet<>();
            for (PmPackageInfo packageInfoRecord : packages) {
                packageNames.add(packageInfoRecord.packageName);
            }
            return packageNames;
        } catch (Exception error) {
            return Set.of();
        }
    }

    private static List<PmPackageInfo> listPackagesViaPm(String command) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("pm list packages failed: exit=" + exitCode + " output=" + output);
            }
            List<PmPackageInfo> packages = new ArrayList<>();
            for (String line : output.split("\\n")) {
                PmPackageInfo info = parsePmPackageLine(line);
                if (info != null) {
                    packages.add(info);
                }
            }
            return packages;
        } catch (Exception error) {
            throw new IllegalStateException("pm list packages failed: " + describeError(error), error);
        }
    }

    private static PmPackageInfo parsePmPackageLine(String line) {
        if (!line.startsWith("package:")) {
            return null;
        }
        String payload = line.substring("package:".length());
        int separator = payload.lastIndexOf('=');
        if (separator <= 0) {
            return null;
        }
        String sourceDir = payload.substring(0, separator).trim();
        String packageName = payload.substring(separator + 1).trim();
        if (sourceDir.startsWith("=")) {
            sourceDir = sourceDir.substring(1);
        }
        if (packageName.isEmpty()) {
            return null;
        }
        return new PmPackageInfo(packageName, sourceDir);
    }

    private static void handleQueriesCommand(String requestBase64) {
        String requestText = new String(Base64.decode(requestBase64, Base64.NO_WRAP), StandardCharsets.UTF_8);
        List<String[]> queries = new ArrayList<>();
        for (String line : requestText.split("\n")) {
            String[] parts = line.split("\t", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                String command = new String(Base64.decode(parts[1], Base64.NO_WRAP), StandardCharsets.UTF_8);
                queries.add(new String[]{parts[0].trim(), command});
            }
        }

        int threadCount = Math.min(MAX_ICON_THREADS, Math.max(1, queries.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<BatchQueryResult>> futures = new ArrayList<>();
        try {
            for (String[] query : queries) {
                futures.add(executor.submit(() -> executeQuery(query[0], query[1])));
            }
        } finally {
            executor.shutdown();
        }
        for (Future<BatchQueryResult> future : futures) {
            BatchQueryResult result;
            try {
                result = future.get();
            } catch (Exception error) {
                result = new BatchQueryResult("unknown", "", describeError(error));
            }
            System.out.println(
                    result.key + "\t" + encodedField(result.value) + "\t" + encodedField(result.error)
            );
        }
    }

    private static BatchQueryResult executeQuery(String key, String command) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            return exitCode == 0
                    ? new BatchQueryResult(key, output, "")
                    : new BatchQueryResult(key, "", "exit=" + exitCode + " output=" + output);
        } catch (Exception error) {
            return new BatchQueryResult(key, "", describeError(error));
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String describeError(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException
                && error.getCause() != null) {
            error = error.getCause();
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static String encodedField(String value) {
        return value == null || value.isEmpty() ? "-" : base64(value);
    }

    private static String valueAppTextField(String value) {
        return "V:" + base64(value != null ? value : "");
    }

    private static String valueAppNumberField(long value) {
        return "V:" + value;
    }

    private static String valueAppBooleanField(boolean value) {
        return value ? "V:1" : "V:0";
    }

    private static String errorAppField(Throwable error) {
        return errorAppField(describeError(error));
    }

    private static String errorAppField(String reason) {
        return "E:" + base64(reason != null ? reason : "Unknown error");
    }

    private static boolean parseFlag(String value) {
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    private static boolean isSystemPath(String sourceDir) {
        return sourceDir != null
                && (sourceDir.startsWith("/system/")
                || sourceDir.startsWith("/system_ext/")
                || sourceDir.startsWith("/product/")
                || sourceDir.startsWith("/vendor/")
                || sourceDir.startsWith("/odm/"));
    }

    private static boolean containsField(String fields) {
        for (String candidate : fields.split(",")) {
            if ("details".equals(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> decodePackageNames(String encodedPackages) {
        Set<String> packageNames = new HashSet<>();
        if ("-".equals(encodedPackages)) {
            return packageNames;
        }
        String decoded = new String(Base64.decode(encodedPackages, Base64.NO_WRAP), StandardCharsets.UTF_8);
        for (String packageName : decoded.split("\n")) {
            String trimmed = packageName.trim();
            if (!trimmed.isEmpty()) {
                packageNames.add(trimmed);
            }
        }
        return packageNames;
    }

    private static void handleIconProbeCommand(PackageManager packageManager, String packageName) throws Exception {
        System.out.println("ICON_PROBE_START\t" + packageName);
        System.out.flush();
        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        System.out.println(
                "ICON_PROBE_APPINFO_OK\ticon=0x" + Integer.toHexString(applicationInfo.icon) +
                        " logo=0x" + Integer.toHexString(applicationInfo.logo)
        );
        System.out.flush();
        String label = String.valueOf(packageManager.getApplicationLabel(applicationInfo));
        System.out.println("ICON_PROBE_LABEL_OK\t" + label);
        System.out.flush();
        Bitmap bitmap = loadIconBitmap(packageManager, applicationInfo);
        System.out.println("ICON_PROBE_LOADICON_OK");
        System.out.flush();
        System.out.println("ICON_PROBE_RENDER_OK\t" + bitmap.getWidth() + "x" + bitmap.getHeight());
    }

    private static void handleIconsCommand(PackageManager packageManager, String requestBase64) throws Exception {
        String requestText = new String(Base64.decode(requestBase64, Base64.NO_WRAP), StandardCharsets.UTF_8);
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
                futures.add(executor.submit(() -> processBatchIconRequest(packageManager, line)));
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
            Bitmap bitmap = loadIconBitmap(packageManager, applicationInfo);
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

    private static boolean requiresSystemContext(String command) {
        return "probe_systemcontext".equals(command)
                || "context".equals(command)
                || "input_text".equals(command);
    }

    private static void handleInputTextCommand(Context systemContext, String textBase64)
            throws Exception {
        byte[] textBytes = Base64.decode(textBase64, Base64.DEFAULT);
        String text = new String(textBytes, StandardCharsets.UTF_8);
        Context shellContext = new ShellContext(systemContext);

        ClipboardManager clipboardManager =
                (ClipboardManager) shellContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            throw new IllegalStateException("Clipboard service is unavailable");
        }
        java.lang.reflect.Field contextField =
                ClipboardManager.class.getDeclaredField("mContext");
        contextField.setAccessible(true);
        contextField.set(clipboardManager, shellContext);
        clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text));

        android.hardware.input.InputManager inputManager =
                (android.hardware.input.InputManager) shellContext.getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) {
            throw new IllegalStateException("Input service is unavailable");
        }
        java.lang.reflect.Method injectInputEvent =
                android.hardware.input.InputManager.class.getMethod(
                        "injectInputEvent",
                        InputEvent.class,
                        int.class
                );
        long now = SystemClock.uptimeMillis();
        KeyEvent down =
                new KeyEvent(
                        now,
                        now,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_PASTE,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_KEYBOARD
                );
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        boolean downInjected =
                (Boolean) injectInputEvent.invoke(inputManager, down, 1);
        boolean upInjected =
                (Boolean) injectInputEvent.invoke(inputManager, up, 1);
        if (!downInjected || !upInjected) {
            throw new IllegalStateException("Paste key event injection failed");
        }
        System.out.println("INPUT_TEXT_OK");
    }

    private static final class ShellContext extends ContextWrapper {
        private static final String SHELL_PACKAGE_NAME = "com.android.shell";

        ShellContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return SHELL_PACKAGE_NAME;
        }

        @Override
        public String getOpPackageName() {
            return SHELL_PACKAGE_NAME;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Context createPackageContext(String packageName, int flags) {
            return this;
        }

        @Override
        public AttributionSource getAttributionSource() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return new AttributionSource.Builder(android.os.Process.SHELL_UID)
                        .setPackageName(SHELL_PACKAGE_NAME)
                        .build();
            }
            return super.getAttributionSource();
        }
    }

    private static PackageManager getPackageManager(Context context) throws Exception {
        return context != null ? context.getPackageManager() : createApplicationPackageManagerWithoutContext();
    }

    private static Context obtainSystemContext() throws Exception {
        prepareMainLooperIfNeeded();
        Object activityThread = resolveActivityThread();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method getSystemContext =
                activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context systemContext =
                (Context) getSystemContext.invoke(activityThread);
        initializeCurrentApplication(activityThread, systemContext);
        android.content.res.Configuration deviceConfiguration = resolveDeviceConfiguration();
        if (deviceConfiguration != null) {
            try {
                return systemContext.createConfigurationContext(deviceConfiguration);
            } catch (RuntimeException ignored) {
            }
        }
        return systemContext;
    }

    private static void initializeCurrentApplication(
            Object activityThread,
            Context systemContext
    ) {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Field initialApplicationField =
                    activityThreadClass.getDeclaredField("mInitialApplication");
            initialApplicationField.setAccessible(true);
            if (initialApplicationField.get(activityThread) != null) {
                return;
            }

            Application application = new Application();
            java.lang.reflect.Method attachBaseContext =
                    ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attachBaseContext.setAccessible(true);
            attachBaseContext.invoke(application, systemContext);
            initialApplicationField.set(activityThread, application);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static android.content.res.Configuration resolveDeviceConfiguration() {
        try {
            Class<?> activityManagerClass = Class.forName("android.app.ActivityManager");
            Object activityManager = activityManagerClass.getMethod("getService").invoke(null);
            if (activityManager != null) {
                Class<?> activityManagerInterface = Class.forName("android.app.IActivityManager");
                Object configuration =
                        activityManagerInterface.getMethod("getConfiguration").invoke(activityManager);
                if (configuration instanceof android.content.res.Configuration) {
                    return new android.content.res.Configuration(
                            (android.content.res.Configuration) configuration
                    );
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> activityManagerNativeClass = Class.forName("android.app.ActivityManagerNative");
            Object activityManager = activityManagerNativeClass.getMethod("getDefault").invoke(null);
            if (activityManager != null) {
                Object configuration =
                        activityManager.getClass().getMethod("getConfiguration").invoke(activityManager);
                if (configuration instanceof android.content.res.Configuration) {
                    return new android.content.res.Configuration(
                            (android.content.res.Configuration) configuration
                    );
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static void prepareMainLooperIfNeeded() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        if (Looper.getMainLooper() == null) {
            java.lang.reflect.Field mainLooperField = Looper.class.getDeclaredField("sMainLooper");
            mainLooperField.setAccessible(true);
            mainLooperField.set(null, Looper.myLooper());
        }
    }

    private static Object resolveActivityThread() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object activityThread = constructor.newInstance();

        java.lang.reflect.Field currentActivityThreadField =
                activityThreadClass.getDeclaredField("sCurrentActivityThread");
        currentActivityThreadField.setAccessible(true);
        currentActivityThreadField.set(null, activityThread);

        java.lang.reflect.Field systemThreadField = activityThreadClass.getDeclaredField("mSystemThread");
        systemThreadField.setAccessible(true);
        systemThreadField.setBoolean(activityThread, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Class<?> configurationControllerClass =
                        Class.forName("android.app.ConfigurationController");
                Class<?> activityThreadInternalClass =
                        Class.forName("android.app.ActivityThreadInternal");
                java.lang.reflect.Constructor<?> configurationControllerConstructor =
                        configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass);
                configurationControllerConstructor.setAccessible(true);
                Object configurationController =
                        configurationControllerConstructor.newInstance(activityThread);
                java.lang.reflect.Field configurationControllerField =
                        activityThreadClass.getDeclaredField("mConfigurationController");
                configurationControllerField.setAccessible(true);
                configurationControllerField.set(activityThread, configurationController);
            } catch (Throwable ignored) {
            }
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

    private static Bitmap loadIconBitmap(
            PackageManager packageManager,
            ApplicationInfo applicationInfo
    ) throws Exception {
        if (applicationInfo.icon == 0) {
            throw new Resources.NotFoundException("Application has no icon resource");
        }
        Resources resources = packageManager.getResourcesForApplication(applicationInfo);
        try {
            Drawable drawable = resources.getDrawable(applicationInfo.icon, null);
            if (drawable != null) {
                return drawableToBitmap(drawable);
            }
        } catch (RuntimeException ignored) {
            // Some app_process environments cannot inflate framework drawables. Keep the
            // resource parser below as a fallback for those devices.
        }
        Bitmap bitmap = renderIconResource(resources, applicationInfo.icon, 0);
        if (bitmap == null) {
            throw new Resources.NotFoundException("Unable to render application icon resource");
        }
        return bitmap;
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Bitmap renderIconResource(Resources resources, int resourceId, int depth) throws Exception {
        if (resourceId == 0 || depth > 8) {
            return null;
        }

        TypedValue value = new TypedValue();
        resources.getValueForDensity(resourceId, 120, value, true);
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return solidColorBitmap(value.data);
        }
        if (value.string == null) {
            return null;
        }

        String resourcePath = value.string.toString();
        if (!resourcePath.endsWith(".xml")) {
            try (InputStream input = openResourceAsset(resources.getAssets(), value.assetCookie, resourcePath)) {
                Bitmap decoded = BitmapFactory.decodeStream(input);
                return decoded != null ? scaleBitmap(decoded) : null;
            }
        }

        try (XmlResourceParser parser = resources.getXml(resourceId)) {
            int event;
            while ((event = parser.next()) != XmlResourceParser.END_DOCUMENT) {
                if (event != XmlResourceParser.START_TAG) {
                    continue;
                }
                String root = parser.getName();
                if ("vector".equals(root)) {
                    return renderVector(resources, parser);
                }
                if ("adaptive-icon".equals(root) || "layer-list".equals(root)
                        || "inset".equals(root) || "selector".equals(root)) {
                    return renderLayeredXml(resources, parser, depth + 1);
                }
                int referencedResource = firstReferencedResource(parser);
                if (referencedResource != 0) {
                    return renderIconResource(resources, referencedResource, depth + 1);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static InputStream openResourceAsset(
            AssetManager assetManager,
            int assetCookie,
            String resourcePath
    ) throws Exception {
        java.lang.reflect.Method openNonAsset =
                AssetManager.class.getDeclaredMethod("openNonAsset", int.class, String.class, int.class);
        openNonAsset.setAccessible(true);
        return (InputStream) openNonAsset.invoke(
                assetManager,
                assetCookie,
                resourcePath,
                AssetManager.ACCESS_STREAMING
        );
    }

    private static Bitmap renderLayeredXml(Resources resources, XmlResourceParser parser, int depth) throws Exception {
        List<Bitmap> layers = new ArrayList<>();
        int event;
        while ((event = parser.next()) != XmlResourceParser.END_DOCUMENT) {
            if (event != XmlResourceParser.START_TAG) {
                continue;
            }
            int resourceId = firstReferencedResource(parser);
            if (resourceId == 0) {
                continue;
            }
            Bitmap layer = renderIconResource(resources, resourceId, depth);
            if (layer != null) {
                layers.add(layer);
            }
        }
        if (layers.isEmpty()) {
            return null;
        }
        Bitmap result = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (Bitmap layer : layers) {
            canvas.drawBitmap(layer, 0, 0, paint);
        }
        return result;
    }

    private static Bitmap renderVector(Resources resources, XmlResourceParser parser) throws Exception {
        float viewportWidth = attributeFloat(parser, "viewportWidth", ICON_SIZE);
        float viewportHeight = attributeFloat(parser, "viewportHeight", ICON_SIZE);
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(ICON_SIZE / viewportWidth, ICON_SIZE / viewportHeight);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);

        int event;
        while ((event = parser.next()) != XmlResourceParser.END_DOCUMENT) {
            if (event != XmlResourceParser.START_TAG) {
                continue;
            }
            String tag = parser.getName();
            String pathData = attributeValue(parser, "pathData");
            if (pathData == null || pathData.isEmpty()) {
                continue;
            }
            Path path = createPath(pathData);
            if (path == null) {
                continue;
            }
            if ("clip-path".equals(tag)) {
                canvas.clipPath(path);
                continue;
            }
            if (!"path".equals(tag)) {
                continue;
            }
            paint.setColor(resolveColor(resources, parser));
            paint.setAlpha(Math.round(255f * attributeFloat(parser, "fillAlpha", 1f)));
            canvas.drawPath(path, paint);
        }
        return bitmap;
    }

    private static Path createPath(String pathData) {
        try {
            Class<?> pathParserClass = Class.forName("android.util.PathParser");
            return (Path) pathParserClass
                    .getMethod("createPathFromPathData", String.class)
                    .invoke(null, pathData);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int firstReferencedResource(XmlResourceParser parser) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            int resourceId = parser.getAttributeResourceValue(i, 0);
            if (resourceId != 0) {
                return resourceId;
            }
        }
        return 0;
    }

    private static String attributeValue(XmlResourceParser parser, String name) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (name.equals(parser.getAttributeName(i))) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    private static float attributeFloat(XmlResourceParser parser, String name, float fallback) {
        String value = attributeValue(parser, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.replace("dip", "").replace("dp", ""));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int resolveColor(
            Resources resources,
            XmlResourceParser parser
    ) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (!"fillColor".equals(parser.getAttributeName(i))) {
                continue;
            }
            int resourceId = parser.getAttributeResourceValue(i, 0);
            if (resourceId != 0) {
                TypedValue colorValue = new TypedValue();
                resources.getValue(resourceId, colorValue, true);
                if (colorValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && colorValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return colorValue.data;
                }
            }
            String value = parser.getAttributeValue(i);
            try {
                return Color.parseColor(value);
            } catch (IllegalArgumentException ignored) {
                return Color.TRANSPARENT;
            }
        }
        return Color.TRANSPARENT;
    }

    private static Bitmap solidColorBitmap(int color) {
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static Bitmap scaleBitmap(Bitmap source) {
        if (source.getWidth() == ICON_SIZE && source.getHeight() == ICON_SIZE) {
            return source;
        }
        return Bitmap.createScaledBitmap(source, ICON_SIZE, ICON_SIZE, true);
    }

    private static String sanitizeEntryName(String packageName) {
        return packageName.replace(':', '_');
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
