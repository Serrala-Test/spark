-- EqualTo
select 1 = 1;
select 1 = '1';
select 1.0 = '1';
select 1.5 = '1.51';

-- GreaterThan
select 1 > '1';
select 2 > '1.0';
select 2 > '2.0';
select 2 > '2.2';
select '1.5' > 0.5;
select to_date('2009-07-30 04:17:52') > to_date('2009-07-30 04:17:52');
select to_date('2009-07-30 04:17:52') > '2009-07-30 04:17:52';
 
-- GreaterThanOrEqual
select 1 >= '1';
select 2 >= '1.0';
select 2 >= '2.0';
select 2.0 >= '2.2';
select '1.5' >= 0.5;
select to_date('2009-07-30 04:17:52') >= to_date('2009-07-30 04:17:52');
select to_date('2009-07-30 04:17:52') >= '2009-07-30 04:17:52';
 
-- LessThan
select 1 < '1';
select 2 < '1.0';
select 2 < '2.0';
select 2.0 < '2.2';
select 0.5 < '1.5';
select to_date('2009-07-30 04:17:52') < to_date('2009-07-30 04:17:52');
select to_date('2009-07-30 04:17:52') < '2009-07-30 04:17:52';
 
-- LessThanOrEqual
select 1 <= '1';
select 2 <= '1.0';
select 2 <= '2.0';
select 2.0 <= '2.2';
select 0.5 <= '1.5';
select to_date('2009-07-30 04:17:52') <= to_date('2009-07-30 04:17:52');
select to_date('2009-07-30 04:17:52') <= '2009-07-30 04:17:52';

-- SPARK-23549: Cast to timestamp when comparing timestamp with date
select to_date('2017-03-01') = to_timestamp('2017-03-01 00:00:00');
select to_timestamp('2017-03-01 00:00:01') > to_date('2017-03-01');
select to_timestamp('2017-03-01 00:00:01') >= to_date('2017-03-01');
select to_date('2017-03-01') < to_timestamp('2017-03-01 00:00:01');
select to_date('2017-03-01') <= to_timestamp('2017-03-01 00:00:01');

-- In
select 1 in (1, 2, 3);
select 1 in (1, 2, 3, null);
select 1 in (1.0, 2.0, 3.0);
select 1 in (1.0, 2.0, 3.0, null);
select 1 in ('2', '3', '4');
select 1 in ('2', '3', '4', null);
select null in (1, 2, 3);
select null in (1, 2, null);

-- Not(In)
select 1 not in (1, 2, 3);
select 1 not in (1, 2, 3, null);
select 1 not in (1.0, 2.0, 3.0);
select 1 not in (1.0, 2.0, 3.0, null);
select 1 not in ('2', '3', '4');
select 1 not in ('2', '3', '4', null);
select null not in (1, 2, 3);
select null not in (1, 2, null);
