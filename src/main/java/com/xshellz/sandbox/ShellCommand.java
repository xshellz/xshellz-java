package com.xshellz.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Wraps a command with optional {@code cd} and environment exports.
 *
 * <p>Environment variables are exported in the remote shell (sshd rarely
 * honours {@code AcceptEnv}). Internal.
 */
final class ShellCommand {

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_UNQUOTED = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");

    private ShellCommand() {
    }

    static String build(String command, String cwd, Map<String, String> env) {
        List<String> parts = new ArrayList<>();
        if (env != null && !env.isEmpty()) {
            StringBuilder exports = new StringBuilder("export");
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (!ENV_NAME.matcher(entry.getKey()).matches()) {
                    throw new XshellzException(
                            "Invalid environment variable name: '" + entry.getKey() + "'");
                }
                exports.append(' ').append(entry.getKey()).append('=').append(quote(entry.getValue()));
            }
            parts.add(exports.toString());
        }
        if (cwd != null && !cwd.isEmpty()) {
            parts.add("cd " + quote(cwd));
        }
        parts.add(command);
        return String.join(" && ", parts);
    }

    /** POSIX single-quote quoting, mirroring Python's {@code shlex.quote}. */
    static String quote(String value) {
        if (value != null && SAFE_UNQUOTED.matcher(value).matches()) {
            return value;
        }
        String body = value == null ? "" : value;
        return "'" + body.replace("'", "'\\''") + "'";
    }
}
