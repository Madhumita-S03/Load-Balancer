-- Stable business IDs are unique; numeric IDs are generated from pooled sequences for efficient inserts.
CREATE SEQUENCE IF NOT EXISTS delivery_order_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS vehicle_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS delivery_orders (
    id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT,
    order_id VARCHAR(100) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    address VARCHAR(500) NOT NULL,
    package_weight DOUBLE PRECISION NOT NULL,
    priority VARCHAR(10) NOT NULL,
    CONSTRAINT uk_delivery_order_business_id UNIQUE (order_id)
);

CREATE INDEX IF NOT EXISTS idx_delivery_order_priority ON delivery_orders(priority);

CREATE TABLE IF NOT EXISTS vehicles (
    id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT,
    vehicle_id VARCHAR(100) NOT NULL,
    capacity DOUBLE PRECISION NOT NULL,
    current_latitude DOUBLE PRECISION NOT NULL,
    current_longitude DOUBLE PRECISION NOT NULL,
    current_address VARCHAR(500) NOT NULL,
    CONSTRAINT uk_vehicle_business_id UNIQUE (vehicle_id)
);
