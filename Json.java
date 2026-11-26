import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** Builds JSON text by hand (no libraries needed). */
public class Json {

    static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String money(double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    static String medicine(Medicine m) {
        return "{"
                + "\"id\":" + str(m.id)
                + ",\"name\":" + str(m.name)
                + ",\"category\":" + str(m.category)
                + ",\"supplier\":" + str(m.supplier)
                + ",\"quantity\":" + m.quantity
                + ",\"reorderLevel\":" + m.reorderLevel
                + ",\"expiry\":" + str(m.expiry.toString())
                + ",\"price\":" + money(m.price)
                + ",\"value\":" + money(m.value())
                + ",\"barcode\":" + str(m.barcode)
                + ",\"location\":" + str(m.location)
                + ",\"rack\":" + str(m.rack)
                + ",\"row\":" + str(m.row)
                + ",\"status\":" + str(m.status())
                + ",\"lowStock\":" + m.isLowStock()
                + ",\"expiryAlert\":" + m.isExpiryAlert()
                + "}";
    }

    static String list(List<Medicine> items) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Medicine m : items) j.add(medicine(m));
        return j.toString();
    }

    static String summary(List<Medicine> items) {
        int units = 0, low = 0, expiring = 0;
        double value = 0;
        for (Medicine m : items) {
            units += m.quantity;
            value += m.value();
            if (m.isLowStock()) low++;
            if (m.isExpiryAlert()) expiring++;
        }
        return "{\"items\":" + items.size()
                + ",\"units\":" + units
                + ",\"value\":" + money(value)
                + ",\"low\":" + low
                + ",\"expiring\":" + expiring + "}";
    }
}
