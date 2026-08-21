CREATE TABLE users (
                       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       github_id BIGINT NOT NULL UNIQUE,
                       github_username VARCHAR(255) NOT NULL,
                       avatar_url VARCHAR(500),
                       access_token VARCHAR(500) NOT NULL,
                       created_at TIMESTAMP NOT NULL DEFAULT now(),
                       updated_at TIMESTAMP NOT NULL DEFAULT now()
);

ALTER TABLE projects ADD COLUMN owner_id UUID REFERENCES users(id);