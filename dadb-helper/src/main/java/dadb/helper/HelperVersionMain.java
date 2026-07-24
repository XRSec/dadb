package dadb.helper;

public final class HelperVersionMain {
    private static final String VERSION = "1.4.2";
    private static final String READY_MARKER = "DADB_HELPER_READY";
    private static final String VERSION_MISMATCH_MARKER = "DADB_HELPER_VERSION_MISMATCH";

    private HelperVersionMain() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && VERSION.equals(args[0])) {
            System.out.println(READY_MARKER);
        } else {
            System.out.println(VERSION_MISMATCH_MARKER);
        }
    }
}
