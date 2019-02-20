/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.parser

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.plans.SQLHelper
import org.apache.spark.sql.internal.SQLConf

class TableIdentifierParserSuite extends SparkFunSuite with SQLHelper {
  import CatalystSqlParser._

  // Add "$elem$", "$value$" & "$key$"
  val hiveNonReservedKeywords = Seq("add", "admin", "after", "analyze", "archive", "asc", "before",
    "bucket", "buckets", "cascade", "change", "cluster", "clustered", "clusterstatus", "collection",
    "columns", "comment", "compact", "compactions", "compute", "concatenate", "continue", "cost",
    "data", "day", "databases", "datetime", "dbproperties", "deferred", "defined", "delimited",
    "dependency", "desc", "directories", "directory", "disable", "distribute",
    "enable", "escaped", "exclusive", "explain", "export", "fields", "file", "fileformat", "first",
    "format", "formatted", "functions", "hold_ddltime", "hour", "idxproperties", "ignore", "index",
    "indexes", "inpath", "inputdriver", "inputformat", "items", "jar", "keys", "key_type", "last",
    "limit", "offset", "lines", "load", "location", "lock", "locks", "logical", "long", "mapjoin",
    "materialized", "metadata", "minus", "minute", "month", "msck", "noscan", "no_drop", "nulls",
    "offline", "option", "outputdriver", "outputformat", "overwrite", "owner", "partitioned",
    "partitions", "plus", "pretty", "principals", "protection", "purge", "read", "readonly",
    "rebuild", "recordreader", "recordwriter", "reload", "rename", "repair", "replace",
    "replication", "restrict", "rewrite", "role", "roles", "schemas", "second",
    "serde", "serdeproperties", "server", "sets", "shared", "show", "show_database", "skewed",
    "sort", "sorted", "ssl", "statistics", "stored", "streamtable", "string", "struct", "tables",
    "tblproperties", "temporary", "terminated", "tinyint", "touch", "transactions", "unarchive",
    "undo", "uniontype", "unlock", "unset", "unsigned", "uri", "use", "utc", "utctimestamp",
    "view", "while", "year", "work", "transaction", "write", "isolation", "level", "snapshot",
    "autocommit", "all", "any", "alter", "array", "as", "authorization", "between", "bigint",
    "binary", "boolean", "both", "by", "create", "cube", "current_date", "current_timestamp",
    "cursor", "date", "decimal", "delete", "describe", "double", "drop", "exists", "external",
    "false", "fetch", "float", "for", "grant", "group", "grouping", "import", "in",
    "insert", "int", "into", "is", "pivot", "lateral", "like", "local", "none", "null",
    "of", "order", "out", "outer", "partition", "percent", "procedure", "range", "reads", "revoke",
    "rollup", "row", "rows", "set", "smallint", "table", "timestamp", "to", "trigger",
    "true", "truncate", "update", "user", "values", "with", "regexp", "rlike",
    "bigint", "binary", "boolean", "current_date", "current_timestamp", "date", "double", "float",
    "int", "smallint", "timestamp", "at", "position", "both", "leading", "trailing", "extract")

  val hiveStrictNonReservedKeywords = Seq("anti", "full", "inner", "left", "semi", "right",
    "natural", "union", "intersect", "except", "database", "on", "join", "cross", "select", "from",
    "where", "having", "from", "to", "table", "with", "not")

  // All the keywords in `docs/sql-reserved-and-non-reserved-key-words.md` are listed below:
  val allCandidateKeywords = Set("abs", "absolute", "acos", "action", "add", "after",
    "all", "allocate", "alter", "analyze", "and", "anti", "any", "archive", "are", "array",
    "array_agg", "array_max_cardinality", "as", "asc", "asensitive", "asin", "assertion",
    "asymmetric", "at", "atan", "atomic", "authorization", "avg", "before", "begin", "begin_frame",
    "begin_partition", "between", "bigint", "binary", "bit", "bit_length", "blob", "boolean",
    "both", "breadth", "bucket", "buckets", "by", "cache", "call", "called", "cardinality",
    "cascade", "cascaded", "case", "cast", "catalog", "ceil", "ceiling", "change", "char",
    "char_length", "character", "character_length", "check", "classifier", "clear", "clob", "close",
    "cluster", "clustered", "coalesce", "codegen", "collate", "collation", "collect", "collection",
    "column", "columns", "comment", "commit", "compact", "compactions", "compute", "concatenate",
    "condition", "connect", "connection", "constraint", "constraints", "constructor", "contains",
    "continue", "convert", "copy", "corr", "corresponding", "cos", "cosh", "cost", "count",
    "covar_pop", "covar_samp", "create", "cross", "cube", "cume_dist", "current", "current_catalog",
    "current_date", "current_default_transform_group", "current_path", "current_role",
    "current_row", "current_schema", "current_time", "current_timestamp",
    "current_transform_group_for_type", "current_user", "cursor", "cycle", "data", "database",
    "databases", "date", "day", "dbproperties", "deallocate", "dec", "decfloat", "decimal",
    "declare", "default", "deferrable", "deferred", "define", "defined", "delete", "delimited",
    "dense_rank", "depth", "deref", "desc", "describe", "descriptor", "deterministic", "dfs",
    "diagnostics", "directories", "directory", "disconnect", "distinct", "distribute", "div", "do",
    "domain", "double", "drop", "dynamic", "each", "element", "else", "elseif", "empty", "end",
    "end_frame", "end_partition", "equals", "escape", "escaped", "every", "except", "exception",
    "exchange", "exec", "execute", "exists", "exit", "exp", "explain", "export", "extended",
    "external", "extract", "false", "fetch", "fields", "fileformat", "filter", "first",
    "first_value", "float", "following", "for", "foreign", "format", "formatted", "found",
    "frame_row", "free", "from", "full", "function", "functions", "fusion", "general", "get",
    "global", "go", "goto", "grant", "group", "grouping", "groups", "handler", "having", "hold",
    "hour", "identity", "if", "ignore", "immediate", "import", "in", "index", "indexes",
    "indicator", "initial", "initially", "inner", "inout", "inpath", "input", "inputformat",
    "insensitive", "insert", "int", "integer", "intersect", "intersection", "interval", "into",
    "is", "isolation", "items", "iterate", "join", "json_array", "json_arrayagg", "json_exists",
    "json_object", "json_objectagg", "json_query", "json_table", "json_table_primitive",
    "json_value", "key", "keys", "lag", "language", "large", "last", "last_value", "lateral",
    "lazy", "lead", "leading", "leave", "left", "level", "like", "like_regex", "limit", "lines",
    "list", "listagg", "ln", "load", "local", "localtime", "localtimestamp", "location",
    "locator", "lock", "locks", "log", "log10", "logical", "loop", "lower", "macro", "map",
    "match", "match_number", "match_recognize", "matches", "max", "member", "merge", "method",
    "min", "minute", "mod", "modifies", "module", "month", "msck", "multiset", "names",
    "national", "natural", "nchar", "nclob", "new", "next", "no", "none", "normalize",
    "not", "nth_value", "ntile", "null", "nullif", "nulls", "numeric", "object",
    "occurrences_regex", "octet_length", "of", "offset", "old", "omit", "on", "one", "only", "open",
    "option", "options", "or", "order", "ordinality", "out", "outer", "output", "outputformat",
    "over", "overlaps", "overlay", "overwrite", "pad", "parameter", "partial", "partition",
    "partitioned", "partitions", "path", "pattern", "per", "percent", "percent_rank",
    "percentile_cont", "percentile_disc", "percentlit", "period", "pivot", "portion", "power",
    "precedes", "preceding", "precision", "prepare", "preserve", "primary", "principals", "prior",
    "privileges", "procedure", "ptf", "public", "purge", "range", "rank", "read", "reads", "real",
    "recordreader", "recordwriter", "recover", "recursive", "reduce", "ref", "references",
    "referencing", "refresh", "regr_avgx", "regr_avgy", "regr_count", "regr_intercept", "regr_r2",
    "regr_slope", "regr_sxx", "regr_sxy", "regr_syy", "relative", "release", "rename", "repair",
    "repeat", "replace", "reset", "resignal", "restrict", "result", "return", "returns", "revoke",
    "right", "rlike", "role", "roles", "rollback", "rollup", "routine", "row", "row_number", "rows",
    "running", "savepoint", "schema", "scope", "scroll", "search", "second", "section", "seek",
    "select", "semi", "sensitive", "separated", "serde", "serdeproperties", "session",
    "session_user", "set", "setminus", "sets", "show", "signal", "similar", "sin", "sinh", "size",
    "skewed", "skip", "smallint", "some", "sort", "sorted", "space", "specific", "specifictype",
    "sql", "sqlcode", "sqlerror", "sqlexception", "sqlstate", "sqlwarning", "sqrt", "start",
    "state", "static", "statistics", "stddev_pop", "stddev_samp", "stored", "stratify", "struct",
    "submultiset", "subset", "substring", "substring_regex", "succeeds", "sum", "symmetric",
    "system", "system_time", "system_user", "table", "tables", "tablesample", "tan", "tanh",
    "tblproperties", "temporary", "terminated", "then", "time", "timestamp", "timezone_hour",
    "timezone_minute", "to", "touch", "trailing", "transaction", "transactions", "transform",
    "translate", "translate_regex", "translation", "treat", "trigger", "trim", "trim_array", "true",
    "truncate", "uescape", "unarchive", "unbounded", "uncache", "under", "undo", "union", "unique",
    "unknown", "unlock", "unnest", "unset", "until", "update", "upper", "usage", "use", "user",
    "using", "value", "value_of", "values", "var_pop", "var_samp", "varbinary", "varchar",
    "varying", "versioning", "view", "when", "whenever", "where", "while", "width_bucket", "window",
    "with", "within", "without", "work", "write", "year", "zone")

  val reservedKeywordsInAnsiMode = Set("all", "and", "any", "as", "authorization", "both", "case",
    "cast", "check", "collate", "column", "constraint", "create", "cross", "current_date",
    "current_time", "current_timestamp", "current_user", "distinct", "else", "end", "except",
    "false", "fetch", "for", "foreign", "from", "full", "grant", "group", "having", "in", "inner",
    "intersect", "into", "is", "join", "leading", "left", "natural", "not", "null", "on", "only",
    "or", "order", "outer", "overlaps", "primary", "references", "right", "select", "session_user",
    "some", "table", "then", "to", "trailing", "union", "unique", "user", "using", "when",
    "where", "with")

  val nonReservedKeywordsInAnsiMode = allCandidateKeywords -- reservedKeywordsInAnsiMode

  val nonReservedKeywordsInNonAnsiMode = allCandidateKeywords -- Set("using")

  test("table identifier") {
    // Regular names.
    assert(TableIdentifier("q") === parseTableIdentifier("q"))
    assert(TableIdentifier("q", Option("d")) === parseTableIdentifier("d.q"))

    // Illegal names.
    Seq("", "d.q.g", "t:", "${some.var.x}", "tab:1").foreach { identifier =>
      intercept[ParseException](parseTableIdentifier(identifier))
    }
  }

  test("quoted identifiers") {
    assert(TableIdentifier("z", Some("x.y")) === parseTableIdentifier("`x.y`.z"))
    assert(TableIdentifier("y.z", Some("x")) === parseTableIdentifier("x.`y.z`"))
    assert(TableIdentifier("z", Some("`x.y`")) === parseTableIdentifier("```x.y```.z"))
    assert(TableIdentifier("`y.z`", Some("x")) === parseTableIdentifier("x.```y.z```"))
    assert(TableIdentifier("x.y.z", None) === parseTableIdentifier("`x.y.z`"))
  }

  test("table identifier - reserved/non-reserved keywords if ANSI mode enabled") {
    withSQLConf(SQLConf.ANSI_SQL_PARSER.key -> "true") {
      reservedKeywordsInAnsiMode.foreach { keyword =>
        val errMsg = intercept[ParseException] {
          parseTableIdentifier(keyword)
        }.getMessage
        assert(errMsg.contains(s"'$keyword' is reserved and you cannot use this keyword " +
          "as an identifier."))
        assert(TableIdentifier(keyword) === parseTableIdentifier(s"`$keyword`"))
        assert(TableIdentifier(keyword, Option("db")) === parseTableIdentifier(s"db.`$keyword`"))
      }
      nonReservedKeywordsInAnsiMode.foreach { keyword =>
        assert(TableIdentifier(keyword) === parseTableIdentifier(s"$keyword"))
        assert(TableIdentifier(keyword, Option("db")) === parseTableIdentifier(s"db.$keyword"))
      }
    }
  }

  test("table identifier - strict keywords") {
    // SQL Keywords.
    hiveStrictNonReservedKeywords.foreach { keyword =>
      assert(TableIdentifier(keyword) === parseTableIdentifier(keyword))
      assert(TableIdentifier(keyword) === parseTableIdentifier(s"`$keyword`"))
      assert(TableIdentifier(keyword, Option("db")) === parseTableIdentifier(s"db.`$keyword`"))
    }
  }

  test("table identifier - non reserved keywords") {
    nonReservedKeywordsInNonAnsiMode.foreach { nonReserved =>
      assert(TableIdentifier(nonReserved) === parseTableIdentifier(nonReserved))
    }

    // Additionally checks if Hive keywords can be allowed.
    hiveNonReservedKeywords.foreach { nonReserved =>
      assert(TableIdentifier(nonReserved) === parseTableIdentifier(nonReserved))
    }
  }

  test("SPARK-17364 table identifier - contains number") {
    assert(parseTableIdentifier("123_") == TableIdentifier("123_"))
    assert(parseTableIdentifier("1a.123_") == TableIdentifier("123_", Some("1a")))
    // ".123" should not be treated as token of type DECIMAL_VALUE
    assert(parseTableIdentifier("a.123A") == TableIdentifier("123A", Some("a")))
    // ".123E3" should not be treated as token of type SCIENTIFIC_DECIMAL_VALUE
    assert(parseTableIdentifier("a.123E3_LIST") == TableIdentifier("123E3_LIST", Some("a")))
    // ".123D" should not be treated as token of type DOUBLE_LITERAL
    assert(parseTableIdentifier("a.123D_LIST") == TableIdentifier("123D_LIST", Some("a")))
    // ".123BD" should not be treated as token of type BIGDECIMAL_LITERAL
    assert(parseTableIdentifier("a.123BD_LIST") == TableIdentifier("123BD_LIST", Some("a")))
  }

  test("SPARK-17832 table identifier - contains backtick") {
    val complexName = TableIdentifier("`weird`table`name", Some("`d`b`1"))
    assert(complexName === parseTableIdentifier("```d``b``1`.```weird``table``name`"))
    assert(complexName === parseTableIdentifier(complexName.quotedString))
    intercept[ParseException](parseTableIdentifier(complexName.unquotedString))
    // Table identifier contains countious backticks should be treated correctly.
    val complexName2 = TableIdentifier("x``y", Some("d``b"))
    assert(complexName2 === parseTableIdentifier(complexName2.quotedString))
  }
}
