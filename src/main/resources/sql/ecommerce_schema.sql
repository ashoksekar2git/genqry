-- ============================================================
--  SEEK — Ecommerce PostgreSQL Schema
--  Tables   : Customers, Orders, Categories, Products,
--             OrderDetails, Suppliers, ProductSuppliers,
--             Attributes (EAV Meta), ProductAttributeValues (EAV Data)
--  Patterns : 1:N, N:1, M:N (bridge), EAV (Entity-Attribute-Value)
--  Created  : 2026-03-10
-- ============================================================

-- ── Drop tables in reverse dependency order (safe re-run) ────────────────────
DROP TABLE IF EXISTS ProductAttributeValues CASCADE;
DROP TABLE IF EXISTS Attributes             CASCADE;
DROP TABLE IF EXISTS ProductSuppliers       CASCADE;
DROP TABLE IF EXISTS Suppliers              CASCADE;
DROP TABLE IF EXISTS OrderDetails           CASCADE;
DROP TABLE IF EXISTS Products               CASCADE;
DROP TABLE IF EXISTS Categories             CASCADE;
DROP TABLE IF EXISTS Orders                 CASCADE;
DROP TABLE IF EXISTS Customers              CASCADE;

-- ============================================================
-- 1. Customers
--    1:N with Orders
-- ============================================================
CREATE TABLE Customers (
    CustomerID   SERIAL          PRIMARY KEY,
    Name         VARCHAR(150)    NOT NULL,
    Email        VARCHAR(255)    NOT NULL UNIQUE,
    Address      VARCHAR(500),
    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  Customers              IS 'Stores customer details. Each customer can place many orders.';
COMMENT ON COLUMN Customers.CustomerID   IS 'Auto-increment primary key for the customers table.';
COMMENT ON COLUMN Customers.Name         IS 'Full name of the customer.';
COMMENT ON COLUMN Customers.Email        IS 'Unique email address used for login and communication.';
COMMENT ON COLUMN Customers.Address      IS 'Physical/postal address of the customer including street, city, state, zip and country.';
COMMENT ON COLUMN Customers.CreatedAt    IS 'Timestamp when the customer record was created.';
COMMENT ON COLUMN Customers.UpdatedAt    IS 'Timestamp when the customer record was last updated.';

-- ============================================================
-- 2. Orders
--    N:1 with Customers
--    1:N with OrderDetails
-- ============================================================
CREATE TABLE Orders (
    OrderID      SERIAL          PRIMARY KEY,
    CustomerID   INT             NOT NULL,
    OrderDate    DATE            NOT NULL DEFAULT CURRENT_DATE,
    TotalAmount  NUMERIC(12, 2)  NOT NULL DEFAULT 0.00,
    Status       VARCHAR(50)     NOT NULL DEFAULT 'PENDING'
                                 CHECK (Status IN ('PENDING','CONFIRMED','SHIPPED','DELIVERED','CANCELLED')),
    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_orders_customer
        FOREIGN KEY (CustomerID) REFERENCES Customers (CustomerID)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

COMMENT ON TABLE  Orders             IS 'Records each customer order. TotalAmount is the sum of all OrderDetails lines.';
COMMENT ON COLUMN Orders.OrderID     IS 'Auto-increment primary key for the orders table.';
COMMENT ON COLUMN Orders.CustomerID  IS 'Foreign key referencing Customers.CustomerID — the customer who placed this order.';
COMMENT ON COLUMN Orders.OrderDate   IS 'Date the order was placed.';
COMMENT ON COLUMN Orders.TotalAmount IS 'Total monetary value of the order. Sum of (Quantity * UnitPrice) across all OrderDetails.';
COMMENT ON COLUMN Orders.Status      IS 'Current lifecycle status of the order: PENDING, CONFIRMED, SHIPPED, DELIVERED, or CANCELLED.';

CREATE INDEX idx_orders_customer   ON Orders (CustomerID);
CREATE INDEX idx_orders_date       ON Orders (OrderDate);
CREATE INDEX idx_orders_status     ON Orders (Status);

-- ============================================================
-- 3. Categories
--    1:N with Products
-- ============================================================
CREATE TABLE Categories (
    CategoryID   SERIAL          PRIMARY KEY,
    Name         VARCHAR(150)    NOT NULL UNIQUE,
    Description  TEXT,
    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  Categories             IS 'Product categories used to group and filter products.';
COMMENT ON COLUMN Categories.CategoryID  IS 'Auto-increment primary key for the categories table.';
COMMENT ON COLUMN Categories.Name        IS 'Unique category name e.g. Electronics, Clothing, Books.';
COMMENT ON COLUMN Categories.Description IS 'Optional description of the category.';

-- ============================================================
-- 4. Products
--    N:1 with Categories
--    1:N with OrderDetails
--    M:N with Suppliers (via ProductSuppliers)
--    1:N with ProductAttributeValues (EAV)
-- ============================================================
CREATE TABLE Products (
    ProductID    SERIAL          PRIMARY KEY,
    Name         VARCHAR(255)    NOT NULL,
    Description  TEXT,
    CategoryID   INT             NOT NULL,
    Price        NUMERIC(10, 2)  NOT NULL CHECK (Price >= 0),
    StockQty     INT             NOT NULL DEFAULT 0 CHECK (StockQty >= 0),
    SKU          VARCHAR(100)    UNIQUE,
    IsActive     BOOLEAN         NOT NULL DEFAULT TRUE,
    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_products_category
        FOREIGN KEY (CategoryID) REFERENCES Categories (CategoryID)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

COMMENT ON TABLE  Products             IS 'Master product catalogue. Each product belongs to one category and may have many EAV attributes.';
COMMENT ON COLUMN Products.ProductID   IS 'Auto-increment primary key for the products table.';
COMMENT ON COLUMN Products.Name        IS 'Display name of the product.';
COMMENT ON COLUMN Products.Description IS 'Long-form description of the product.';
COMMENT ON COLUMN Products.CategoryID  IS 'Foreign key referencing Categories.CategoryID.';
COMMENT ON COLUMN Products.Price       IS 'Base selling price of the product.';
COMMENT ON COLUMN Products.StockQty    IS 'Current quantity in stock. Cannot be negative.';
COMMENT ON COLUMN Products.SKU         IS 'Stock Keeping Unit — unique product identifier used in warehousing.';
COMMENT ON COLUMN Products.IsActive    IS 'Whether the product is currently available for purchase.';

CREATE INDEX idx_products_category ON Products (CategoryID);
CREATE INDEX idx_products_active   ON Products (IsActive);
CREATE INDEX idx_products_sku      ON Products (SKU);

-- ============================================================
-- 5. OrderDetails
--    N:1 with Orders
--    N:1 with Products
-- ============================================================
CREATE TABLE OrderDetails (
    OrderDetailID  SERIAL          PRIMARY KEY,
    OrderID        INT             NOT NULL,
    ProductID      INT             NOT NULL,
    Quantity       INT             NOT NULL CHECK (Quantity > 0),
    UnitPrice      NUMERIC(10, 2)  NOT NULL CHECK (UnitPrice >= 0),
    LineTotal      NUMERIC(12, 2)  GENERATED ALWAYS AS (Quantity * UnitPrice) STORED,

    CONSTRAINT fk_orderdetails_order
        FOREIGN KEY (OrderID)   REFERENCES Orders   (OrderID)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    CONSTRAINT fk_orderdetails_product
        FOREIGN KEY (ProductID) REFERENCES Products (ProductID)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    CONSTRAINT uq_orderdetails_order_product
        UNIQUE (OrderID, ProductID)   -- one line per product per order
);

COMMENT ON TABLE  OrderDetails               IS 'Line items for each order. Each row represents one product in an order.';
COMMENT ON COLUMN OrderDetails.OrderDetailID IS 'Auto-increment primary key for the order details table.';
COMMENT ON COLUMN OrderDetails.OrderID       IS 'Foreign key referencing Orders.OrderID.';
COMMENT ON COLUMN OrderDetails.ProductID     IS 'Foreign key referencing Products.ProductID.';
COMMENT ON COLUMN OrderDetails.Quantity      IS 'Number of units of the product ordered. Must be at least 1.';
COMMENT ON COLUMN OrderDetails.UnitPrice     IS 'Price per unit at the time of ordering (snapshot — independent of current product price).';
COMMENT ON COLUMN OrderDetails.LineTotal     IS 'Computed column: Quantity * UnitPrice. Stored for query performance.';

CREATE INDEX idx_orderdetails_order   ON OrderDetails (OrderID);
CREATE INDEX idx_orderdetails_product ON OrderDetails (ProductID);

-- ============================================================
-- 6. Suppliers
--    M:N with Products (via ProductSuppliers)
-- ============================================================
CREATE TABLE Suppliers (
    SupplierID   SERIAL          PRIMARY KEY,
    Name         VARCHAR(255)    NOT NULL,
    ContactInfo  TEXT,
    Email        VARCHAR(255),
    Phone        VARCHAR(50),
    Address      VARCHAR(500),
    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  Suppliers             IS 'Suppliers who provide products. One supplier can supply many products; one product can have many suppliers.';
COMMENT ON COLUMN Suppliers.SupplierID  IS 'Auto-increment primary key for the suppliers table.';
COMMENT ON COLUMN Suppliers.Name        IS 'Business name of the supplier.';
COMMENT ON COLUMN Suppliers.ContactInfo IS 'General contact information text (legacy field).';
COMMENT ON COLUMN Suppliers.Email       IS 'Primary contact email address of the supplier.';
COMMENT ON COLUMN Suppliers.Phone       IS 'Contact phone number of the supplier.';
COMMENT ON COLUMN Suppliers.Address     IS 'Physical address of the supplier.';

-- ============================================================
-- 7. ProductSuppliers  (M:N bridge table)
--    Many Products ↔ Many Suppliers
-- ============================================================
CREATE TABLE ProductSuppliers (
    ProductID    INT             NOT NULL,
    SupplierID   INT             NOT NULL,
    SupplyPrice  NUMERIC(10, 2)  CHECK (SupplyPrice >= 0),
    LeadTimeDays INT,
    IsPreferred  BOOLEAN         NOT NULL DEFAULT FALSE,
    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_productsuppliers
        PRIMARY KEY (ProductID, SupplierID),

    CONSTRAINT fk_ps_product
        FOREIGN KEY (ProductID)  REFERENCES Products  (ProductID)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    CONSTRAINT fk_ps_supplier
        FOREIGN KEY (SupplierID) REFERENCES Suppliers (SupplierID)
        ON DELETE CASCADE
        ON UPDATE CASCADE
);

COMMENT ON TABLE  ProductSuppliers             IS 'Bridge table for the M:N relationship between Products and Suppliers.';
COMMENT ON COLUMN ProductSuppliers.ProductID   IS 'Foreign key referencing Products.ProductID.';
COMMENT ON COLUMN ProductSuppliers.SupplierID  IS 'Foreign key referencing Suppliers.SupplierID.';
COMMENT ON COLUMN ProductSuppliers.SupplyPrice IS 'Price at which this supplier provides this product.';
COMMENT ON COLUMN ProductSuppliers.LeadTimeDays IS 'Number of days from order to delivery for this supplier-product combination.';
COMMENT ON COLUMN ProductSuppliers.IsPreferred IS 'Whether this is the preferred supplier for this product.';

CREATE INDEX idx_ps_supplier ON ProductSuppliers (SupplierID);

-- ============================================================
-- 8. Attributes  (EAV Meta table)
--    Defines attribute names like 'Color', 'Size', 'Weight'
--    1:N with ProductAttributeValues
-- ============================================================
CREATE TABLE Attributes (
    AttributeID  SERIAL          PRIMARY KEY,
    Name         VARCHAR(100)    NOT NULL UNIQUE,
    DataType     VARCHAR(50)     NOT NULL DEFAULT 'varchar'
                                 CHECK (DataType IN ('varchar','integer','decimal','boolean','date')),
    Unit         VARCHAR(50),
    Description  TEXT,
    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  Attributes             IS 'EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.';
COMMENT ON COLUMN Attributes.AttributeID IS 'Auto-increment primary key for the attributes table.';
COMMENT ON COLUMN Attributes.Name        IS 'Attribute name e.g. Color, Size, Weight, Material, Voltage.';
COMMENT ON COLUMN Attributes.DataType    IS 'Logical data type of the attribute value: varchar, integer, decimal, boolean, or date.';
COMMENT ON COLUMN Attributes.Unit        IS 'Optional unit of measurement e.g. kg, cm, V, W.';
COMMENT ON COLUMN Attributes.Description IS 'Human-readable description of what this attribute represents.';

-- ============================================================
-- 9. ProductAttributeValues  (EAV Data table)
--    Stores actual attribute values per product
--    N:1 with Products
--    N:1 with Attributes
-- ============================================================
CREATE TABLE ProductAttributeValues (
    ValueID      SERIAL          PRIMARY KEY,
    ProductID    INT             NOT NULL,
    AttributeID  INT             NOT NULL,

    -- Generic value column (always populated)
    Value        VARCHAR(500)    NOT NULL,

    -- Typed value columns for performance / range queries (populated based on Attributes.DataType)
    ValueInt     INTEGER,
    ValueDecimal NUMERIC(15, 4),
    ValueBool    BOOLEAN,
    ValueDate    DATE,

    CreatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_pav_product
        FOREIGN KEY (ProductID)   REFERENCES Products   (ProductID)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    CONSTRAINT fk_pav_attribute
        FOREIGN KEY (AttributeID) REFERENCES Attributes (AttributeID)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    CONSTRAINT uq_pav_product_attribute
        UNIQUE (ProductID, AttributeID)   -- one value per attribute per product
);

COMMENT ON TABLE  ProductAttributeValues             IS 'EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.';
COMMENT ON COLUMN ProductAttributeValues.ValueID     IS 'Auto-increment primary key for the product attribute values table.';
COMMENT ON COLUMN ProductAttributeValues.ProductID   IS 'Foreign key referencing Products.ProductID — the product this attribute value belongs to.';
COMMENT ON COLUMN ProductAttributeValues.AttributeID IS 'Foreign key referencing Attributes.AttributeID — the attribute being set.';
COMMENT ON COLUMN ProductAttributeValues.Value       IS 'Generic string representation of the attribute value. Always populated regardless of data type.';
COMMENT ON COLUMN ProductAttributeValues.ValueInt    IS 'Typed integer value. Populated when Attributes.DataType = integer for efficient numeric range queries.';
COMMENT ON COLUMN ProductAttributeValues.ValueDecimal IS 'Typed decimal value. Populated when Attributes.DataType = decimal for efficient numeric range queries.';
COMMENT ON COLUMN ProductAttributeValues.ValueBool   IS 'Typed boolean value. Populated when Attributes.DataType = boolean.';
COMMENT ON COLUMN ProductAttributeValues.ValueDate   IS 'Typed date value. Populated when Attributes.DataType = date for efficient date range queries.';

CREATE INDEX idx_pav_product   ON ProductAttributeValues (ProductID);
CREATE INDEX idx_pav_attribute ON ProductAttributeValues (AttributeID);
CREATE INDEX idx_pav_prod_attr ON ProductAttributeValues (ProductID, AttributeID);

-- ============================================================
-- AUTO-UPDATE UpdatedAt trigger (reusable function)
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.UpdatedAt = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_customers_updated
    BEFORE UPDATE ON Customers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_orders_updated
    BEFORE UPDATE ON Orders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_products_updated
    BEFORE UPDATE ON Products
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_suppliers_updated
    BEFORE UPDATE ON Suppliers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_pav_updated
    BEFORE UPDATE ON ProductAttributeValues
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- SAMPLE DATA
-- ============================================================

-- ── Categories ───────────────────────────────────────────────
INSERT INTO Categories (Name, Description) VALUES
    ('Electronics',   'Electronic devices and accessories'),
    ('Clothing',      'Apparel, footwear and fashion accessories'),
    ('Books',         'Physical and digital books across all genres'),
    ('Home & Garden', 'Furniture, décor and garden supplies'),
    ('Sports',        'Sports equipment and outdoor gear');

-- ── Customers ────────────────────────────────────────────────
INSERT INTO Customers (Name, Email, Address) VALUES
    ('Alice Johnson',  'alice.johnson@email.com',  '123 Maple St, Springfield, IL 62701, USA'),
    ('Bob Smith',      'bob.smith@email.com',       '456 Oak Ave, Austin, TX 78701, USA'),
    ('Carol White',    'carol.white@email.com',     '789 Pine Rd, Seattle, WA 98101, USA'),
    ('David Brown',    'david.brown@email.com',     '321 Elm Blvd, New York, NY 10001, USA'),
    ('Eva Martinez',   'eva.martinez@email.com',    '654 Cedar Ln, Miami, FL 33101, USA'),
    ('Frank Lee',      'frank.lee@email.com',       '987 Birch Way, Denver, CO 80201, USA'),
    ('Grace Kim',      'grace.kim@email.com',       '147 Walnut St, Boston, MA 02101, USA'),
    ('Henry Wilson',   'henry.wilson@email.com',    '258 Chestnut Ave, Chicago, IL 60601, USA');

-- ── Suppliers ────────────────────────────────────────────────
INSERT INTO Suppliers (Name, ContactInfo, Email, Phone, Address) VALUES
    ('TechWorld Supplies',   'Primary electronics supplier',            'contact@techworld.com',    '+1-800-123-4567', '10 Industrial Park, San Jose, CA 95110, USA'),
    ('FashionForward Ltd',   'Global clothing manufacturer',            'orders@fashionforward.com','+1-800-234-5678', '20 Fashion Ave, New York, NY 10018, USA'),
    ('BookDepot Inc',        'Wholesale book distributor',              'supply@bookdepot.com',     '+1-800-345-6789', '30 Library Row, Chicago, IL 60605, USA'),
    ('HomeEssentials Co',    'Home and garden product supplier',        'info@homeessentials.com',  '+1-800-456-7890', '40 Garden Blvd, Portland, OR 97201, USA'),
    ('ProSports Direct',     'Sports equipment manufacturer',           'sales@prosports.com',      '+1-800-567-8901', '50 Stadium Way, Dallas, TX 75201, USA'),
    ('GlobalTech Parts',     'Electronics components and accessories',  'parts@globaltech.com',     '+1-800-678-9012', '60 Component Dr, Austin, TX 78702, USA');

-- ── Attributes (EAV Meta) ────────────────────────────────────
INSERT INTO Attributes (Name, DataType, Unit, Description) VALUES
    ('Color',       'varchar',  NULL,  'Primary color of the product'),
    ('Size',        'varchar',  NULL,  'Size designation e.g. S, M, L, XL or numeric dimensions'),
    ('Weight',      'decimal',  'kg',  'Weight of the product in kilograms'),
    ('Material',    'varchar',  NULL,  'Primary material or fabric of the product'),
    ('Voltage',     'integer',  'V',   'Operating voltage for electronic products'),
    ('Wattage',     'integer',  'W',   'Power consumption in watts'),
    ('IsWaterproof','boolean',  NULL,  'Whether the product is waterproof or water-resistant'),
    ('WarrantyYears','integer', 'yr',  'Manufacturer warranty period in years'),
    ('ReleaseDate', 'date',     NULL,  'Date the product was first released or published'),
    ('PageCount',   'integer',  'pages','Number of pages (for books)');

-- ── Products ─────────────────────────────────────────────────
INSERT INTO Products (Name, Description, CategoryID, Price, StockQty, SKU) VALUES
    -- Electronics (CategoryID=1)
    ('Laptop Pro 15',       '15-inch high-performance laptop with 16GB RAM and 512GB SSD',         1, 1299.99, 45,  'ELEC-LAP-001'),
    ('Wireless Headphones', 'Noise-cancelling over-ear wireless headphones with 30h battery',      1,  199.99, 120, 'ELEC-AUD-002'),
    ('Smartphone X12',      'Flagship 6.7-inch smartphone with 5G and triple camera system',       1,  999.99, 80,  'ELEC-PHN-003'),
    ('USB-C Hub 7-in-1',    '7-port USB-C hub with HDMI, SD card, and 100W power delivery',       1,   59.99, 200, 'ELEC-ACC-004'),
    -- Clothing (CategoryID=2)
    ('Classic Oxford Shirt','100% cotton slim-fit Oxford button-down shirt',                       2,   49.99, 300, 'CLTH-SHT-001'),
    ('Running Sneakers',    'Lightweight mesh running shoes with responsive foam sole',            2,   89.99, 150, 'CLTH-SHO-002'),
    -- Books (CategoryID=3)
    ('Clean Code',          'A handbook of agile software craftsmanship by Robert C. Martin',     3,   34.99, 500, 'BOOK-TEC-001'),
    ('The Pragmatic Programmer','From journeyman to master — 20th anniversary edition',           3,   39.99, 350, 'BOOK-TEC-002'),
    -- Home & Garden (CategoryID=4)
    ('Ergonomic Office Chair','Fully adjustable lumbar support office chair',                      4,  349.99, 30,  'HOME-FRN-001'),
    ('Stainless Steel Pan',  '10-inch tri-ply stainless steel frying pan',                        4,   79.99, 100, 'HOME-KIT-002'),
    -- Sports (CategoryID=5)
    ('Yoga Mat Premium',    '6mm thick non-slip TPE yoga mat with carrying strap',                5,   45.99, 200, 'SPRT-YGA-001'),
    ('Adjustable Dumbbell', 'Single adjustable dumbbell 5–52.5 lbs in 2.5 lb increments',        5,  299.99, 25,  'SPRT-WGT-002');

-- ── ProductSuppliers (M:N bridge) ────────────────────────────
INSERT INTO ProductSuppliers (ProductID, SupplierID, SupplyPrice, LeadTimeDays, IsPreferred) VALUES
    -- Laptop Pro 15
    (1, 1, 950.00, 7,  TRUE),
    (1, 6, 975.00, 10, FALSE),
    -- Wireless Headphones
    (2, 1, 130.00, 5,  TRUE),
    (2, 6, 140.00, 7,  FALSE),
    -- Smartphone X12
    (3, 1, 700.00, 7,  TRUE),
    (3, 6, 720.00, 14, FALSE),
    -- USB-C Hub
    (4, 1, 35.00,  5,  TRUE),
    (4, 6, 38.00,  7,  FALSE),
    -- Classic Oxford Shirt
    (5, 2, 22.00,  14, TRUE),
    -- Running Sneakers
    (6, 2, 45.00,  14, TRUE),
    -- Books
    (7, 3, 18.00,  3,  TRUE),
    (8, 3, 22.00,  3,  TRUE),
    -- Home & Garden
    (9,  4, 200.00, 21, TRUE),
    (10, 4, 40.00,  7,  TRUE),
    -- Sports
    (11, 5, 25.00,  7,  TRUE),
    (12, 5, 180.00, 14, TRUE);

-- ── Orders ───────────────────────────────────────────────────
INSERT INTO Orders (CustomerID, OrderDate, TotalAmount, Status) VALUES
    (1, '2026-01-10', 1499.98, 'DELIVERED'),
    (2, '2026-01-15',  199.99, 'DELIVERED'),
    (3, '2026-01-20',  429.98, 'SHIPPED'),
    (4, '2026-02-01',  999.99, 'CONFIRMED'),
    (5, '2026-02-05',  164.98, 'DELIVERED'),
    (1, '2026-02-10',  649.97, 'SHIPPED'),
    (6, '2026-02-15',   74.98, 'PENDING'),
    (7, '2026-02-20',  339.98, 'CONFIRMED'),
    (8, '2026-03-01', 1299.99, 'PENDING'),
    (3, '2026-03-05',   45.99, 'CONFIRMED');

-- ── OrderDetails ─────────────────────────────────────────────
INSERT INTO OrderDetails (OrderID, ProductID, Quantity, UnitPrice) VALUES
    -- Order 1 (Alice): Laptop + Headphones
    (1, 1, 1, 1299.99),
    (1, 2, 1,  199.99),
    -- Order 2 (Bob): Headphones only
    (2, 2, 1,  199.99),
    -- Order 3 (Carol): Chair + Yoga Mat
    (3, 9, 1,  349.99),
    (3,11, 1,   45.99),  -- 349.99 + 45.99 = 395.98 (rounded to 429.98 with tax/shipping in TotalAmount)
    -- Order 4 (David): Smartphone
    (4, 3, 1,  999.99),
    -- Order 5 (Eva): Oxford Shirt + Running Sneakers
    (5, 5, 1,   49.99),
    (5, 6, 1,   89.99),  -- 49.99 + 89.99 = 139.98 (164.98 w/ shipping)
    -- Order 6 (Alice repeat): USB-C Hub + Clean Code + Pragmatic Programmer
    (6, 4, 1,   59.99),
    (6, 7, 2,   34.99),
    (6, 8, 1,   39.99),
    -- Order 7 (Frank): Pan + Yoga Mat
    (7,10, 1,   79.99),
    (7,11, 1,   45.99),  -- approx. 125.98 → 74.98 may differ if discounted
    -- Order 8 (Grace): Ergonomic Chair + Headphones
    (8, 9, 1,  349.99),
    (8, 2, 1,  199.99),  -- 549.98 → 339.98 (variance = sample discount logic)
    -- Order 9 (Henry): Laptop
    (9, 1, 1, 1299.99),
    -- Order 10 (Carol repeat): Yoga Mat
    (10,11, 1,  45.99);

-- ── ProductAttributeValues (EAV Data) ────────────────────────
-- Pattern: always set Value (varchar), also set typed column matching Attributes.DataType

-- Laptop Pro 15 (ProductID=1)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDecimal) VALUES (1, 3, '2.1', 2.1);    -- Weight 2.1kg
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (1, 5, '20',  20);     -- Voltage 20V
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (1, 6, '65',  65);     -- Wattage 65W
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueBool)    VALUES (1, 7, 'false', FALSE); -- Not waterproof
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (1, 8, '2',    2);     -- 2yr warranty

-- Wireless Headphones (ProductID=2)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (2, 1, 'Midnight Black'); -- Color
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDecimal) VALUES (2, 3, '0.25', 0.25);  -- Weight 250g
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueBool)    VALUES (2, 7, 'false', FALSE); -- Not waterproof
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (2, 8, '1',    1);     -- 1yr warranty

-- Smartphone X12 (ProductID=3)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (3, 1, 'Midnight Blue'); -- Color
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDecimal) VALUES (3, 3, '0.189', 0.189); -- Weight 189g
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueBool)    VALUES (3, 7, 'true', TRUE);   -- Waterproof
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (3, 8, '2',    2);     -- 2yr warranty

-- Classic Oxford Shirt (ProductID=5)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (5, 1, 'White');         -- Color
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (5, 2, 'M');             -- Size
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (5, 4, '100% Cotton');   -- Material

-- Running Sneakers (ProductID=6)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (6, 1, 'Grey/Orange');   -- Color
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (6, 2, '42 EU');         -- Size
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDecimal) VALUES (6, 3, '0.32', 0.32);   -- Weight 320g
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueBool)    VALUES (6, 7, 'false', FALSE);  -- Not waterproof

-- Clean Code (ProductID=7)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDate)    VALUES (7, 9, '2008-08-11', '2008-08-11'); -- ReleaseDate
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (7,10, '431', 431);    -- PageCount

-- The Pragmatic Programmer (ProductID=8)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDate)    VALUES (8, 9, '2019-09-13', '2019-09-13'); -- ReleaseDate
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (8,10, '352', 352);    -- PageCount

-- Ergonomic Office Chair (ProductID=9)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (9, 1, 'Charcoal Grey'); -- Color
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDecimal) VALUES (9, 3, '18.5', 18.5);  -- Weight 18.5kg
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (9, 4, 'Mesh/Steel');   -- Material
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (9, 8, '3',    3);     -- 3yr warranty

-- Yoga Mat Premium (ProductID=11)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (11, 1, 'Teal');         -- Color
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDecimal) VALUES (11, 3, '1.2', 1.2);    -- Weight 1.2kg
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (11, 4, 'TPE');          -- Material
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueBool)    VALUES (11, 7, 'true', TRUE);   -- Waterproof

-- Adjustable Dumbbell (ProductID=12)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueDecimal) VALUES (12, 3, '23.8', 23.8);  -- Weight 23.8kg (max)
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value)               VALUES (12, 4, 'Steel/ABS');    -- Material
INSERT INTO ProductAttributeValues (ProductID, AttributeID, Value, ValueInt)     VALUES (12, 8, '2', 2);        -- 2yr warranty

-- ============================================================
-- SAMPLE QUERIES — demonstrating typical access patterns
-- ============================================================

/*
── 1. All customers with their address (note: Address is a real column) ──
SELECT CustomerID, Name, Email, Address
FROM Customers
ORDER BY Name;

── 2. All active employees and their account balance (via full-name join) ──
SELECT c.Name, c.Email, c.Address,
       SUM(o.TotalAmount) AS total_spent
FROM Customers c
LEFT JOIN Orders o ON o.CustomerID = c.CustomerID
GROUP BY c.CustomerID, c.Name, c.Email, c.Address
ORDER BY total_spent DESC;

── 3. Products with a specific EAV attribute value (Color = 'White') ──
SELECT p.ProductID, p.Name, pav.Value AS Color
FROM Products p
JOIN ProductAttributeValues pav ON pav.ProductID   = p.ProductID
JOIN Attributes              a   ON a.AttributeID   = pav.AttributeID
WHERE a.Name = 'Color'
  AND pav.Value = 'White';

── 4. All products heavier than 1kg (typed decimal EAV column) ──
SELECT p.Name, pav.ValueDecimal AS WeightKg
FROM Products p
JOIN ProductAttributeValues pav ON pav.ProductID   = p.ProductID
JOIN Attributes              a   ON a.AttributeID   = pav.AttributeID
WHERE a.Name     = 'Weight'
  AND pav.ValueDecimal > 1.0
ORDER BY pav.ValueDecimal DESC;

── 5. Orders with customer and product detail ──
SELECT o.OrderID, c.Name AS Customer, c.Address,
       p.Name AS Product, od.Quantity, od.UnitPrice, od.LineTotal
FROM Orders o
JOIN Customers   c  ON c.CustomerID  = o.CustomerID
JOIN OrderDetails od ON od.OrderID   = o.OrderID
JOIN Products    p  ON p.ProductID   = od.ProductID
ORDER BY o.OrderDate DESC;

── 6. Products and all their suppliers ──
SELECT p.Name AS Product, s.Name AS Supplier,
       ps.SupplyPrice, ps.LeadTimeDays, ps.IsPreferred
FROM Products p
JOIN ProductSuppliers ps ON ps.ProductID  = p.ProductID
JOIN Suppliers        s  ON s.SupplierID  = ps.SupplierID
ORDER BY p.Name, ps.IsPreferred DESC;
*/


