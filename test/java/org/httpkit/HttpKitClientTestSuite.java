package org.httpkit;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import org.httpkit.client.*;

/* in progress:
 * this is a temporary scaffold to move towards cleaner tests
 *
 * following currently fail:
 *  - HttpClientDecoderTest.class   not finding file on class path
 *  - TextClientTest.class          hangs, does not complete
 *  
 * following are currently not JUnit tests:
 *  - HttpClientTest2.class
 *  - HttpsClientTest.class
 *  - HttpsTest.class
 *  - SSLTest.class
 *
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
  HttpClientDecoderTest.class,
  // TextClientTest.class,
  HttpClientTest.class,
})
public class HttpKitClientTestSuite { }
