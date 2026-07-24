package dadb.helper;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ManagementSnapshotMain {
    private static final int MAX_PROCESS_TEXT_BYTES = 64 * 1024;
    private static final int MAX_QUERY_THREADS = 4;
    private static final QuerySpec[] DEVICE_QUERIES = new QuerySpec[]{
            new QuerySpec("model", "getprop ro.product.model"),
            new QuerySpec("manufacturer", "getprop ro.product.manufacturer"),
            new QuerySpec("soc_model", "getprop ro.soc.model"),
            new QuerySpec("android_version", "getprop ro.build.version.release"),
            new QuerySpec("uptime", "cat /proc/uptime"),
            new QuerySpec("baseband", "getprop gsm.version.baseband"),
            new QuerySpec("product_code_name", "getprop ro.product.device"),
            new QuerySpec("security_patch", "getprop ro.build.version.security_patch"),
            new QuerySpec("serial", "getprop ro.serialno"),
            new QuerySpec("resolution", "wm size | grep -m 1 'Override' || wm size | grep -m 1 'Physical'"),
            new QuerySpec("density", "wm density | grep -m 1 'Override' || wm density | grep -m 1 'Physical'"),
            new QuerySpec("display_metrics", "dumpsys display | grep -m 4 -E 'xDpi=|yDpi=|density .* dpi'"),
            new QuerySpec("display_info", "dumpsys display | grep -m 1 'DisplayDeviceInfo{'"),
            new QuerySpec("network_interfaces", "ip -o -4 addr show 2>/dev/null; ifconfig 2>/dev/null"),
            new QuerySpec("default_route", "ip -4 route show default 2>/dev/null; cat /proc/net/route 2>/dev/null"),
            new QuerySpec("mobile_network_type", "getprop gsm.network.type"),
            new QuerySpec("carrier_names", "getprop gsm.operator.alpha"),
            new QuerySpec("signal_strength", "dumpsys telephony.registry | grep -m 1 'mSignalStrength='"),
            new QuerySpec("cell_identity", "dumpsys telephony.registry | grep -m 1 'mCellIdentity=' || true"),
            new QuerySpec("wifi_info", "dumpsys wifi | grep -m 1 'mWifiInfo SSID:'"),
            new QuerySpec("memory", "cat /proc/meminfo | grep -E 'MemTotal|MemAvailable'"),
            new QuerySpec("data_filesystem", "df /data | grep -m 1 '^/data[[:space:]]'"),
            new QuerySpec("battery_cycle", "for path in /sys/class/power_supply/battery/cycle_count " +
                    "/sys/class/power_supply/bq_bms/cycle_count; do if [ -r \"$path\" ]; then " +
                    "value=$(cat \"$path\" 2>/dev/null); if [ -n \"$value\" ]; then echo \"$value\"; break; fi; fi; done"),
            new QuerySpec("battery", "dumpsys battery | grep -E 'level:|status:|health:|voltage:|temperature:|current now:|current average:'"),
            new QuerySpec("voltage_now", "cat /sys/class/power_supply/battery/voltage_now 2>/dev/null || true"),
            new QuerySpec("battery_current_now", "cmd battery get -f current_now 2>/dev/null || true"),
            new QuerySpec("battery_current_average", "cmd battery get -f current_average 2>/dev/null || true"),
            new QuerySpec("sysfs_current", "for path in /sys/class/power_supply/battery/current_now " +
                    "/sys/class/power_supply/battery/current_avg /sys/class/power_supply/bms/current_now " +
                    "/sys/class/power_supply/main/current_now /sys/class/power_supply/battery/constant_charge_current " +
                    "/sys/class/power_supply/usb/current_max; do if [ -r \"$path\" ]; then " +
                    "value=$(cat \"$path\" 2>/dev/null); if [ -n \"$value\" ]; then echo \"$value\"; break; fi; fi; done"),
            new QuerySpec("abi", "getprop ro.product.cpu.abilist"),
            new QuerySpec("board", "getprop ro.product.board"),
            new QuerySpec("fingerprint", "getprop ro.build.fingerprint"),
            new QuerySpec("wireless_port", "getprop service.adb.tcp.port"),
            new QuerySpec("cpu_count", "cat /proc/cpuinfo | grep -c processor"),
            new QuerySpec("cpu_max_frequency", "cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null || true"),
    };

    private ManagementSnapshotMain() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                throw new IllegalArgumentException("Management helper command is required");
            }
            switch (args[0]) {
                case "files":
                    if (args.length != 2) {
                        throw new IllegalArgumentException("files requires one path argument");
                    }
                    writeFiles(decode(args[1]));
                    break;
                case "processes":
                    if (args.length != 1) {
                        throw new IllegalArgumentException("processes does not accept arguments");
                    }
                    writeProcesses();
                    break;
                case "device":
                    if (args.length != 1) {
                        throw new IllegalArgumentException("device does not accept arguments");
                    }
                    writeDeviceSnapshot();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown management helper command: " + args[0]);
            }
        } catch (Throwable error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            System.out.println("ERROR\t" + encode(message));
        }
    }

    private static void writeFiles(String path) throws Exception {
        File directory = new File(path);
        if (!directory.isDirectory()) {
            throw new IOException("Remote path is not a directory: " + path);
        }
        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Unable to enumerate remote directory: " + path);
        }
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });

        System.out.println("DADB_MANAGEMENT\tFILES");
        for (File child : children) {
            StructStat stat;
            try {
                stat = Os.lstat(child.getAbsolutePath());
            } catch (Exception ignored) {
                continue;
            }
            String kind;
            if (OsConstants.S_ISDIR(stat.st_mode)) {
                kind = "D";
            } else if (OsConstants.S_ISREG(stat.st_mode)) {
                kind = "F";
            } else if (OsConstants.S_ISLNK(stat.st_mode)) {
                kind = "L";
            } else {
                kind = "O";
            }
            System.out.println(
                    "F\t"
                            + encode(child.getName())
                            + "\t"
                            + kind
                            + "\t"
                            + stat.st_size
                            + "\t"
                            + (stat.st_mtime * 1000L)
            );
        }
    }

    private static void writeProcesses() throws IOException {
        File[] procEntries = new File("/proc").listFiles();
        if (procEntries == null) {
            throw new IOException("Unable to enumerate /proc");
        }
        Arrays.sort(procEntries, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Integer.compare(numericName(left), numericName(right));
            }
        });

        System.out.println("DADB_MANAGEMENT\tPROCESSES");
        for (File procEntry : procEntries) {
            int pid = numericName(procEntry);
            if (pid < 0 || !procEntry.isDirectory()) {
                continue;
            }
            try {
                ProcessDetails details = readProcessDetails(procEntry);
                if (!details.name.isEmpty()) {
                    System.out.println(
                            "P\t"
                                    + pid
                                    + "\t"
                                    + details.rssBytes
                                    + "\t"
                                    + encode(details.name)
                    );
                }
            } catch (Exception ignored) {
                // Processes may disappear or become unreadable while /proc is being enumerated.
            }
        }
    }

    private static void writeDeviceSnapshot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_QUERY_THREADS, DEVICE_QUERIES.length));
        List<Future<QueryResult>> futures = new java.util.ArrayList<>();
        try {
            for (QuerySpec query : DEVICE_QUERIES) {
                futures.add(executor.submit(() -> executeQuery(query)));
            }
        } finally {
            executor.shutdown();
        }

        System.out.println("DADB_MANAGEMENT\tDEVICE");
        for (Future<QueryResult> future : futures) {
            QueryResult result = future.get();
            System.out.println(
                    "D\t" + result.key + "\t" + encodeOptional(result.value) + "\t" + encodeOptional(result.error)
            );
        }
    }

    private static QueryResult executeQuery(QuerySpec query) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", query.command)
                    .redirectErrorStream(true)
                    .start();
            String output = readProcessOutput(process).trim();
            int exitCode = process.waitFor();
            return exitCode == 0
                    ? new QueryResult(query.key, output, "")
                    : new QueryResult(query.key, "", "exit=" + exitCode + " output=" + output);
        } catch (Exception error) {
            String message = error.getMessage();
            return new QueryResult(
                    query.key,
                    "",
                    error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message)
            );
        }
    }

    private static String readProcessOutput(Process process) throws IOException {
        try (InputStream input = process.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ProcessDetails readProcessDetails(File procEntry) throws IOException {
        String status = readText(new File(procEntry, "status"));
        String statusName = "";
        long rssBytes = 0L;
        for (String line : status.split("\n")) {
            if (line.startsWith("Name:")) {
                statusName = line.substring("Name:".length()).trim();
            } else if (line.startsWith("VmRSS:")) {
                String value = line.substring("VmRSS:".length()).trim().split("\\s+")[0];
                rssBytes = Long.parseLong(value) * 1024L;
            }
        }

        String commandLine = readText(new File(procEntry, "cmdline"));
        int separator = commandLine.indexOf('\0');
        String name = (separator >= 0 ? commandLine.substring(0, separator) : commandLine).trim();
        if (name.isEmpty()) {
            name = statusName;
        }
        return new ProcessDetails(name, rssBytes);
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int remaining = MAX_PROCESS_TEXT_BYTES;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int numericName(File file) {
        try {
            return Integer.parseInt(file.getName());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String encodeOptional(String value) {
        return value == null || value.isEmpty() ? "-" : encode(value);
    }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static final class ProcessDetails {
        final String name;
        final long rssBytes;

        ProcessDetails(String name, long rssBytes) {
            this.name = name;
            this.rssBytes = rssBytes;
        }
    }

    private static final class QuerySpec {
        final String key;
        final String command;

        QuerySpec(String key, String command) {
            this.key = key;
            this.command = command;
        }
    }

    private static final class QueryResult {
        final String key;
        final String value;
        final String error;

        QueryResult(String key, String value, String error) {
            this.key = key;
            this.value = value;
            this.error = error;
        }
    }
}
