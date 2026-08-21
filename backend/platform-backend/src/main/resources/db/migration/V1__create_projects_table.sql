CREATE TABLE projects (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          name VARCHAR(255) NOT NULL,
                          github_repo_url VARCHAR(500) NOT NULL,
                          branch VARCHAR(100) NOT NULL DEFAULT 'main',
                          status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                          created_at TIMESTAMP NOT NULL DEFAULT now(),
                          updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_name ON projects(name);