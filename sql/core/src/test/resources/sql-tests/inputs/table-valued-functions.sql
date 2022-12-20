-- unresolved function
select * from dummy(3);

-- range call with end
select * from range(6 + cos(3));

-- range call with start and end
select * from range(5, 10);

-- range call with step
select * from range(0, 10, 2);

-- range call with numPartitions
select * from range(0, 10, 1, 200);

-- range call with invalid number of arguments
select * from range(1, 1, 1, 1, 1);

-- range call with null
select * from range(1, null);

-- range call with incompatible type
select * from range(array(1, 2, 3));

-- range call with illegal step
select * from range(0, 5, 0);

-- range call with a mixed-case function name
select * from RaNgE(2);

-- explode
select * from explode(array(1, 2));
select * from explode(map('a', 1, 'b', 2));

-- explode with empty values
select * from explode(array());
select * from explode(map());

-- explode with column aliases
select * from explode(array(1, 2)) t(c1);
select * from explode(map('a', 1, 'b', 2)) t(k, v);

-- explode with erroneous input
select * from explode(null);
select * from explode(null) t(c1);
select * from explode(1);
select * from explode(1, 2);
select * from explode(explode(array(1)));
select * from explode(array(1, 2)) t(c1, c2);

-- explode_outer
select * from explode_outer(array(1, 2));
select * from explode_outer(map('a', 1, 'b', 2));
select * from explode_outer(array());
select * from explode_outer(map());

-- table-valued functions with join
select * from range(2) join explode(array(1, 2));
select * from range(2) join explode_outer(array());
