package org.httpkit;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import org.httpkit.server.*;

/* in progress:
 * this is a temporary scaffold to move towards cleaner tests
 *
 * following currently fail:
 *  
 * following are currently not JUnit tests:
 *  - MultiThreadHttpServerTest.class
 *  - PipelineTest.class
 *  - SingleThreadHttpServerTest.class
 *  
 *
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
  RingHandlerTest.class,
  ThreadPoolTest.class
})
public class HttpKitServerTestSuite { }
