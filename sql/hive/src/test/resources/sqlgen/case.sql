-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT CASE WHEN id % 2 > 0 THEN 0 WHEN id % 2 = 0 THEN 1 END FROM parquet_t0
--------------------------------------------------------------------------------
SELECT CASE WHEN ((parquet_t0.`id` % CAST(2 AS BIGINT)) > CAST(0 AS BIGINT)) THEN 0 WHEN ((parquet_t0.`id` % CAST(2 AS BIGINT)) = CAST(0 AS BIGINT)) THEN 1 END AS `CASE WHEN ((id % CAST(2 AS BIGINT)) > CAST(0 AS BIGINT)) THEN 0 WHEN ((id % CAST(2 AS BIGINT)) = CAST(0 AS BIGINT)) THEN 1 END` FROM parquet_t0
