-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT x.key FROM parquet_t1 x JOIN parquet_t1 y ON x.key = y.key
--------------------------------------------------------------------------------
SELECT x.`key` FROM (parquet_t1) AS x INNER JOIN (parquet_t1) AS y ON (x.`key` = y.`key`)
