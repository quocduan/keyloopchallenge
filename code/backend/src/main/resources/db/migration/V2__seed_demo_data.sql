INSERT INTO dealership (id, name) VALUES (1, 'Keyloop Demo Motors');
INSERT INTO vehicle (id, vin, make, model, model_year) VALUES
  (1, '1HGCM82633A004352', 'Honda', 'Accord', 2023),
  (2, 'WBA8E9G56GNU12345', 'BMW', '3 Series', 2022);
INSERT INTO service_type (id, name, duration_minutes) VALUES
  (1, 'Routine Service', 60),
  (2, 'Brake Inspection', 90),
  (3, 'Engine Diagnostics', 120);
INSERT INTO technician (id, dealership_id, name, service_type_id) VALUES
  (1, 1, 'Alex Morgan', 1), (2, 1, 'Alex Morgan', 2), (3, 1, 'Alex Morgan', 3),
  (4, 1, 'Jamie Lee', 1), (5, 1, 'Jamie Lee', 2), (6, 1, 'Jamie Lee', 3);
INSERT INTO service_bay (id, dealership_id, name) VALUES
  (1, 1, 'Bay 1'), (2, 1, 'Bay 2');
