CREATE TABLE investigation (
  id uuid PRIMARY KEY,
  request_key varchar(100) NOT NULL UNIQUE,
  status varchar(40) NOT NULL,
  version bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  payload jsonb NOT NULL
);
CREATE INDEX investigation_queue ON investigation(status, created_at);
CREATE UNIQUE INDEX investigation_approval_key ON investigation((payload->>'approvalKey')) WHERE payload->>'approvalKey' <> '';
CREATE TABLE model_daily (day date PRIMARY KEY,attempts integer NOT NULL CHECK(attempts>0));
CREATE TABLE knowledge_chunk (
  id varchar(64) PRIMARY KEY,path text NOT NULL,heading text NOT NULL,
  first_line integer NOT NULL,last_line integer NOT NULL,revision varchar(40) NOT NULL,
  sha256 varchar(64) NOT NULL,body text NOT NULL,
  search_es tsvector GENERATED ALWAYS AS (setweight(to_tsvector('spanish',heading),'A')||setweight(to_tsvector('spanish',body),'B')) STORED,
  search_tech tsvector GENERATED ALWAYS AS (setweight(to_tsvector('simple',heading),'A')||setweight(to_tsvector('simple',body),'B')) STORED
);
CREATE INDEX knowledge_spanish ON knowledge_chunk USING gin(search_es);
CREATE INDEX knowledge_technical ON knowledge_chunk USING gin(search_tech);
