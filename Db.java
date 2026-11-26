import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** All database work. Data is stored in the file pharmacy.db (SQLite). */
public class Db {

    static class Account {
        String username, role, hash;
        String name = "", phone = "", email = "";
        String permissions = "billing,inventory";
        boolean approved;

        boolean hasPermission(String perm) {
            if ("OWNER".equalsIgnoreCase(role)) return true;
            if (permissions == null || permissions.isBlank()) return false;
            for (String p : permissions.split(",")) {
                if (p.trim().equalsIgnoreCase(perm)) return true;
            }
            return false;
        }
    }

    private final String url;

    Db(String file) {
        this.url = "jdbc:sqlite:" + file;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite jar not found in this folder.", e);
        }
    }

    Connection conn() throws SQLException {
        return DriverManager.getConnection(url);
    }

    static String now() {
        return LocalDateTime.now().withNano(0).toString().replace('T', ' ');
    }

    void init() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, role TEXT NOT NULL, "
                    + "password_hash TEXT NOT NULL, approved INTEGER NOT NULL, name TEXT DEFAULT '', "
                    + "phone TEXT DEFAULT '', email TEXT DEFAULT '', permissions TEXT DEFAULT 'billing,inventory')");
            s.execute("CREATE TABLE IF NOT EXISTS medicines (id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "category TEXT, supplier TEXT, quantity INTEGER NOT NULL, reorder_level INTEGER NOT NULL, "
                    + "expiry TEXT NOT NULL, price REAL NOT NULL, barcode TEXT DEFAULT '', location TEXT DEFAULT '', "
                    + "rack TEXT DEFAULT '', row TEXT DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS bills (id INTEGER PRIMARY KEY AUTOINCREMENT, customer TEXT, "
                    + "total REAL NOT NULL, cashier TEXT, created_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS bill_items (bill_id INTEGER NOT NULL, medicine_id TEXT, "
                    + "name TEXT, qty INTEGER NOT NULL, price REAL NOT NULL, line_total REAL NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "username TEXT, action TEXT, details TEXT, created_at TEXT NOT NULL)");
        }
        // add the barcode, location, rack, and row columns to databases created by earlier versions
        for (String col : new String[]{"barcode", "location", "rack", "row"}) {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.execute("ALTER TABLE medicines ADD COLUMN " + col + " TEXT DEFAULT ''");
            } catch (SQLException alreadyExists) {
                // column is already there
            }
        }
        // add name, phone, email, permissions columns to users table if needed
        for (String col : new String[]{"name", "phone", "email", "permissions"}) {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.execute("ALTER TABLE users ADD COLUMN " + col + " TEXT DEFAULT 'billing,inventory'");
            } catch (SQLException alreadyExists) {
                // column is already there
            }
        }
        if (count("users") == 0) {
            insertUser("owner", UserStore.hash("owner123"), "OWNER", true, "Pharmacy Owner", "9876543210", "owner@pharmacy.com", "billing,inventory");
            insertUser("worker", UserStore.hash("worker123"), "WORKER", true, "Staff Worker", "9123456789", "worker@pharmacy.com", "billing,inventory");
        }
        if (count("medicines") == 0) seedMedicines();
    }

    private long count(String table) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    // ================= USERS =================

    private void insertUser(String username, String hash, String role, boolean approved, String name, String phone, String email, String permissions) throws SQLException {
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement("INSERT INTO users VALUES (?,?,?,?,?,?,?,?)")) {
            p.setString(1, username);
            p.setString(2, role);
            p.setString(3, hash);
            p.setInt(4, approved ? 1 : 0);
            p.setString(5, name == null ? "" : name);
            p.setString(6, phone == null ? "" : phone);
            p.setString(7, email == null ? "" : email);
            p.setString(8, permissions == null || permissions.isBlank() ? "billing,inventory" : permissions);
            p.executeUpdate();
        }
    }

    Account findUser(String username) throws SQLException {
        if (username == null) return null;
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement("SELECT * FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return readAccount(r);
            }
        }
    }

    private Account readAccount(ResultSet r) throws SQLException {
        Account a = new Account();
        a.username = r.getString("username");
        a.role = r.getString("role");
        a.hash = r.getString("password_hash");
        a.approved = r.getInt("approved") == 1;
        try { a.name = r.getString("name") == null ? "" : r.getString("name"); } catch (SQLException ignored) {}
        try { a.phone = r.getString("phone") == null ? "" : r.getString("phone"); } catch (SQLException ignored) {}
        try { a.email = r.getString("email") == null ? "" : r.getString("email"); } catch (SQLException ignored) {}
        try { a.permissions = r.getString("permissions") == null ? "billing,inventory" : r.getString("permissions"); } catch (SQLException ignored) {}
        return a;
    }

    /** Creates a user. Self sign-up uses approved=false, so the owner must approve. */
    void addUser(String username, String password, String role, boolean approved, String name, String phone, String email, String permissions, String by) throws SQLException {
        if (username.isEmpty() || username.contains(" ") || username.contains(",")) {
            throw new IllegalArgumentException("User ID is required and cannot contain spaces or commas");
        }
        if (password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters");
        if (!role.equals("OWNER") && !role.equals("WORKER")) throw new IllegalArgumentException("Role must be OWNER or WORKER");
        if (findUser(username) != null) throw new IllegalArgumentException("User ID already taken");
        String perms = permissions == null || permissions.isBlank() ? "billing,inventory" : permissions;
        insertUser(username, UserStore.hash(password), role, approved, name, phone, email, perms);
        audit(by, "ADD_USER", username + " (" + name + ") role=" + role + " perms=" + perms);
    }

    void updateUserPermissions(String username, String permissions, String by) throws SQLException {
        Account a = findUser(username);
        if (a == null) throw new IllegalArgumentException("User not found: " + username);
        String perms = permissions == null ? "" : permissions.trim();
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement("UPDATE users SET permissions=? WHERE username=?")) {
            p.setString(1, perms);
            p.setString(2, username);
            p.executeUpdate();
        }
        audit(by, "UPDATE_PERMISSIONS", username + " perms=" + perms);
    }

    void approveUser(String username, String by) throws SQLException {
        if (findUser(username) == null) throw new IllegalArgumentException("User not found");
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement("UPDATE users SET approved=1 WHERE username=?")) {
            p.setString(1, username);
            p.executeUpdate();
        }
        audit(by, "APPROVE_USER", username);
    }

    void deleteUser(String username, String by) throws SQLException {
        Account a = findUser(username);
        if (a == null) throw new IllegalArgumentException("User not found");
        if (a.role.equals("OWNER") && countOwners() <= 1) {
            throw new IllegalArgumentException("Cannot remove the last owner");
        }
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement("DELETE FROM users WHERE username=?")) {
            p.setString(1, username);
            p.executeUpdate();
        }
        audit(by, "DELETE_USER", username);
    }

    private long countOwners() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM users WHERE role='OWNER' AND approved=1")) {
            r.next();
            return r.getLong(1);
        }
    }

    String usersJson() throws SQLException {
        StringJoiner j = new StringJoiner(",", "[", "]");
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT * FROM users ORDER BY username")) {
            while (r.next()) {
                Account a = readAccount(r);
                j.add("{\"username\":" + Json.str(a.username)
                        + ",\"name\":" + Json.str(a.name)
                        + ",\"phone\":" + Json.str(a.phone)
                        + ",\"email\":" + Json.str(a.email)
                        + ",\"role\":" + Json.str(a.role)
                        + ",\"permissions\":" + Json.str(a.permissions)
                        + ",\"approved\":" + a.approved + "}");
            }
        }
        return j.toString();
    }

    // ================= MEDICINES =================

    private Medicine readMedicine(ResultSet r) throws SQLException {
        Medicine m = new Medicine(r.getString("id"), r.getString("name"), r.getString("category"),
                r.getString("supplier"), r.getInt("quantity"), r.getInt("reorder_level"),
                LocalDate.parse(r.getString("expiry")), r.getDouble("price"));
        m.barcode = r.getString("barcode") == null ? "" : r.getString("barcode");
        m.location = r.getString("location") == null ? "" : r.getString("location");
        try { m.rack = r.getString("rack") == null ? "" : r.getString("rack"); } catch (SQLException ignored) {}
        try { m.row = r.getString("row") == null ? "" : r.getString("row"); } catch (SQLException ignored) {}
        return m;
    }

    List<Medicine> medicines() throws SQLException {
        List<Medicine> list = new ArrayList<>();
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT * FROM medicines ORDER BY name")) {
            while (r.next()) list.add(readMedicine(r));
        }
        return list;
    }

    private Medicine findMedicine(Connection c, String id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM medicines WHERE id=?")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? readMedicine(r) : null;
            }
        }
    }

    Medicine findMedicine(String id) throws SQLException {
        try (Connection c = conn()) {
            return findMedicine(c, id);
        }
    }

    void addMedicine(Medicine m, String by) throws SQLException {
        if (findMedicine(m.id) != null) throw new IllegalArgumentException("ID already exists: " + m.id);
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO medicines (id,name,category,supplier,quantity,reorder_level,expiry,price,barcode,location,rack,row) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, m.id);
            p.setString(2, m.name);
            p.setString(3, m.category);
            p.setString(4, m.supplier);
            p.setInt(5, m.quantity);
            p.setInt(6, m.reorderLevel);
            p.setString(7, m.expiry.toString());
            p.setDouble(8, m.price);
            p.setString(9, m.barcode);
            p.setString(10, m.location);
            p.setString(11, m.rack);
            p.setString(12, m.row);
            p.executeUpdate();
        }
        audit(by, "ADD_MEDICINE", m.id + " " + m.name + " qty=" + m.quantity);
    }

    void updateMedicine(Medicine m, String by) throws SQLException {
        if (findMedicine(m.id) == null) throw new IllegalArgumentException("Medicine not found: " + m.id);
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement(
                "UPDATE medicines SET name=?,category=?,supplier=?,quantity=?,reorder_level=?,expiry=?,price=?,barcode=?,location=?,rack=?,row=? WHERE id=?")) {
            p.setString(1, m.name);
            p.setString(2, m.category);
            p.setString(3, m.supplier);
            p.setInt(4, m.quantity);
            p.setInt(5, m.reorderLevel);
            p.setString(6, m.expiry.toString());
            p.setDouble(7, m.price);
            p.setString(8, m.barcode);
            p.setString(9, m.location);
            p.setString(10, m.rack);
            p.setString(11, m.row);
            p.setString(12, m.id);
            p.executeUpdate();
        }
        audit(by, "EDIT_MEDICINE", m.id + " " + m.name);
    }

    void deleteMedicine(String id, String by) throws SQLException {
        if (findMedicine(id) == null) throw new IllegalArgumentException("Medicine not found: " + id);
        try (Connection c = conn(); PreparedStatement p = c.prepareStatement("DELETE FROM medicines WHERE id=?")) {
            p.setString(1, id);
            p.executeUpdate();
        }
        audit(by, "DELETE_MEDICINE", id);
    }

    /** Manual stock in (+) or stock out (-), e.g. a delivery or a correction. */
    void adjustStock(String id, int change, String by) throws SQLException {
        try (Connection c = conn()) {
            Medicine m = findMedicine(c, id);
            if (m == null) throw new IllegalArgumentException("Medicine not found: " + id);
            m.adjust(change);
            updateQty(c, m);
            audit(c, by, change > 0 ? "STOCK_IN" : "STOCK_OUT", id + " " + change + " now " + m.quantity);
        }
    }

    private void updateQty(Connection c, Medicine m) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE medicines SET quantity=? WHERE id=?")) {
            p.setInt(1, m.quantity);
            p.setString(2, m.id);
            p.executeUpdate();
        }
    }

    // ================= BILLS =================

    /** items = list of {medicineId, quantity}. Reduces stock and saves the bill in one transaction. */
    long createBill(String customer, List<String[]> items, String cashier) throws SQLException {
        if (items.isEmpty()) throw new IllegalArgumentException("Add at least one medicine to the bill");
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                double total = 0;
                List<Medicine> meds = new ArrayList<>();
                List<Integer> qtys = new ArrayList<>();
                List<Double> lines = new ArrayList<>();
                for (String[] it : items) {
                    Medicine m = findMedicine(c, it[0]);
                    if (m == null) throw new IllegalArgumentException("Medicine not found: " + it[0]);
                    int qty = Integer.parseInt(it[1]);
                    if (qty <= 0) throw new IllegalArgumentException("Quantity must be more than 0");
                    if (m.quantity < qty) {
                        throw new IllegalArgumentException("Not enough " + m.name + " in stock (have " + m.quantity + ")");
                    }
                    m.adjust(-qty);
                    updateQty(c, m);
                    double line = qty * m.price;
                    total += line;
                    meds.add(m);
                    qtys.add(qty);
                    lines.add(line);
                }

                long billId;
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO bills (customer,total,cashier,created_at) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1, customer);
                    p.setDouble(2, total);
                    p.setString(3, cashier);
                    p.setString(4, now());
                    p.executeUpdate();
                    try (ResultSet k = p.getGeneratedKeys()) {
                        k.next();
                        billId = k.getLong(1);
                    }
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO bill_items VALUES (?,?,?,?,?,?)")) {
                    for (int i = 0; i < meds.size(); i++) {
                        Medicine m = meds.get(i);
                        p.setLong(1, billId);
                        p.setString(2, m.id);
                        p.setString(3, m.name);
                        p.setInt(4, qtys.get(i));
                        p.setDouble(5, m.price);
                        p.setDouble(6, lines.get(i));
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                audit(c, cashier, "BILL", "#" + billId + " total=" + Json.money(total));
                c.commit();
                return billId;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    String billJson(long id) throws SQLException {
        try (Connection c = conn()) {
            String head;
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM bills WHERE id=?")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("Bill not found: " + id);
                    head = "\"id\":" + id
                            + ",\"customer\":" + Json.str(r.getString("customer"))
                            + ",\"total\":" + Json.money(r.getDouble("total"))
                            + ",\"cashier\":" + Json.str(r.getString("cashier"))
                            + ",\"date\":" + Json.str(r.getString("created_at"));
                }
            }
            StringJoiner items = new StringJoiner(",", "[", "]");
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM bill_items WHERE bill_id=?")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        items.add("{\"name\":" + Json.str(r.getString("name"))
                                + ",\"qty\":" + r.getInt("qty")
                                + ",\"price\":" + Json.money(r.getDouble("price"))
                                + ",\"lineTotal\":" + Json.money(r.getDouble("line_total")) + "}");
                    }
                }
            }
            return "{" + head + ",\"items\":" + items + "}";
        }
    }

    String billsJson() throws SQLException {
        StringJoiner j = new StringJoiner(",", "[", "]");
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT * FROM bills ORDER BY id DESC")) {
            while (r.next()) {
                j.add("{\"id\":" + r.getLong("id")
                        + ",\"customer\":" + Json.str(r.getString("customer"))
                        + ",\"total\":" + Json.money(r.getDouble("total"))
                        + ",\"cashier\":" + Json.str(r.getString("cashier"))
                        + ",\"date\":" + Json.str(r.getString("created_at")) + "}");
            }
        }
        return j.toString();
    }

    // ================= AUDIT LOG =================

    void audit(String user, String action, String details) throws SQLException {
        try (Connection c = conn()) {
            audit(c, user, action, details);
        }
    }

    private void audit(Connection c, String user, String action, String details) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO audit_log (username,action,details,created_at) VALUES (?,?,?,?)")) {
            p.setString(1, user);
            p.setString(2, action);
            p.setString(3, details);
            p.setString(4, now());
            p.executeUpdate();
        }
    }

    String auditJson() throws SQLException {
        StringJoiner j = new StringJoiner(",", "[", "]");
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT * FROM audit_log ORDER BY id DESC LIMIT 200")) {
            while (r.next()) {
                j.add("{\"user\":" + Json.str(r.getString("username"))
                        + ",\"action\":" + Json.str(r.getString("action"))
                        + ",\"details\":" + Json.str(r.getString("details"))
                        + ",\"date\":" + Json.str(r.getString("created_at")) + "}");
            }
        }
        return j.toString();
    }

    // ================= SAMPLE DATA =================

    private void seedMedicines() throws SQLException {
        LocalDate t = LocalDate.now();
        addMedicine(new Medicine("M001", "Paracetamol 500mg", "Pain Relief", "PharmaCo", 200, 50, t.plusDays(400), 5.00, "8901001001", "Rack A, Row 1", "Rack A", "Row 1"), "system");
        addMedicine(new Medicine("M002", "Amoxicillin 500mg", "Antibiotic", "MediSupply", 80, 30, t.plusDays(200), 45.00, "8901001002", "Rack A, Row 2", "Rack A", "Row 2"), "system");
        addMedicine(new Medicine("M003", "Ibuprofen 400mg", "Pain Relief", "PharmaCo", 25, 40, t.plusDays(300), 12.00, "8901001003", "Rack B, Row 1", "Rack B", "Row 1"), "system");
        addMedicine(new Medicine("M004", "Insulin Glargine", "Diabetes", "BioLife", 15, 10, t.plusDays(12), 450.00, "8901001004", "Fridge 1, Shelf 1", "Fridge 1", "Shelf 1"), "system");
        addMedicine(new Medicine("M005", "Cough Syrup 100ml", "Cold & Flu", "HealthPlus", 40, 15, t.minusDays(20), 85.00, "8901001005", "Rack B, Row 3", "Rack B", "Row 3"), "system");
        addMedicine(new Medicine("M006", "Surgical Gloves (box)", "Supplies", "SafeHands", 60, 20, t.plusDays(600), 180.00, "8901001006", "Rack C, Row 1", "Rack C", "Row 1"), "system");
        addMedicine(new Medicine("M007", "Syringe 5ml", "Supplies", "SafeHands", 500, 100, t.plusDays(900), 15.00, "8901001007", "Rack C, Row 2", "Rack C", "Row 2"), "system");
        addMedicine(new Medicine("M008", "Metformin 500mg", "Diabetes", "BioLife", 120, 40, t.plusDays(250), 25.00, "8901001008", "Rack A, Row 3", "Rack A", "Row 3"), "system");
        addMedicine(new Medicine("M009", "Omeprazole 20mg", "Stomach", "MediSupply", 8, 20, t.plusDays(180), 35.00, "8901001009", "Rack B, Row 2", "Rack B", "Row 2"), "system");
        addMedicine(new Medicine("M010", "Vitamin C 500mg", "Supplements", "HealthPlus", 150, 30, t.plusDays(500), 20.00, "8901001010", "Rack D, Row 1", "Rack D", "Row 1"), "system");
    }
}
