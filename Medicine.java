import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** One medicine in the pharmacy. */
public class Medicine {
    static final int EXPIRY_WARNING_DAYS = 30;

    String id;
    String name;
    String category;
    String supplier;
    int quantity;
    int reorderLevel;
    LocalDate expiry;
    double price;
    String barcode = "";
    String location = "";   // shelf place, e.g. "Rack A, Row 3"
    String rack = "";       // e.g. "Rack A"
    String row = "";        // e.g. "Row 3"

    Medicine(String id, String name, String category, String supplier,
             int quantity, int reorderLevel, LocalDate expiry, double price) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.supplier = supplier;
        this.quantity = quantity;
        this.reorderLevel = reorderLevel;
        this.expiry = expiry;
        this.price = price;
    }

    Medicine(String id, String name, String category, String supplier,
             int quantity, int reorderLevel, LocalDate expiry, double price,
             String barcode, String location, String rack, String row) {
        this(id, name, category, supplier, quantity, reorderLevel, expiry, price);
        this.barcode = barcode == null ? "" : barcode;
        this.location = location == null ? "" : location;
        this.rack = rack == null ? "" : rack;
        this.row = row == null ? "" : row;
    }

    boolean isLowStock() {
        return quantity <= reorderLevel;
    }

    boolean isExpired() {
        return expiry.isBefore(LocalDate.now());
    }

    /** True if expired OR expiring within the warning period. */
    boolean isExpiryAlert() {
        return !expiry.isAfter(LocalDate.now().plusDays(EXPIRY_WARNING_DAYS));
    }

    String status() {
        if (isExpired()) return "EXPIRED";
        if (isExpiryAlert()) return "EXPIRING";
        if (isLowStock()) return "LOW";
        return "OK";
    }

    double value() {
        return quantity * price;
    }

    /** Stock in (positive) or stock out (negative). Never goes below zero. */
    void adjust(int change) {
        if (quantity + change < 0) {
            throw new IllegalArgumentException("Not enough stock. Current quantity: " + quantity);
        }
        quantity += change;
    }

    String toCsv() {
        return String.join(",", clean(id), clean(name), clean(category), clean(supplier),
                String.valueOf(quantity), String.valueOf(reorderLevel),
                expiry.toString(), String.valueOf(price),
                clean(barcode), clean(location), clean(rack), clean(row));
    }

    static Medicine fromCsv(String line) {
        String[] p = line.split(",", -1);
        if (p.length < 8) throw new IllegalArgumentException("Bad data line: " + line);
        Medicine m = new Medicine(p[0], p[1], p[2], p[3], Integer.parseInt(p[4]),
                Integer.parseInt(p[5]), LocalDate.parse(p[6]), Double.parseDouble(p[7]));
        if (p.length > 8) m.barcode = p[8];
        if (p.length > 9) m.location = p[9];
        if (p.length > 10) m.rack = p[10];
        if (p.length > 11) m.row = p[11];
        return m;
    }

    /** Builds a medicine from submitted form fields. */
    static Medicine fromForm(String id, Map<String, String> f) {
        String cleanId = req(Map.of("id", id), "id");
        Medicine m = new Medicine(
                cleanId,
                req(f, "name"),
                req(f, "category"),
                opt(f, "supplier"),
                nonNegInt(f, "quantity"),
                nonNegInt(f, "reorderLevel"),
                date(f, "expiry"),
                nonNegPrice(f, "price"));
        m.barcode = opt(f, "barcode");
        m.rack = opt(f, "rack");
        m.row = opt(f, "row");
        String loc = opt(f, "location");
        if (loc.isEmpty() && (!m.rack.isEmpty() || !m.row.isEmpty())) {
            loc = (m.rack.isEmpty() ? "" : m.rack) + (m.rack.isEmpty() || m.row.isEmpty() ? "" : ", ") + (m.row.isEmpty() ? "" : m.row);
        }
        m.location = loc;
        return m;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace(",", " ").replace("\n", " ").replace("\r", " ").trim();
    }

    private static String req(Map<String, String> f, String key) {
        String v = clean(f.get(key));
        if (v.isEmpty()) throw new IllegalArgumentException("Field '" + key + "' is required");
        return v;
    }

    private static String opt(Map<String, String> f, String key) {
        return clean(f.get(key));
    }

    private static int nonNegInt(Map<String, String> f, String key) {
        String v = req(f, key);
        int n;
        try {
            n = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a whole number");
        }
        if (n < 0) throw new IllegalArgumentException("'" + key + "' cannot be negative");
        return n;
    }

    private static double nonNegPrice(Map<String, String> f, String key) {
        String v = req(f, key);
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a number");
        }
        if (d < 0) throw new IllegalArgumentException("'" + key + "' cannot be negative");
        return d;
    }

    private static LocalDate date(Map<String, String> f, String key) {
        String v = req(f, key);
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Expiry must be a date like 2027-12-31");
        }
    }
}
