import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Web server (built-in Java) + SQLite database. Open http://localhost:8080 in a browser. */
public class PharmacyServer {
    static final int PORT = 8080;
    static final Path WEB_DIR = Paths.get("web").toAbsolutePath().normalize();
    static final Db db = new Db("pharmacy.db");
    static final Map<String, String> sessions = new ConcurrentHashMap<>(); // token -> username
    static final String JSON = "application/json; charset=utf-8";

    static class Forbidden extends RuntimeException {
        Forbidden(String msg) { super(msg); }
    }

    public static void main(String[] args) throws Exception {
        db.init();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", PharmacyServer::handle);
        server.start();
        System.out.println("Pharmacy system running at http://localhost:" + PORT);
        System.out.println("Keep this window open. Press Ctrl+C to stop.");
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(ex, path);
            } else if (ex.getRequestMethod().equals("GET")) {
                serveFile(ex, path);
            } else {
                send(ex, 405, "text/plain; charset=utf-8", "Method not allowed");
            }
        } catch (Forbidden e) {
            send(ex, 403, JSON, "{\"error\":" + Json.str(e.getMessage()) + "}");
        } catch (IllegalArgumentException e) {
            send(ex, 400, JSON, "{\"error\":" + Json.str(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, JSON, "{\"error\":" + Json.str("Server error: " + e) + "}");
        }
    }

    static void handleApi(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        String[] p = path.split("/"); // ["", "api", "medicines", id, "stock"]

        // ----- public: login and sign-up -----
        if (path.equals("/api/login") && method.equals("POST")) {
            Map<String, String> f = parseForm(readBody(ex));
            String name = f.getOrDefault("username", "").trim();
            Db.Account a = db.findUser(name);
            if (a == null || !a.hash.equals(UserStore.hash(f.getOrDefault("password", "")))) {
                send(ex, 401, JSON, "{\"error\":\"Wrong username or password\"}");
                return;
            }
            if (!a.approved) {
                send(ex, 403, JSON, "{\"error\":\"Your account is waiting for owner approval\"}");
                return;
            }
            String token = UUID.randomUUID().toString();
            sessions.put(token, a.username);
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            send(ex, 200, JSON, userJson(a));
            return;
        }
        if (path.equals("/api/signup") && method.equals("POST")) {
            Map<String, String> f = parseForm(readBody(ex));
            db.addUser(f.getOrDefault("username", "").trim(),
                    f.getOrDefault("password", ""),
                    "WORKER", false,
                    f.getOrDefault("name", "").trim(),
                    f.getOrDefault("phone", "").trim(),
                    f.getOrDefault("email", "").trim(),
                    "billing,inventory",
                    "self-signup");
            send(ex, 200, JSON, "{\"pending\":true}");
            return;
        }
        if (path.equals("/api/logout") && method.equals("POST")) {
            String t = token(ex);
            if (t != null) sessions.remove(t);
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=; Path=/; Max-Age=0");
            send(ex, 200, JSON, "{\"ok\":true}");
            return;
        }

        // ----- everything below needs a logged-in, approved user -----
        Db.Account me = currentUser(ex);
        if (me == null) {
            send(ex, 401, JSON, "{\"error\":\"Please log in first\"}");
            return;
        }
        boolean owner = me.role.equals("OWNER");

        if (path.equals("/api/me") && method.equals("GET")) {
            send(ex, 200, JSON, userJson(me));

        } else if (path.equals("/api/medicines") && method.equals("GET")) {
            String q = parseForm(ex.getRequestURI().getRawQuery()).getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
            List<Medicine> out = new ArrayList<>();
            for (Medicine m : db.medicines()) {
                if (q.isEmpty() || (m.id + " " + m.name + " " + m.category + " " + m.supplier + " " + m.barcode + " " + m.location + " " + m.rack + " " + m.row).toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(m);
                }
            }
            send(ex, 200, JSON, Json.list(out));

        } else if (path.equals("/api/medicines") && method.equals("POST")) {
            if (!owner && !me.hasPermission("inventory")) throw new Forbidden("Permission denied: Inventory Staff access required");
            Map<String, String> f = parseForm(readBody(ex));
            Medicine m = Medicine.fromForm(f.get("id"), f);
            if (!owner) {
                m.barcode = "";
                m.location = "";
                m.rack = "";
                m.row = "";
            }
            db.addMedicine(m, me.username);
            send(ex, 200, JSON, Json.medicine(m));

        } else if (path.equals("/api/summary") && method.equals("GET")) {
            send(ex, 200, JSON, Json.summary(db.medicines()));

        } else if (p.length == 4 && p[2].equals("medicines") && method.equals("PUT")) {
            if (!owner && !me.hasPermission("inventory")) throw new Forbidden("Permission denied: Inventory Staff access required");
            Map<String, String> f = parseForm(readBody(ex));
            Medicine m = Medicine.fromForm(p[3], f);
            if (!owner) {
                // workers cannot change barcode, location, rack, or row: keep existing values
                Medicine old = db.findMedicine(p[3]);
                if (old != null) {
                    m.barcode = old.barcode;
                    m.location = old.location;
                    m.rack = old.rack;
                    m.row = old.row;
                }
            }
            db.updateMedicine(m, me.username);
            send(ex, 200, JSON, Json.medicine(m));

        } else if (p.length == 4 && p[2].equals("medicines") && method.equals("DELETE")) {
            if (!owner) throw new Forbidden("Only the owner can delete medicines");
            db.deleteMedicine(p[3], me.username);
            send(ex, 200, JSON, "{\"ok\":true}");

        } else if (p.length == 5 && p[2].equals("medicines") && p[4].equals("stock") && method.equals("POST")) {
            if (!owner && !me.hasPermission("inventory")) throw new Forbidden("Permission denied: Inventory Staff access required");
            Map<String, String> f = parseForm(readBody(ex));
            int change;
            try {
                change = Integer.parseInt(f.getOrDefault("change", "").trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Amount must be a whole number");
            }
            db.adjustStock(p[3], change, me.username);
            send(ex, 200, JSON, "{\"ok\":true}");

        } else if (path.equals("/api/bills") && method.equals("GET")) {
            send(ex, 200, JSON, db.billsJson());

        } else if (path.equals("/api/bills") && method.equals("POST")) {
            if (!owner && !me.hasPermission("billing")) throw new Forbidden("Permission denied: Cashier (Billing) access required");
            Map<String, String> f = parseForm(readBody(ex));
            String customer = f.getOrDefault("customer", "").trim();
            if (customer.isEmpty()) customer = "Walk-in customer";
            String cashierInfo = me.name != null && !me.name.isBlank() ? me.username + " (" + me.name + ")" : me.username;
            long billId = db.createBill(customer, parseItems(f.get("items")), cashierInfo);
            send(ex, 200, JSON, db.billJson(billId));

        } else if (p.length == 4 && p[2].equals("bills") && method.equals("GET")) {
            send(ex, 200, JSON, db.billJson(Long.parseLong(p[3])));

        } else if (path.equals("/api/users") && method.equals("GET")) {
            if (!owner) throw new Forbidden("Only the owner can view staff accounts");
            send(ex, 200, JSON, db.usersJson());

        } else if (path.equals("/api/users") && method.equals("POST")) {
            if (!owner) throw new Forbidden("Only the owner can add accounts");
            Map<String, String> f = parseForm(readBody(ex));
            db.addUser(f.getOrDefault("username", "").trim(),
                    f.getOrDefault("password", ""),
                    f.getOrDefault("role", "WORKER"), true,
                    f.getOrDefault("name", "").trim(),
                    f.getOrDefault("phone", "").trim(),
                    f.getOrDefault("email", "").trim(),
                    f.getOrDefault("permissions", "billing,inventory"),
                    me.username);
            send(ex, 200, JSON, "{\"ok\":true}");

        } else if (p.length == 5 && p[2].equals("users") && p[4].equals("permissions") && method.equals("POST")) {
            if (!owner) throw new Forbidden("Only the owner can manage permissions");
            Map<String, String> f = parseForm(readBody(ex));
            db.updateUserPermissions(p[3], f.getOrDefault("permissions", ""), me.username);
            send(ex, 200, JSON, "{\"ok\":true}");

        } else if (p.length == 5 && p[2].equals("users") && p[4].equals("approve") && method.equals("POST")) {
            if (!owner) throw new Forbidden("Only the owner can approve accounts");
            db.approveUser(p[3], me.username);
            send(ex, 200, JSON, "{\"ok\":true}");

        } else if (p.length == 4 && p[2].equals("users") && method.equals("DELETE")) {
            if (!owner) throw new Forbidden("Only the owner can remove accounts");
            if (p[3].equals(me.username)) throw new IllegalArgumentException("You cannot remove your own account");
            db.deleteUser(p[3], me.username);
            send(ex, 200, JSON, "{\"ok\":true}");

        } else if (path.equals("/api/audit") && method.equals("GET")) {
            if (!owner) throw new Forbidden("Only the owner can view the activity log");
            send(ex, 200, JSON, db.auditJson());

        } else {
            send(ex, 404, JSON, "{\"error\":\"Not found\"}");
        }
    }

    /** Parses "M001:2,M002:1" into [medicineId, quantity] pairs. */
    static List<String[]> parseItems(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) throw new IllegalArgumentException("Bad bill item: " + part);
            out.add(new String[]{kv[0].trim(), kv[1].trim()});
        }
        return out;
    }

    static String userJson(Db.Account a) {
        return "{\"username\":" + Json.str(a.username)
                + ",\"name\":" + Json.str(a.name)
                + ",\"phone\":" + Json.str(a.phone)
                + ",\"email\":" + Json.str(a.email)
                + ",\"role\":" + Json.str(a.role) + "}";
    }

    static String token(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith("SESSION=")) return part.substring("SESSION=".length());
        }
        return null;
    }

    static Db.Account currentUser(HttpExchange ex) throws Exception {
        String t = token(ex);
        if (t == null) return null;
        String name = sessions.get(t);
        if (name == null) return null;
        Db.Account a = db.findUser(name);
        return (a != null && a.approved) ? a : null;
    }

    static void serveFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        Path file = WEB_DIR.resolve(path.substring(1)).normalize();
        if (!file.startsWith(WEB_DIR) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        sendBytes(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(file));
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        sendBytes(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Parses "a=1&b=two" text (form data or URL query). */
    static Map<String, String> parseForm(String text) {
        Map<String, String> map = new HashMap<>();
        if (text == null || text.isEmpty()) return map;
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }
}
