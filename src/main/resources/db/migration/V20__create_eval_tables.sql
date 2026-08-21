CREATE TABLE IF NOT EXISTS eval_cases (
    id                          BIGSERIAL PRIMARY KEY,
    owner_user_id               BIGINT NOT NULL REFERENCES app_users(id),
    case_id                     VARCHAR(128) NOT NULL,
    mode                        VARCHAR(64) NOT NULL,
    type                        VARCHAR(64) NOT NULL,
    suite                       VARCHAR(64),
    layer                       VARCHAR(64),
    question                    VARCHAR(4000) NOT NULL,
    expect_no_answer            BOOLEAN NOT NULL DEFAULT FALSE,
    require_local_evidence     BOOLEAN NOT NULL DEFAULT FALSE,
    allow_model_fallback        BOOLEAN NOT NULL DEFAULT FALSE,
    expected_sources            TEXT NOT NULL,
    expected_heading_paths      TEXT NOT NULL,
    expected_keywords           TEXT NOT NULL,
    forbidden_keywords          TEXT NOT NULL,
    expected_page_numbers       TEXT,
    expected_retrieval_keywords TEXT,
    forbidden_retrieval_keywords TEXT,
    conversation_history        TEXT,
    expected_relation            VARCHAR(32),
    expected_standalone_question VARCHAR(4000),
    expected_retrieval_queries  TEXT
);

CREATE INDEX IF NOT EXISTS idx_eval_cases_owner_id ON eval_cases(owner_user_id);

CREATE TABLE IF NOT EXISTS eval_imports (
    id                  BIGSERIAL PRIMARY KEY,
    owner_user_id       BIGINT NOT NULL REFERENCES app_users(id),
    original_file_name  VARCHAR(255) NOT NULL,
    stored_file_name    VARCHAR(255) NOT NULL,
    content_type        VARCHAR(128) NOT NULL,
    file_size           BIGINT NOT NULL,
    imported_count      INTEGER NOT NULL,
    created_at          TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_imports_owner_created
    ON eval_imports(owner_user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS eval_runs (
    id                          BIGSERIAL PRIMARY KEY,
    owner_user_id               BIGINT REFERENCES app_users(id),
    run_id                      VARCHAR(64) NOT NULL UNIQUE,
    enhanced                    BOOLEAN NOT NULL,
    judge_enabled               BOOLEAN NOT NULL,
    total_cases                 INTEGER NOT NULL,
    passed                      INTEGER NOT NULL,
    failed                      INTEGER NOT NULL,
    pass_rate                   DOUBLE PRECISION NOT NULL,
    retrieval_hit_rate          DOUBLE PRECISION NOT NULL,
    citation_correctness_rate   DOUBLE PRECISION NOT NULL,
    key_point_coverage_rate     DOUBLE PRECISION NOT NULL,
    unsupported_answer_rate     DOUBLE PRECISION NOT NULL,
    model_fallback_rate         DOUBLE PRECISION NOT NULL,
    refusal_correctness_rate    DOUBLE PRECISION NOT NULL,
    ranking_case_count          INTEGER,
    recall_at_5                 DOUBLE PRECISION,
    precision_at_5              DOUBLE PRECISION,
    mrr                         DOUBLE PRECISION,
    ndcg_at_5                   DOUBLE PRECISION,
    gate_enabled                BOOLEAN,
    gate_passed                 BOOLEAN,
    gate_failures               TEXT,
    created_at                  TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_runs_owner_created
    ON eval_runs(owner_user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS eval_run_results (
    id          BIGSERIAL PRIMARY KEY,
    eval_run_id BIGINT NOT NULL REFERENCES eval_runs(id) ON DELETE CASCADE,
    case_id     VARCHAR(128) NOT NULL,
    result_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_run_results_run_id ON eval_run_results(eval_run_id);
