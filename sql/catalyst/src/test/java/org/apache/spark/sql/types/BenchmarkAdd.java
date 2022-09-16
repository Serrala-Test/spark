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

package org.apache.spark.sql.types;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.RunnerException;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UnlockDiagnosticVMOptions"})
@Warmup(iterations = 10, time = 20, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 20, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkAdd {

  //  @Benchmark
  //  @OperationsPerInvocation(BenchmarkData.COUNT)
  //  public void addBigint(BenchmarkData data) {
  //    for (int i = 0; i < BenchmarkData.COUNT; i++) {
  //      sink(data.bigintDividends[i].add(data.bigintDivisors[i]));
  //    }
  //  }

  //  @Benchmark
  //  @OperationsPerInvocation(BenchmarkData.COUNT)
  //  public void addBigDecimal(BenchmarkData data) {
  //    for (int i = 0; i < BenchmarkData.COUNT; i++) {
  //      sink(data.bigDecimalDividends[i].add(data.bigDecimalDivisors[i]));
  //    }
  //  }

  @Benchmark
  @OperationsPerInvocation(BenchmarkData.COUNT)
  public void addDecimal(BenchmarkData data) {
    for (int i = 0; i < BenchmarkData.COUNT; i++) {
      sink(data.decimalDividends[i].$plus(data.decimalDivisors[i]));
    }
  }

  @Benchmark
  @OperationsPerInvocation(BenchmarkData.COUNT)
  public void addDecimal128(BenchmarkData data) {
    for (int i = 0; i < BenchmarkData.COUNT; i++) {
      sink(data.decimal128Dividends[i].$plus(data.decimal128Divisors[i]));
    }
  }

  //  @Benchmark
  //  @OperationsPerInvocation(BenchmarkData.COUNT)
  //  public void addInt128(BenchmarkData data) {
  //    for (int i = 0; i < BenchmarkData.COUNT; i++) {
  //      sink(data.dividends[i].$plus(data.divisors[i]));
  //    }
  //  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  public static void sink(BigInteger value) {

  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  public static void sink(BigDecimal value) {

  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  public static void sink(Decimal value) {

  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  public static void sink(Int128 value) {

  }

  @State(Scope.Thread)
  public static class BenchmarkData {
    private static final int COUNT = 1000;

    private static final Random RANDOM = new Random();

    private final Int128[] dividends = new Int128[COUNT];
    private final Int128[] divisors = new Int128[COUNT];

    private final BigInteger[] bigintDividends = new BigInteger[COUNT];
    private final BigInteger[] bigintDivisors = new BigInteger[COUNT];

    private final BigDecimal[] bigDecimalDividends = new BigDecimal[COUNT];
    private final BigDecimal[] bigDecimalDivisors = new BigDecimal[COUNT];

    private final Decimal[] decimalDividends = new Decimal[COUNT];
    private final Decimal[] decimalDivisors = new Decimal[COUNT];

    private final Decimal[] decimal128Dividends = new Decimal[COUNT];
    private final Decimal[] decimal128Divisors = new Decimal[COUNT];

    @Param(value = {"126", "90", "65", "64", "63", "32", "10", "1", "0"})
    private int dividendMagnitude = 126;

    @Param(value = {"126", "90", "65", "64", "63", "32", "10", "1"})
    private int divisorMagnitude = 90;

    @Setup
    public void setup() {
      int count = 0;
      while (count < COUNT) {
        Int128 dividend = Int128MathTest.random(dividendMagnitude);
        Int128 divisor = Int128MathTest.random(divisorMagnitude);

         int dividendScale = RANDOM.nextInt(10);
        // int divisorScale = RANDOM.nextInt(10);

        if (ThreadLocalRandom.current().nextBoolean()) {
          dividend = dividend.unary_$minus();
        }

        if (ThreadLocalRandom.current().nextBoolean()) {
          divisor = divisor.unary_$minus();
        }

        if (!divisor.isZero()) {
          dividends[count] = dividend;
          divisors[count] = divisor;

          bigintDividends[count] = dividends[count].toBigInteger();
          bigintDivisors[count] = divisors[count].toBigInteger();

          bigDecimalDividends[count] = new BigDecimal(bigintDividends[count], dividendScale);
          bigDecimalDivisors[count] = new BigDecimal(bigintDivisors[count], dividendScale);

          decimalDividends[count] = newJDKDecimal(bigDecimalDividends[count]);
          decimalDivisors[count] = newJDKDecimal(bigDecimalDivisors[count]);

          decimal128Dividends[count] = newDecimal128(bigDecimalDividends[count]);
          decimal128Divisors[count] = newDecimal128(bigDecimalDivisors[count]);

          count++;
        }
      }
    }
  }

  private static Decimal newJDKDecimal(BigDecimal bigDecimal) {
    Decimal decimal = new Decimal(false);
    return decimal.set(new scala.math.BigDecimal(bigDecimal));
  }

  private static Decimal newDecimal128(BigDecimal bigDecimal) {
    Decimal decimal = new Decimal(true);
    return decimal.set(new scala.math.BigDecimal(bigDecimal));
  }

  @Test
  public void test() {
    BenchmarkData data = new BenchmarkData();
    data.setup();
//    addDecimal2(data);
    addDecimal128(data);
  }

  public static void main(String[] args) throws RunnerException {
    BenchmarkRunner.benchmark(BenchmarkAdd.class);
  }
}
