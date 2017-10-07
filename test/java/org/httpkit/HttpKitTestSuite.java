package org.httpkit;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import org.httpkit.HttpKitClientTestSuite;
import org.httpkit.HttpKitServerTestSuite;


@RunWith(Suite.class)
@Suite.SuiteClasses({
  HttpKitServerTestSuite.class,
  HttpKitClientTestSuite.class,
})
public class HttpKitTestSuite { }

