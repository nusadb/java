package com.nusadb.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks the server of a multi-host URL ({@code jdbc:nusadb://h1:p1,h2:p2/db}): hosts are tried in
 * order (shuffled with {@code loadBalanceHosts}), a host that cannot be reached is skipped, and with
 * {@code targetServerType} a host whose role does not fit is skipped too. What was learned about each
 * host is remembered for {@code hostRecheckSeconds}, so later connections try the likely hosts first.
 */
final class HostSelector {

    /** {@code targetServerType}. */
    enum Target {
        ANY, PRIMARY, SECONDARY, PREFER_PRIMARY, PREFER_SECONDARY;

        static Target parse(String value) throws SQLException {
            if (value == null || value.isEmpty()) {
                return ANY;
            }
            switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "any":
                    return ANY;
                case "primary":
                case "master":
                    return PRIMARY;
                case "secondary":
                case "standby":
                case "slave":
                    return SECONDARY;
                case "preferprimary":
                    return PREFER_PRIMARY;
                case "prefersecondary":
                case "preferstandby":
                case "preferslave":
                    return PREFER_SECONDARY;
                default:
                    throw new SQLException("nusadb: targetServerType must be any, primary, secondary,"
                            + " preferPrimary or preferSecondary: " + value, "08001");
            }
        }

        /** The role a host must have in the strict pass, or null when any host will do. */
        Boolean standby() {
            switch (this) {
                case PRIMARY:
                case PREFER_PRIMARY:
                    return Boolean.FALSE;
                case SECONDARY:
                case PREFER_SECONDARY:
                    return Boolean.TRUE;
                default:
                    return null;
            }
        }

        boolean fallsBack() {
            return this == PREFER_PRIMARY || this == PREFER_SECONDARY;
        }
    }

    /** One {@code host:port} of the URL. */
    record Host(String name, int port) {
        @Override
        public String toString() {
            return (name.indexOf(':') >= 0 ? "[" + name + "]" : name) + ":" + port;
        }
    }

    private enum Status { DOWN, PRIMARY, STANDBY }

    private record Known(Status status, long at) {}

    /** What connections from this JVM have learned about each host. */
    private static final Map<Host, Known> KNOWN = new ConcurrentHashMap<>();

    private HostSelector() {}

    /** Parses {@code h1[:p1],h2[:p2],...}; an IPv6 address is written in brackets. */
    static List<Host> parse(String authority, String defaultHost, int defaultPort) throws SQLException {
        List<Host> hosts = new ArrayList<>();
        for (String part : authority.split(",", -1)) {
            part = part.trim();
            if (part.isEmpty()) {
                if (authority.isEmpty()) {
                    break;
                }
                throw new SQLException("nusadb: empty host in URL", "08001");
            }
            String name = part;
            String port = null;
            if (part.startsWith("[")) {
                int end = part.indexOf(']');
                if (end < 0) {
                    throw new SQLException("nusadb: unclosed [ in URL host " + part, "08001");
                }
                name = part.substring(1, end);
                if (end + 1 < part.length()) {
                    if (part.charAt(end + 1) != ':') {
                        throw new SQLException("nusadb: invalid URL host " + part, "08001");
                    }
                    port = part.substring(end + 2);
                }
            } else {
                int colon = part.lastIndexOf(':');
                if (colon >= 0) {
                    name = part.substring(0, colon);
                    port = part.substring(colon + 1);
                }
            }
            int p = defaultPort;
            if (port != null) {
                try {
                    p = Integer.parseInt(port);
                } catch (NumberFormatException e) {
                    throw new SQLException("nusadb: invalid port in URL", "08001", e);
                }
            }
            hosts.add(new Host(name.isEmpty() ? defaultHost : name, p));
        }
        if (hosts.isEmpty()) {
            hosts.add(new Host(defaultHost, defaultPort));
        }
        return hosts;
    }

    /** Opens a connection to the first host that answers and has the role {@code target} asks for. */
    static Wire connect(List<Host> hosts, Wire.Options base, Target target, boolean loadBalance,
            int recheckSeconds) throws SQLException {
        if (hosts.size() == 1 && target == Target.ANY) {
            Host h = hosts.get(0);
            return Wire.connect(base.at(h.name(), h.port()));
        }
        List<Host> order = order(hosts, target.standby(), loadBalance, recheckSeconds * 1_000_000_000L);
        List<String> failures = new ArrayList<>();
        SQLException last = null;
        Wire fallback = null;
        for (Host h : order) {
            Wire wire;
            try {
                wire = Wire.connect(base.at(h.name(), h.port()));
            } catch (SQLException e) {
                if (!unreachable(e)) {
                    closeQuietly(fallback);
                    throw e;
                }
                KNOWN.put(h, new Known(Status.DOWN, System.nanoTime()));
                failures.add(h + " (" + e.getMessage() + ")");
                if (last != null) {
                    e.addSuppressed(last);
                }
                last = e;
                continue;
            }
            Boolean wanted = target.standby();
            if (wanted == null) {
                return wire;
            }
            boolean standby;
            try {
                standby = isStandby(wire);
            } catch (SQLException e) {
                wire.close();
                closeQuietly(fallback);
                throw e;
            }
            KNOWN.put(h, new Known(standby ? Status.STANDBY : Status.PRIMARY, System.nanoTime()));
            if (standby == wanted) {
                closeQuietly(fallback);
                return wire;
            }
            failures.add(h + " (" + (standby ? "a standby" : "a primary") + ")");
            if (target.fallsBack() && fallback == null) {
                fallback = wire;
            } else {
                wire.close();
            }
        }
        if (fallback != null) {
            return fallback;
        }
        if (hosts.size() == 1 && last != null) {
            throw last;
        }
        String what = target == Target.ANY ? "could not connect to any host"
                : "no host is a " + (target.standby() ? "standby" : "primary") + " server";
        throw new SQLException("nusadb: " + what + ": " + String.join(", ", failures), "08001", last);
    }

    /** A failure that another host may not have: the host is down, unreachable or too slow. */
    private static boolean unreachable(SQLException e) {
        String state = e.getSQLState();
        return e instanceof SQLTimeoutException || state == null || state.startsWith("08")
                || "57P03".equals(state) || "53300".equals(state);
    }

    /**
     * The hosts in the order to try them: hosts not known to be down or of the wrong role come
     * first, each group in URL order or shuffled.
     */
    private static List<Host> order(List<Host> hosts, Boolean standby, boolean loadBalance, long recheckNanos) {
        List<Host> order = new ArrayList<>(hosts);
        if (loadBalance) {
            Collections.shuffle(order);
        }
        long now = System.nanoTime();
        order.sort(Comparator.comparingInt(h -> {
            Known k = KNOWN.get(h);
            if (k == null || now - k.at() > recheckNanos) {
                return 0;
            }
            if (k.status() == Status.DOWN) {
                return 2;
            }
            return standby == null || (k.status() == Status.STANDBY) == standby ? 0 : 1;
        }));
        return order;
    }

    /**
     * Whether the server is a read-only standby. The server is asked for {@code transaction_read_only};
     * when it does not report one, a temporary table is created in a transaction that is rolled back,
     * which a standby refuses with SQLState 25006.
     */
    static boolean isStandby(Wire wire) throws SQLException {
        Wire.Result r = wire.simpleQuery("SHOW transaction_read_only");
        byte[] cell = r.rows.isEmpty() || r.rows.get(0).isEmpty() ? null : r.rows.get(0).get(0);
        String v = cell == null ? "" : new String(cell, StandardCharsets.UTF_8).trim();
        if (v.equalsIgnoreCase("on")) {
            return true;
        }
        if (v.equalsIgnoreCase("off")) {
            return false;
        }
        wire.simpleQuery("BEGIN");
        try {
            wire.simpleQuery("CREATE TEMPORARY TABLE jdbc_role_probe (x int)");
            return false;
        } catch (SQLException e) {
            if ("25006".equals(e.getSQLState())) {
                return true;
            }
            throw e;
        } finally {
            wire.simpleQuery("ROLLBACK");
        }
    }

    private static void closeQuietly(Wire wire) {
        if (wire != null) {
            wire.close();
        }
    }
}
