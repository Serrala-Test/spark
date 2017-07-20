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

package org.apache.spark.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import static java.nio.file.attribute.PosixFilePermission.*;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import static org.apache.spark.launcher.CommandBuilderUtils.*;

public class OutputRedirectionSuite extends BaseSuite {

  private static final List<String> MESSAGES = new ArrayList<>();

  @Before
  public void cleanupLog() {
    MESSAGES.clear();
  }

  @Test
  public void testRedirectsSimple() throws Exception {
    SparkLauncher launcher = new SparkLauncher();
    launcher.redirectError(ProcessBuilder.Redirect.PIPE);
    assertNotNull(launcher.errorStream);
    assertEquals(launcher.errorStream.type(), ProcessBuilder.Redirect.Type.PIPE);

    launcher.redirectOutput(ProcessBuilder.Redirect.PIPE);
    assertNotNull(launcher.outputStream);
    assertEquals(launcher.outputStream.type(), ProcessBuilder.Redirect.Type.PIPE);
  }

  @Test
  public void testRedirectLastWins() throws Exception {
    SparkLauncher launcher = new SparkLauncher();
    launcher.redirectError(ProcessBuilder.Redirect.PIPE)
      .redirectError(ProcessBuilder.Redirect.INHERIT);
    assertEquals(launcher.errorStream.type(), ProcessBuilder.Redirect.Type.INHERIT);

    launcher.redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectOutput(ProcessBuilder.Redirect.INHERIT);
    assertEquals(launcher.outputStream.type(), ProcessBuilder.Redirect.Type.INHERIT);
  }

  @Test
  public void testRedirectToLog() throws Exception {
    assumeFalse(isWindows());

    ChildProcAppHandle handle = (ChildProcAppHandle) new TestSparkLauncher().startApplication();
    waitFor(handle);

    assertTrue(MESSAGES.contains("output"));
    assertTrue(MESSAGES.contains("error"));
  }

  @Test
  public void testRedirectErrorToLog() throws Exception {
    assumeFalse(isWindows());

    Path err = Files.createTempFile("stderr", "txt");

    ChildProcAppHandle handle = (ChildProcAppHandle) new TestSparkLauncher()
      .redirectError(err.toFile())
      .startApplication();
    waitFor(handle);

    assertTrue(MESSAGES.contains("output"));
    assertEquals(Arrays.asList("error"), Files.lines(err).collect(Collectors.toList()));
  }

  @Test
  public void testRedirectOutputToLog() throws Exception {
    assumeFalse(isWindows());

    Path out = Files.createTempFile("stdout", "txt");

    ChildProcAppHandle handle = (ChildProcAppHandle) new TestSparkLauncher()
      .redirectOutput(out.toFile())
      .startApplication();
    waitFor(handle);

    assertTrue(MESSAGES.contains("error"));
    assertEquals(Arrays.asList("output"), Files.lines(out).collect(Collectors.toList()));
  }

  @Test
  public void testNoRedirectToLog() throws Exception {
    assumeFalse(isWindows());

    Path out = Files.createTempFile("stdout", "txt");
    Path err = Files.createTempFile("stderr", "txt");

    ChildProcAppHandle handle = (ChildProcAppHandle) new TestSparkLauncher()
      .redirectError(err.toFile())
      .redirectOutput(out.toFile())
      .startApplication();
    waitFor(handle);

    assertTrue(MESSAGES.isEmpty());
    assertEquals(Arrays.asList("error"), Files.lines(err).collect(Collectors.toList()));
    assertEquals(Arrays.asList("output"), Files.lines(out).collect(Collectors.toList()));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadLogRedirect() throws Exception {
    new SparkLauncher()
      .redirectError()
      .redirectOutput(Files.createTempFile("stdout", "txt").toFile())
      .redirectToLog("foo")
      .launch()
      .waitFor();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRedirectErrorTwiceFails() throws Exception {
    new SparkLauncher()
      .redirectError()
      .redirectError(Files.createTempFile("stderr", "txt").toFile())
      .launch()
      .waitFor();
  }

  private void waitFor(ChildProcAppHandle handle) throws Exception {
    while (handle.isRunning()) {
      Thread.sleep(10);
    }
  }

  private static class TestSparkLauncher extends SparkLauncher {

    TestSparkLauncher() {
      setAppResource("outputredirtest");
    }

    @Override
    String findSparkSubmit() throws IOException {
      Path script = new File(getClass().getResource("/output-redir-test.sh").getPath()).toPath();
      Files.setPosixFilePermissions(script, EnumSet.of(OWNER_READ, OWNER_EXECUTE, OWNER_WRITE));
      return script.toFile().getAbsolutePath();
    }

  }

  /**
   * A log4j appender used by child apps of this test. It records all messages logged through it in
   * memory so the test can check them.
   */
  public static class LogAppender extends AppenderSkeleton {

    @Override
    protected void append(LoggingEvent event) {
      MESSAGES.add(event.getMessage().toString());
    }

    @Override
    public boolean requiresLayout() {
      return false;
    }

    @Override
    public void close() {

    }

  }
}
