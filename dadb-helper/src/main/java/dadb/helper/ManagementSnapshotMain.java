package dadb.helper;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

public final class ManagementSnapshotMain {
    private static final String PROTOCOL_VERSION = "1";
    private static final int MAX_PROCESS_TEXT_BYTES = 64 * 1024;

    private ManagementSnapshotMain() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2 || !PROTOCOL_VERSION.equals(args[0])) {
                throw new IllegalArgumentException("Unsupported management helper protocol");
            }
            switch (args[1]) {
                case "files":
                    if (args.length != 3) {
                        throw new IllegalArgumentException("files requires one path argument");
                    }
                    writeFiles(decode(args[2]));
                    break;
                case "processes":
                    if (args.length != 2) {
                        throw new IllegalArgumentException("processes does not accept arguments");
                    }
                    writeProcesses();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown management helper command: " + args[1]);
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

        System.out.println("DADB_MANAGEMENT\t" + PROTOCOL_VERSION + "\tFILES");
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

        System.out.println("DADB_MANAGEMENT\t" + PROTOCOL_VERSION + "\tPROCESSES");
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
}
