-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT EXPLODE(ARRAY(1,2,3)) AS val
--------------------------------------------------------------------------------
SELECT `gen_attr_0` AS `val` FROM (SELECT `gen_attr_0` FROM (SELECT 1) gen_subquery_1 LATERAL VIEW explode(array(1, 2, 3)) gen_subquery_2 AS `gen_attr_0`) AS gen_subquery_0
