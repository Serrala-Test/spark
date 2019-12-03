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

package org.apache.spark.unsafe.types;

import org.junit.Test;

import java.time.Duration;
import java.time.Period;

import static org.junit.Assert.*;
import static org.apache.spark.sql.catalyst.util.DateTimeConstants.*;

public class CalendarIntervalSuite {

  @Test
  public void equalsTest() {
    CalendarInterval i1 = new CalendarInterval(3, 2, 123);
    CalendarInterval i2 = new CalendarInterval(3, 2,321);
    CalendarInterval i3 = new CalendarInterval(3, 4,123);
    CalendarInterval i4 = new CalendarInterval(1, 2, 123);
    CalendarInterval i5 = new CalendarInterval(1, 4, 321);
    CalendarInterval i6 = new CalendarInterval(3, 2, 123);

    assertNotSame(i1, i2);
    assertNotSame(i1, i3);
    assertNotSame(i1, i4);
    assertNotSame(i2, i3);
    assertNotSame(i2, i4);
    assertNotSame(i3, i4);
    assertNotSame(i1, i5);
    assertEquals(i1, i6);
  }

  @Test
  public void periodAndDurationTest() {
    CalendarInterval interval = new CalendarInterval(120, -40, 123456);
    assertEquals(Period.of(0, 120, -40), interval.extractAsPeriod());
    assertEquals(Duration.ofNanos(123456000), interval.extractAsDuration());
  }
}
