import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Staff accounts. Roles: OWNER (full access) and WORKER (daily work, no delete, no user management). */
public class UserStore {

    static class User {
        final String username;
        final String role;
        final String hash;

        User(String username, String role, String hash) {
            this.username = username;
            this.role = role;
            this.hash = hash;
        }
    }

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Path file;

    UserStore(String fileName) {
        this.file = Paths.get(fileName);
    }

    /** Loads users from users.csv. If there are no users yet, creates the default owner and worker. */
    synchronized void load() throws IOException {
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] p = line.split(",", -1);
                if (p.length == 3) users.put(p[0], new User(p[0], p[1], p[2]));
            }
        }
        if (users.isEmpty()) {
            users.put("owner", new User("owner", "OWNER", hash("owner123")));
            users.put("worker", new User("worker", "WORKER", hash("worker123")));
            save();
        }
    }

    synchronized User get(String username) {
        return users.get(username);
    }

    synchronized User login(String username, String password) {
        User u = users.get(username);
        if (u == null || !u.hash.equals(hash(password))) return null;
        return u;
    }

    synchronized List<User> all() {
        return new ArrayList<>(users.values());
    }

    synchronized void create(String username, String password, String role) throws IOException {
        if (username.isEmpty() || username.contains(",") || username.contains(" ")) {
            throw new IllegalArgumentException("Username is required and cannot contain spaces or commas");
        }
        if (password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (!role.equals("OWNER") && !role.equals("WORKER")) {
            throw new IllegalArgumentException("Role must be OWNER or WORKER");
        }
        if (users.containsKey(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        users.put(username, new User(username, role, hash(password)));
        save();
    }

    synchronized void delete(String username) throws IOException {
        User u = users.get(username);
        if (u == null) throw new IllegalArgumentException("User not found: " + username);
        if (u.role.equals("OWNER") && countOwners() <= 1) {
            throw new IllegalArgumentException("Cannot remove the last owner account");
        }
        users.remove(username);
        save();
    }

    private long countOwners() {
        long n = 0;
        for (User u : users.values()) if (u.role.equals("OWNER")) n++;
        return n;
    }

    private void save() throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (User u : users.values()) {
                w.write(u.username + "," + u.role + "," + u.hash);
                w.newLine();
            }
        }
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
