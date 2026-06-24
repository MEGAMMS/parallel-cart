-- Run after the Spring Boot app has started once and Hibernate has created tables.
-- Creates deterministic user IDs 3..202 for JMeter checkout tests.
INSERT INTO users (id, email, password_hash, role, created_at)
SELECT gs,
       'jmeter-user-' || gs || '@parallelcart.local',
       'dev-hash',
       'CUSTOMER',
       now()
FROM generate_series(3, 202) AS gs
ON CONFLICT (id) DO NOTHING;

-- Keep inventory high enough for repeated local test runs.
UPDATE inventory SET available_quantity = 10000;

-- Move users sequence after the explicit IDs, if the table uses a serial/identity sequence.
SELECT setval(pg_get_serial_sequence('users','id'), COALESCE((SELECT MAX(id) FROM users), 1));
