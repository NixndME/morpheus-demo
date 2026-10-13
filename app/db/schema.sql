CREATE TABLE IF NOT EXISTS traces (
  id INT AUTO_INCREMENT PRIMARY KEY,
  at DATETIME DEFAULT CURRENT_TIMESTAMP,
  ip VARCHAR(45), browser VARCHAR(80), path VARCHAR(80), status INT,
  total_ms DOUBLE, db_ms DOUBLE, spans TEXT
);
CREATE TABLE IF NOT EXISTS visitors (
  ip VARCHAR(45), browser VARCHAR(80),
  first_seen DATETIME, last_seen DATETIME, hits INT,
  PRIMARY KEY (ip, browser)
);
CREATE TABLE IF NOT EXISTS guestbook (
  id INT AUTO_INCREMENT PRIMARY KEY,
  at DATETIME DEFAULT CURRENT_TIMESTAMP,
  name VARCHAR(60), message VARCHAR(300), ip VARCHAR(45)
);
INSERT INTO guestbook (name, message, ip)
  SELECT 'Morpheus', 'Hello from the database tier.', '' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM guestbook);
