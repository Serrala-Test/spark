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

package test.org.apache.spark.sql.connector;

import java.util.Arrays;

import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.expressions.*;
import org.apache.spark.sql.connector.read.*;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

public class JavaOrderAndPartitionAwareDataSource extends JavaPartitionAwareDataSource {

  protected InputPartition[] getPartitions() {
    return new InputPartition[]{
      new SpecificInputPartition(new int[]{1, 1, 3}, new int[]{4, 5, 5}),
      new SpecificInputPartition(new int[]{2, 4, 4}, new int[]{6, 1, 2})
    };
  }

  @Override
  public Table getTable(CaseInsensitiveStringMap options) {
    return new JavaSimpleBatchTable() {
      @Override
      public Transform[] partitioning() {
        String partitionKeys = options.get("partitionKeys");
        if (partitionKeys == null) {
          return new Transform[0];
        } else {
          return (Transform[]) Arrays.stream(partitionKeys.split(","))
            .map(Expressions::identity).toArray();
        }
      }

      @Override
      public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) {
        return new JavaOrderAndPartitionAwareScanBuilder(
            getPartitions(), options.get("partitionKeys"), options.get("orderKeys"));
      }
    };
  }

  static class MySortOrder implements SortOrder {
    private final Expression expression;

    MySortOrder(String columnName) {
      this.expression = new MyIdentityTransform(new MyNamedReference(columnName));
    }

    @Override
    public Expression expression() {
      return expression;
    }

    @Override
    public SortDirection direction() {
      return SortDirection.ASCENDING;
    }

    @Override
    public NullOrdering nullOrdering() {
      return NullOrdering.NULLS_FIRST;
    }
  }

  static class MyNamedReference implements NamedReference {
    private final String[] parts;

    MyNamedReference(String part) {
      this.parts = new String[] { part };
    }

    @Override
    public String[] fieldNames() {
      return this.parts;
    }
  }

  static class MyIdentityTransform implements Transform {
    private final Expression[] args;

    MyIdentityTransform(NamedReference namedReference) {
      this.args = new Expression[] { namedReference };
    }

    @Override
    public String name() {
      return "identity";
    }

    @Override
    public NamedReference[] references() {
      return new NamedReference[0];
    }

    @Override
    public Expression[] arguments() {
      return this.args;
    }
  }

}
