import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps all medicines in memory and saves them to a CSV file after every change. */
public class PharmacyStore {
    private final Map<String, Medicine> items = new LinkedHashMap<>();
    private final Path file;

    PharmacyStore(String fileName) {
        this.file = Paths.get(fileName);
    }

    synchronized List<Medicine> all() {
        return new ArrayList<>(items.values());
    }

    synchronized List<Medicine> search(String text) {
        String k = text == null ? "" : text.trim().toLowerCase();
        List<Medicine> result = new ArrayList<>();
        for (Medicine m : items.values()) {
            if (k.isEmpty()
                    || m.id.toLowerCase().contains(k)
                    || m.name.toLowerCase().contains(k)
                    || m.category.toLowerCase().contains(k)
                    || m.supplier.toLowerCase().contains(k)
                    || m.barcode.toLowerCase().contains(k)
                    || m.location.toLowerCase().contains(k)
                    || m.rack.toLowerCase().contains(k)
                    || m.row.toLowerCase().contains(k)) {
                result.add(m);
            }
        }
        return result;
    }

    synchronized void add(Medicine m) throws IOException {
        if (items.containsKey(m.id)) {
            throw new IllegalArgumentException("ID already exists: " + m.id);
        }
        items.put(m.id, m);
        save();
    }

    synchronized void update(String id, Medicine m) throws IOException {
        if (!items.containsKey(id)) {
            throw new IllegalArgumentException("Medicine not found: " + id);
        }
        m.id = id;
        items.put(id, m);
        save();
    }

    synchronized void delete(String id) throws IOException {
        if (items.remove(id) == null) {
            throw new IllegalArgumentException("Medicine not found: " + id);
        }
        save();
    }

    synchronized void adjust(String id, int change) throws IOException {
        Medicine m = items.get(id);
        if (m == null) {
            throw new IllegalArgumentException("Medicine not found: " + id);
        }
        m.adjust(change);
        save();
    }

    private void save() throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Medicine m : items.values()) {
                w.write(m.toCsv());
                w.newLine();
            }
        }
    }

    void load() throws IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                Medicine m = Medicine.fromCsv(line);
                items.put(m.id, m);
            }
        }
    }
}
