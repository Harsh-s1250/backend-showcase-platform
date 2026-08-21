CREATE TABLE deployment_logs (
                                 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                                 log_type VARCHAR(20) NOT NULL,
                                 content TEXT NOT NULL,
                                 success BOOLEAN,
                                 created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_deployment_logs_project_id ON deployment_logs(project_id);