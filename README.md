# StockPro — Inventory Management System (JavaFX)

StockPro is a desktop Inventory Management System built with **Java 21+, JavaFX, SQLite (HikariCP), pure-Java Data Structures & Offline ML, and optional Groq API integration**.

## Core Capabilities
- **Inventory CRUD & Validation:** Add, browse, filter, inline-edit, archive, and delete items with SKU auto-generation (`PRD-1001`), automatic/override stock status (`In Stock`, `Low Stock`, `Out of Stock`), and Command Pattern Undo/Redo (`Ctrl+Z` / `Ctrl+Y`).
- **Role-Based Access & Modern UI:** Split-screen login with `jBCrypt`, `Owner`/`Manager`/`Cashier` permissions, collapsible Midnight Navy sidebar, Light & Dark themes, and `Ctrl+K` Command Bar.
- **Stock Operations & SHA-256 Audit Ledger:** Restock, Sales, Damaged, Returned, Lost, Physical Shelf Count, FEFO Expiry Tracking, and tamper-evident SHA-256 audit verification.
- **Suppliers, Purchase Orders & Finance:** Supplier directory, multi-item PO builder, OpenPDF PO sheets, live profit/margin calculation, dead-stock detection, and tax calculation.
- **Data Structures & Offline ML:** `HashMap` SKU index, Compressed `Trie` autocomplete, `BK-Tree` Levenshtein typo search, Min-Heap urgency queue, `TreeMap` range index, plus offline demand forecasting, dynamic ROP/safety stock, Z-score anomaly detection, and ABC grading.
- **Barcodes, Spreadsheets & Reports:** USB scanner support, ZXing Code-128 & QR label PDFs, Apache POI `.xlsx`/`.csv` import/export, and OpenPDF executive business reports.
- **Groq Assistant & Approval Inbox:** Natural-language Q&A, chart builder, supplier email drafting, and human-approved Auto-Buyer / Auditor / Expiry Rescue proposals with full offline fallback.

## Build & Run
```bash
mvn clean test
mvn javadoc:javadoc
mvn javafx:run
```

## Authors
- **Ehtisham Haider**
- **Syed Ahmed**
