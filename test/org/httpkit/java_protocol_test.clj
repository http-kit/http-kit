(ns org.httpkit.java-protocol-test
  (:require [clojure.test :refer [deftest is]])
  (:import
   [org.httpkit HttpUtilsTest]
   [org.httpkit.client HttpClientDecoderTest RespListenerTest]
   [org.httpkit.server AsyncChannelCloseTest HttpDecoderTest
    HttpServerProtocolTest RingResponseTest]
   [org.httpkit.timer TimerServiceTest]
   [org.junit.runner JUnitCore]))

(deftest java-protocol-regressions
  (let [result
        (JUnitCore/runClasses
          (into-array Class
            [HttpUtilsTest HttpClientDecoderTest RespListenerTest
             AsyncChannelCloseTest HttpDecoderTest HttpServerProtocolTest
             RingResponseTest TimerServiceTest]))]
    (is (.wasSuccessful result)
      (pr-str (map str (.getFailures result))))))
