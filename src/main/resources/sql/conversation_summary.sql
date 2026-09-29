-- Execute manually in the application database; this project does not run Flyway migrations.
CREATE TABLE IF NOT EXISTS conversation_summary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(128) NOT NULL,
    username VARCHAR(128) NOT NULL,
    summary_text LONGTEXT NOT NULL,
    summarized_message_count INT NOT NULL DEFAULT 0,
    summary_version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_summary_user_conversation (username, conversation_id),
    KEY idx_summary_username_update (username, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
