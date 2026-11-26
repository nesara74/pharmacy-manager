# Pharmacy Manager (Java website)

A simple pharmacy stock website written in plain Java. It uses only the built-in Java web server, so no extra libraries are needed.

## Features
- Add, edit, and delete medicines
- Search by name, category, supplier, or ID
- Stock In (new delivery) and Stock Out (dispensed)
- Summary cards: number of medicines, total units, stock value, low stock, expired/expiring
- Filter for low stock or expired/expiring soon
- Data saved automatically to `pharmacy.csv`

## Folder layout
```
PharmacyWeb/
  Medicine.java
  PharmacyStore.java
  Json.java
  PharmacyServer.java
  web/
    index.html
```

## How to run (Java 17 or newer)
1. Open a terminal in the `PharmacyWeb` folder (the one that contains `web`).
2. Compile:
   ```
   javac *.java
   ```
3. Start:
   ```
   java PharmacyServer
   ```
4. Open http://localhost:8080 in your browser.

Keep the terminal open while using the site. Press Ctrl+C to stop.

## Notes
- The server must be started from the `PharmacyWeb` folder, because it reads `web/index.html` and writes `pharmacy.csv` from the current folder.
- The site runs on this computer only (localhost). It has no login.
- Expiry warnings cover items expiring within 30 days (set by `EXPIRY_WARNING_DAYS` in `Medicine.java`).
