-- Add user_addresses table
CREATE TABLE IF NOT EXISTS `user_addresses` (
  `address_id`    char(36) NOT NULL DEFAULT (uuid()),
  `user_id`       char(36) NOT NULL,
  `address_type`  enum('MAILING','BILLING','HOME') DEFAULT 'MAILING',
  `address_line1` varchar(255) NOT NULL,
  `address_line2` varchar(255) DEFAULT NULL,
  `city`          varchar(100) NOT NULL,
  `state`         varchar(50) NOT NULL,
  `zip`           varchar(20) NOT NULL,
  `country`       varchar(50) NOT NULL DEFAULT 'US',
  `is_primary`    tinyint(1) DEFAULT 0,
  `created_at`    datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`address_id`),
  KEY `idx_addresses_user` (`user_id`),
  CONSTRAINT `addresses_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Add email_queue columns if missing
ALTER TABLE email_queue 
  ADD COLUMN IF NOT EXISTS `to_user_id` char(36) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `ride_id` char(36) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `subject` varchar(255) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `html_body` text DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `status` enum('pending','sent','failed') DEFAULT 'pending',
  ADD COLUMN IF NOT EXISTS `queue_id` char(36) DEFAULT NULL;
