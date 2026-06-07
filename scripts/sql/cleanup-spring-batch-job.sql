-- Row-level cleanup of Spring Batch meta state for a single job.
--
-- Usage:
--   PGPASSWORD=... psql -h <host> -U <user> -d <db> \
--       -v job_name='cleanupUnpublishedPostsJob' -v ON_ERROR_STOP=1 \
--       -f cleanup-spring-batch-job.sql
--
-- Deletes only the rows that belong to the named job, in the order
-- required by the foreign-key relationships between the 6 meta tables:
--
--   batch_step_execution_context   -- step context  (FK -> step_execution)
--   batch_step_execution           -- per-step state (FK -> job_execution)
--   batch_job_execution_context    -- job context   (FK -> job_execution)
--   batch_job_execution_params     -- job params    (FK -> job_execution)
--   batch_job_execution            -- per-launch    (FK -> job_instance)
--   batch_job_instance             -- per (job, parameters)
--
-- The tables themselves are NOT dropped, and rows belonging to OTHER
-- jobs are not touched. This is safe to run in a shared cluster.
--
-- Wrapped in a transaction so a mid-script failure leaves the
-- database in the same state as before the script ran.

\echo --- Spring Batch state for job :job_name (before) ---
SELECT
  (SELECT COUNT(*) FROM batch_job_instance           WHERE job_name = :'job_name') AS job_instance,
  (SELECT COUNT(*) FROM batch_job_execution          WHERE job_instance_id IN (SELECT job_instance_id FROM batch_job_instance WHERE job_name = :'job_name')) AS job_execution,
  (SELECT COUNT(*) FROM batch_job_execution_params   WHERE job_execution_id IN (SELECT je.job_execution_id FROM batch_job_execution je JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS job_execution_params,
  (SELECT COUNT(*) FROM batch_job_execution_context  WHERE job_execution_id IN (SELECT je.job_execution_id FROM batch_job_execution je JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS job_execution_context,
  (SELECT COUNT(*) FROM batch_step_execution         WHERE job_execution_id IN (SELECT je.job_execution_id FROM batch_job_execution je JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS step_execution,
  (SELECT COUNT(*) FROM batch_step_execution_context WHERE step_execution_id IN (SELECT se.step_execution_id FROM batch_step_execution se JOIN batch_job_execution je ON je.job_execution_id = se.job_execution_id JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS step_execution_context;

BEGIN;

DELETE FROM batch_step_execution_context
WHERE step_execution_id IN (
    SELECT se.step_execution_id
    FROM batch_step_execution se
    JOIN batch_job_execution je ON je.job_execution_id = se.job_execution_id
    JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
    WHERE ji.job_name = :'job_name'
);

DELETE FROM batch_step_execution
WHERE job_execution_id IN (
    SELECT je.job_execution_id
    FROM batch_job_execution je
    JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
    WHERE ji.job_name = :'job_name'
);

DELETE FROM batch_job_execution_context
WHERE job_execution_id IN (
    SELECT je.job_execution_id
    FROM batch_job_execution je
    JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
    WHERE ji.job_name = :'job_name'
);

DELETE FROM batch_job_execution_params
WHERE job_execution_id IN (
    SELECT je.job_execution_id
    FROM batch_job_execution je
    JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
    WHERE ji.job_name = :'job_name'
);

DELETE FROM batch_job_execution
WHERE job_instance_id IN (
    SELECT job_instance_id FROM batch_job_instance WHERE job_name = :'job_name'
);

DELETE FROM batch_job_instance WHERE job_name = :'job_name';

COMMIT;

\echo --- Spring Batch state for job :job_name (after) ---
SELECT
  (SELECT COUNT(*) FROM batch_job_instance           WHERE job_name = :'job_name') AS job_instance,
  (SELECT COUNT(*) FROM batch_job_execution          WHERE job_instance_id IN (SELECT job_instance_id FROM batch_job_instance WHERE job_name = :'job_name')) AS job_execution,
  (SELECT COUNT(*) FROM batch_job_execution_params   WHERE job_execution_id IN (SELECT je.job_execution_id FROM batch_job_execution je JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS job_execution_params,
  (SELECT COUNT(*) FROM batch_job_execution_context  WHERE job_execution_id IN (SELECT je.job_execution_id FROM batch_job_execution je JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS job_execution_context,
  (SELECT COUNT(*) FROM batch_step_execution         WHERE job_execution_id IN (SELECT je.job_execution_id FROM batch_job_execution je JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS step_execution,
  (SELECT COUNT(*) FROM batch_step_execution_context WHERE step_execution_id IN (SELECT se.step_execution_id FROM batch_step_execution se JOIN batch_job_execution je ON je.job_execution_id = se.job_execution_id JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id WHERE ji.job_name = :'job_name')) AS step_execution_context;
