---
layout: global
title: DROP TABLE
displayTitle: DROP TABLE
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
 
     http://www.apache.org/licenses/LICENSE-2.0
 
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

### Description

Deletes the table and removes the directory associated with this table from the file system if this
is not EXTERNAL table. If the table is not present it throws exception.

In case of External table it only deletes the metadata and it will not remove the directory 
associated with this table.

### Syntax
{% highlight sql %}
DROP TABLE [IF EXISTS] [database_name.]table_name
{% endhighlight %}

### Parameter
<dl>
  <dt><code><em>IF EXISTS</em></code></dt>
  <dd>
     If specified, no exception is thrown when the table does not exist.
  </dd>
  <dt><code><em>database_name</em></code></dt>
  <dd>
     Database name where table is present.
  </dd>
  <dt><code><em>table_name</em></code></dt>
  <dd>
     table name to be dropped.
  </dd>
</dl>

### Example
{% highlight sql %}
-- Assumes a table name `employeetable` exist.
DROP TABLE employeetable;
+---------+--+
| Result  |
+---------+--+
+---------+--+

-- Assumes a table name `employeetable` exist in `userdb` database
DROP TABLE userdb.employeetable;
+---------+--+
| Result  |
+---------+--+
+---------+--+

-- Assumes a table name `employeetable` does not exist.
-- Throws exception
DROP TABLE employeetable;
Error: org.apache.spark.sql.AnalysisException: Table or view not found: employeetable;
(state=,code=0)

-- Assumes a table name `employeetable` does not exist,Try with IF EXISTS
-- this time it will not throw exception
DROP TABLE IF EXISTS employeetable;
+---------+--+
| Result  |
+---------+--+
+---------+--+

{% endhighlight %}

### Related Statements
- [CREATE TABLE ](sql-ref-syntax-ddl-create-table.html)
- [CREATE DATABASE](sql-ref-syntax-ddl-create-database.html)
- [DROP DATABASE](sql-ref-syntax-ddl-drop-database.html)


