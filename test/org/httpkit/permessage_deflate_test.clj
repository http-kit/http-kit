(ns org.httpkit.permessage-deflate-test
  "RFC 7692 permessage-deflate.

  The negotiation and codec tests are unit-level because the interesting cases
  are protocol edge cases -- a compressed control frame, RSV1 on a
  continuation, an unknown extension parameter -- that are awkward to provoke
  through a real client and trivial to state directly."
  (:require
   [clojure.test :refer :all]
   [org.httpkit.server :as server])
  (:import
   [org.httpkit.server PerMessageDeflate WSDecoder]
   [org.httpkit ProtocolException]
   [java.nio ByteBuffer]
   [java.util Locale]))

(def ^:private max-size (* 1024 1024))

(defn- ^PerMessageDeflate pmd [offer] (PerMessageDeflate/negotiate offer max-size))

;;;; Negotiation

(deftest negotiation-accepts-a-plain-offer
  (let [p (pmd "permessage-deflate")]
    (is (some? p))
    (is (= "permessage-deflate" (.responseHeader p)))
    (.end p)))

(deftest negotiation-declines-when-not-offered
  (is (nil? (pmd nil)) "no header at all")
  (is (nil? (pmd "")) "empty header")
  (is (nil? (pmd "x-webkit-deflate-frame")) "a DIFFERENT extension must not match"))

(deftest negotiation-echoes-no-context-takeover
  (testing "the parameters constrain BOTH ends, so an accepted one has to be
            echoed or the peer and the server disagree about whether the
            window persists -- which decodes as corruption, not as an error"
    (let [p (pmd "permessage-deflate; client_no_context_takeover")]
      (is (= "permessage-deflate; client_no_context_takeover" (.responseHeader p)))
      (.end p))
    (let [p (pmd "permessage-deflate; server_no_context_takeover; client_no_context_takeover")]
      (is (= "permessage-deflate; server_no_context_takeover; client_no_context_takeover"
             (.responseHeader p)))
      (.end p))))

(deftest negotiation-does-not-echo-window-bits
  (testing "java.util.zip cannot set zlib's windowBits, so the server must not
            claim a smaller window. Accepting the offer while omitting the
            parameter means a 15-bit window, which is what we actually use."
    (let [p (pmd "permessage-deflate; client_max_window_bits=10")]
      (is (some? p) "the offer is still acceptable")
      (is (= "permessage-deflate" (.responseHeader p)) "but the parameter is not echoed")
      (.end p))
    (let [p (pmd "permessage-deflate; client_max_window_bits")]
      (is (some? p) "the valueless form is what browsers send")
      (.end p))))

(deftest negotiation-rejects-what-it-does-not-implement
  (testing "an unknown parameter must make the offer unacceptable rather than
            be ignored -- ignoring it means agreeing to terms we did not
            implement, and the peer then encodes for a contract we are not
            honouring"
    (is (nil? (pmd "permessage-deflate; made_up_parameter")))
    (is (nil? (pmd "permessage-deflate; client_max_window_bits=99")) "out of range")
    (is (nil? (pmd "permessage-deflate; client_max_window_bits=abc")) "not a number")))

(deftest negotiation-takes-the-first-acceptable-offer
  (testing "clients may list several; skip the ones we cannot satisfy"
    (let [p (pmd "permessage-deflate; made_up_parameter, permessage-deflate")]
      (is (some? p))
      (is (= "permessage-deflate" (.responseHeader p)))
      (.end p))))

;;;; The codec

(defn- roundtrip [^PerMessageDeflate p ^String s]
  (let [bs (.getBytes s "UTF-8")]
    (String. (.decompress p (.compress p bs (alength bs))) "UTF-8")))

(deftest codec-round-trips
  (let [p (pmd "permessage-deflate")]
    (try
      (doseq [s ["" "a" "hello world"
                 (apply str (repeat 1000 "compressible "))
                 "unicode: äöü 中文 😀"]]
        (is (= s (roundtrip p s)) (str "len " (count s))))
      (finally (.end p)))))

(deftest context-takeover-is-what-makes-it-worth-having
  (testing "the whole point of the extension for a stream of small similar
            messages: message N is compressed against 1..N-1. Without context
            takeover each message pays full price, so the sizes must diverge."
    (let [^bytes msg (.getBytes "{\"type\":\"publish\",\"topic\":\"store\",\"key\":\"node-1\"}" "UTF-8")
          ^PerMessageDeflate with-ctx (pmd "permessage-deflate")
          ^PerMessageDeflate without  (pmd "permessage-deflate; server_no_context_takeover")]
      (try
        (dotimes [_ 20] (.compress with-ctx msg (alength msg)))
        (dotimes [_ 20] (.compress without  msg (alength msg)))
        (let [a (alength (.compress with-ctx msg (alength msg)))
              b (alength (.compress without  msg (alength msg)))]
          (is (< a b)
              (format "with context takeover %d B, without %d B" a b))
          (is (< a (/ (alength msg) 4))
              "a repeated message should collapse to a small back-reference"))
        (finally (.end with-ctx) (.end without))))))

(deftest decompression-is-bounded
  (testing "WSDecoder bounds the bytes RECEIVED, which says nothing about the
            size after inflation. Without a separate bound, a small frame is an
            unbounded allocation."
    (let [^PerMessageDeflate small (PerMessageDeflate/negotiate "permessage-deflate" 1024)
          ^bytes big (byte-array 1000000)] ; all zeros: compresses to almost nothing
      (try
        (let [compressed (.compress small big (alength big))]
          (is (< (alength compressed) 1024) "the compressed form is tiny")
          (is (thrown-with-msg? Exception #"Max payload length"
                                (.decompress small compressed))))
        (finally (.end small))))))

;;;; Decoder framing rules

(defn- masked-frame
  "A client->server frame. `b0` is the FIN/RSV/opcode byte."
  [b0 ^bytes payload]
  (let [^bytes mask (byte-array [1 2 3 4])
        n (alength payload)
        ^bytes masked (byte-array n)]
    (dotimes [i n]
      (aset masked i (byte (bit-xor (aget payload i) (aget mask (mod i 4))))))
    (let [buf (ByteBuffer/allocate (+ 6 n))]
      ;; unchecked-byte, not byte: the FIN/RSV bits put these above 127 and
      ;; `byte` refuses to narrow them.
      (.put buf (unchecked-byte b0))
      (.put buf (unchecked-byte (bit-or 0x80 n)))   ; MASK + short length
      (.put buf mask)
      (.put buf masked)
      (.flip buf)
      buf)))

(deftest rsv1-is-rejected-without-the-extension
  (testing "unchanged behaviour for a connection that did not negotiate:
            RSV1 stays a protocol error rather than becoming silently ignored"
    (let [d (WSDecoder. max-size)]
      (is (thrown? ProtocolException
                   (.decode d (masked-frame 0xC1 (.getBytes "hi" "UTF-8"))))))))

(deftest a-compressed-control-frame-is-rejected
  (testing "RFC 7692 6.1 -- control frames are never compressed. Accepting one
            would mean inflating a close/ping payload the peer never deflated."
    (let [d (WSDecoder. max-size)
          p (pmd "permessage-deflate")]
      (.setPerMessageDeflate d p)
      (try
        (is (thrown-with-msg? ProtocolException #"compressed websocket control frame"
                              (.decode d (masked-frame 0xC9 (byte-array 0)))))
        (finally (.end p))))))

(deftest rsv1-on-a-continuation-is-rejected
  (testing "RSV1 belongs on the FIRST frame of a message only; a continuation
            inherits it. Repeating it means the sender and receiver disagree
            about where the deflate stream starts."
    (let [d (WSDecoder. max-size)
          p (pmd "permessage-deflate")]
      (.setPerMessageDeflate d p)
      (try
        ;; open a fragmented message (FIN=0, opcode=BINARY, RSV1 set)
        (.decode d (masked-frame 0x42 (byte-array 1)))
        (is (thrown-with-msg? ProtocolException #"RSV1 set on websocket continuation"
                              (.decode d (masked-frame 0x40 (byte-array 1)))))
        (finally (.end p))))))

(deftest rsv2-and-rsv3-stay-unsupported
  (let [d (WSDecoder. max-size)
        p (pmd "permessage-deflate")]
    (.setPerMessageDeflate d p)
    (try
      (doseq [b0 [0xA1 0x91]] ; RSV2, RSV3
        (is (thrown? ProtocolException (.decode d (masked-frame b0 (byte-array 1))))))
      (finally (.end p)))))

;;;; End to end, over a real socket
;;
;; Hand-built rather than driven by a client library. The first version of this
;; used hato, whose underlying java.net.http.WebSocket does NOT offer
;; permessage-deflate -- so it passed while negotiating the extension away and
;; proved nothing. Speaking the bytes directly is the only way to assert that
;; RSV1 actually appears on the wire.

(defn- ws-handshake!
  "Opens a socket, sends an upgrade request offering permessage-deflate, and
  returns [socket in out response-headers]."
  [port]
  (let [sock (java.net.Socket. "localhost" (int port))
        out (.getOutputStream sock)
        in (java.io.DataInputStream. (.getInputStream sock))]
    (.write out (.getBytes (str "GET / HTTP/1.1\r\n"
                                "Host: localhost\r\n"
                                "Upgrade: websocket\r\n"
                                "Connection: Upgrade\r\n"
                                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                                "Sec-WebSocket-Version: 13\r\n"
                                "Sec-WebSocket-Extensions: permessage-deflate\r\n"
                                "\r\n")
                           "UTF-8"))
    (.flush out)
    (try
      (let [headers (loop [acc []]
                      (let [line (.readLine in)]
                        (if (or (nil? line) (= "" line)) acc (recur (conj acc line)))))]
        [sock in out headers])
      (catch Throwable t
        (.close sock)   ; do not leak the socket if reading the response throws
        (throw t)))))

(defn- send-masked!
  "Write a client->server frame with FIN set, `rsv1` optional."
  [^java.io.OutputStream out opcode ^bytes payload rsv1]
  (let [n (alength payload)
        mask (byte-array [9 8 7 6])
        buf (java.io.ByteArrayOutputStream.)]
    (.write buf (unchecked-byte (bit-or 0x80 (if rsv1 0x40 0) opcode)))
    (.write buf (unchecked-byte (bit-or 0x80 n)))   ; assumes n <= 125
    (.write buf mask)
    (dotimes [i n]
      (.write buf (unchecked-byte (bit-xor (aget payload i) (aget mask (mod i 4))))))
    (.write out (.toByteArray buf))
    (.flush out)))

(defn- send-masked-large!
  "Like `send-masked!`, but writes the 16-bit extended length so payloads over
  125 bytes can be sent. Payload must be under 64 KiB."
  [^java.io.OutputStream out opcode ^bytes payload rsv1]
  (let [n (alength payload)
        mask (byte-array [9 8 7 6])
        buf (java.io.ByteArrayOutputStream.)]
    (assert (< n 65536))
    (.write buf (unchecked-byte (bit-or 0x80 (if rsv1 0x40 0) opcode)))
    (if (<= n 125)
      (.write buf (unchecked-byte (bit-or 0x80 n)))
      (do (.write buf (unchecked-byte (bit-or 0x80 126)))
          (.write buf (unchecked-byte (bit-and (bit-shift-right n 8) 0xFF)))
          (.write buf (unchecked-byte (bit-and n 0xFF)))))
    (.write buf mask)
    (dotimes [i n]
      (.write buf (unchecked-byte (bit-xor (aget payload i) (aget mask (mod i 4))))))
    (.write out (.toByteArray buf))
    (.flush out)))

(defn- read-frame!
  "Read one server->client frame (unmasked). Returns [rsv1? payload]."
  [^java.io.DataInputStream in]
  (let [b0 (.readUnsignedByte in)
        b1 (.readUnsignedByte in)
        n (bit-and b1 0x7F)
        n (cond (= n 126) (.readUnsignedShort in)
                (= n 127) (int (.readLong in))
                :else n)
        payload (byte-array n)]
    (.readFully in payload)
    [(not= 0 (bit-and b0 0x40)) payload]))

(deftest ^:integration compression-is-off-unless-asked-for
  (testing "the extension is opt-in per server. A default server must decline a
            perfectly good offer, and the connection then behaves exactly as it
            did before this existed."
    (doseq [[label opts expected] [["default"      {}                             nil]
                                   ["opted in"     {:websocket-compression? true} "permessage-deflate"]
                                   ["explicit off" {:websocket-compression? false} nil]]]
      (testing label
        (let [server (server/run-server
                      (fn [req]
                        (server/as-channel req {:on-receive (fn [ch msg] (server/send! ch msg))}))
                      (merge {:port 0 :join? false} opts))
              port (:local-port (meta server))]
          (try
            (let [[sock _in _out headers] (ws-handshake! port)
                  ext (some #(when (re-find #"(?i)^Sec-WebSocket-Extensions:" %) %) headers)]
              (try
                (if expected
                  (is (and ext (re-find (re-pattern expected) ext))
                      "the offer must be accepted and echoed")
                  (is (nil? ext) "no extension header at all"))
                (finally (.close ^java.net.Socket sock))))
            (finally (server))))))))

(deftest ^:integration compressed-frames-cross-a-real-socket
  (testing "the whole path: the server accepts the offer in the handshake, sets
            RSV1 on what it sends back, and both directions inflate."
    (let [negotiated (atom :never-connected)
          server (server/run-server
                  (fn [req]
                    (server/as-channel
                     req
                     {:on-open (fn [ch]
                                 (reset! negotiated
                                         (some? (.getPerMessageDeflate
                                                 ^org.httpkit.server.AsyncChannel ch))))
                      :on-receive (fn [ch msg] (server/send! ch msg))}))
                  {:port 0 :join? false :websocket-compression? true})
          port (:local-port (meta server))]
      (try
        (let [[sock in out headers] (ws-handshake! port)
              ext (some #(when (re-find #"(?i)^Sec-WebSocket-Extensions:" %) %) headers)]
          (try
            (is (some? ext) "the server echoed Sec-WebSocket-Extensions")
            (is (re-find #"permessage-deflate" ext))
            ;; Poll: ws-handshake! returns as soon as the 101 headers are read,
            ;; but on-open fires afterwards, on a worker thread. Reading the
            ;; atom straight away failed ~2 runs in 12.
            (let [deadline (+ (System/currentTimeMillis) 2000)]
              (while (and (= :never-connected @negotiated)
                          (< (System/currentTimeMillis) deadline))
                (Thread/sleep 10)))
            (is (true? @negotiated) "and installed the codec on the channel")

            ;; A second instance stands in for the client's half of the
            ;; connection; deflate/inflate are symmetric.
            (let [client (pmd "permessage-deflate")]
              (try
                (dotimes [i 20]
                  (let [^bytes msg (.getBytes (str "{\"key\":\"node-" i "\"}") "UTF-8")
                        deflated (.compress client msg (alength msg))]
                    (send-masked! out 0x01 deflated true)
                    (let [[rsv1 echoed] (read-frame! in)]
                      (is rsv1 (str "server set RSV1 on echo " i))
                      (is (= (String. msg "UTF-8")
                             (String. (.decompress client echoed) "UTF-8"))))))
                (finally (.end client))))
            (finally (.close ^java.net.Socket sock))))
        (finally (server))))))

(deftest ^:integration server-close-does-not-fail-the-connection
  (testing "serverClose sends our CLOSE and then WAITS for the peer's (RFC 6455
            5.5.1). Releasing the codec there nulled the decoder's reference,
            so a compressed frame the client had already put on the wire came
            back as \"unsupported websocket extension data\" -- a clean 1000
            close turned into 1000 followed by 1002 and a reset socket, and
            only ever on compressed connections. The codec must outlive our
            half of the closing handshake."
    (let [chan (atom nil)
          server (server/run-server
                  (fn [req]
                    (server/as-channel
                     req
                     {:on-open (fn [ch] (reset! chan ch))
                      :on-receive (fn [ch _msg] (server/close ch))}))
                  {:port 0 :join? false :websocket-compression? true})
          port (:local-port (meta server))]
      (try
        (let [[sock in out _headers] (ws-handshake! port)
              client (pmd "permessage-deflate")]
          (try
            (let [^bytes m1 (.getBytes "bye" "UTF-8")]
              (send-masked! out 0x01 (.compress client m1 (alength m1)) true))
            ;; The frame a real client already had in flight when our CLOSE
            ;; crossed it on the wire.
            (Thread/sleep 300)
            (let [^bytes m2 (.getBytes "in flight" "UTF-8")]
              (send-masked! out 0x01 (.compress client m2 (alength m2)) true))

            (.setSoTimeout ^java.net.Socket sock 1000)
            (let [[_ payload] (read-frame! in)
                  status (when (>= (alength ^bytes payload) 2)
                           (bit-or (bit-shift-left (bit-and (aget ^bytes payload 0) 0xFF) 8)
                                   (bit-and (aget ^bytes payload 1) 0xFF)))]
              (is (= 1000 status) "a clean close"))
            (is (thrown? java.net.SocketTimeoutException (read-frame! in))
                "and no second CLOSE frame: the in-flight compressed frame is
                 discarded, not treated as a protocol error")
            (finally
              (.end client)
              (.close ^java.net.Socket sock))))
        (finally (server)))

      (testing "and the codec is still released once the socket is gone --
                serverClose sets closedRan, which suppresses onClose, so
                HttpServer.closeKey is the only path that always runs"
        (let [deadline (+ (System/currentTimeMillis) 2000)]
          (while (and (some? (.getPerMessageDeflate
                              ^org.httpkit.server.AsyncChannel @chan))
                      (< (System/currentTimeMillis) deadline))
            (Thread/sleep 25)))
        (is (nil? (.getPerMessageDeflate ^org.httpkit.server.AsyncChannel @chan))
            "a retained closed channel must not hold a Deflater and an Inflater")))))

(deftest empty-message-mid-stream
  (testing "RFC 7692 7.2.3.6: a message whose compressed form is empty must go
            out as the single octet 0x00, not as zero bytes.

            This is a regression test for a real interop defect. Zero bytes
            round-trips fine on a fresh connection and as the FIRST message,
            because there is no compression history yet for it to corrupt. Send
            [\"a\" \"\" \"b\"] over one connection with context takeover and the
            stream desynchronises: \"b\" never arrives. It was found by running
            this server against an independent client implementation, which is
            the only reason it surfaced at all -- one implementation talking to
            itself agrees with itself either way."
    (let [server (server/run-server
                  (fn [req]
                    (server/as-channel req {:on-receive (fn [ch msg] (server/send! ch msg))}))
                  {:port 0 :join? false :websocket-compression? true})
          port (:local-port (meta server))]
      (try
        (let [[sock in out _headers] (ws-handshake! port)]
          (try
            (let [client (pmd "permessage-deflate")]
              (try
                (doseq [s ["a" "" "b" "" "" "c"]]
                  (let [^bytes msg (.getBytes ^String s "UTF-8")
                        deflated (.compress client msg (alength msg))]
                    (is (pos? (alength deflated))
                        (str "compressed payload for " (pr-str s) " is never zero-length"))
                    (send-masked! out 0x01 deflated true)
                    (let [[_rsv1 echoed] (read-frame! in)]
                      (is (= s (String. (.decompress client echoed) "UTF-8"))
                          (str "round-trip of " (pr-str s) " in sequence")))))
                (finally (.end client))))
            (finally (.close ^java.net.Socket sock))))
        (finally (server))))))

(deftest negotiation-grammar-is-strict
  (testing "RFC 7692 7.1: an offer we cannot satisfy must be DECLINED, not
            accepted with the offending parameter quietly dropped. Each of
            these was accepted before."
    (testing "server_max_window_bits must be declined outright — java.util.zip
              does not expose windowBits, so we cannot honour a smaller window,
              and accepting while omitting the parameter is not a valid
              acceptance (7.1.2.1). A client sizing its inflate window at 1 KiB
              against our 32 KiB-distance references corrupts silently."
      (is (nil? (pmd "permessage-deflate; server_max_window_bits=10")))
      (is (nil? (pmd "permessage-deflate; server_max_window_bits"))))

    (testing "the no-context-takeover parameters take no value"
      (is (nil? (pmd "permessage-deflate; server_no_context_takeover=x")))
      (is (nil? (pmd "permessage-deflate; client_no_context_takeover=x"))))

    (testing "duplicate parameter names must decline (7.1)"
      (is (nil? (pmd "permessage-deflate; client_no_context_takeover; client_no_context_takeover"))))

    (testing "window-bits values are 1*DIGIT, 8..15, no leading zeroes --
              Integer.parseInt alone accepts \"08\" and \"+8\""
      (is (nil? (pmd "permessage-deflate; client_max_window_bits=08")))
      (is (nil? (pmd "permessage-deflate; client_max_window_bits=+8")))
      (is (nil? (pmd "permessage-deflate; client_max_window_bits=7")))
      (is (nil? (pmd "permessage-deflate; client_max_window_bits=16"))))

    (testing "an empty parameter is not valid grammar"
      (is (nil? (pmd "permessage-deflate;;"))))

    (testing "and what browsers actually send is still accepted"
      (is (some? (pmd "permessage-deflate; client_max_window_bits")))
      (is (some? (pmd "permessage-deflate; client_max_window_bits=15")))
      (is (some? (pmd "permessage-deflate")))
      (is (some? (pmd "permessage-deflate; client_no_context_takeover")))
      (testing "including skipping an unsatisfiable offer for a later one"
        (is (some? (pmd "permessage-deflate; server_max_window_bits=10, permessage-deflate")))))))

(deftest offers-split-across-repeated-headers
  (testing "RFC 6455 9.1: a client may repeat Sec-WebSocket-Extensions, and the
            result is equivalent to one comma-separated value -- so a later
            offer must still be reachable when an earlier one is unsatisfiable.
            http-kit joins duplicates with a newline and normalises that to a
            comma before the Ring map (#615), so the wire path is covered by
            that; the parser accepts either separator so it is correct even for
            a caller passing a raw header value."
    (let [p (pmd "unsatisfiable-offer\npermessage-deflate")]
      (is (some? p) "the newline-joined fallback must be found")
      (.end p))
    (let [p (pmd "permessage-deflate; server_max_window_bits=9\npermessage-deflate")]
      (is (some? p) "skip the unsatisfiable one, take the next")
      (is (= "permessage-deflate" (.responseHeader p)))
      (.end p))
    (let [p (pmd "unsatisfiable-offer, permessage-deflate")]
      (is (some? p) "and the comma-joined form the Ring map actually delivers")
      (.end p))))

(deftest quoted-values-are-one-token
  (testing "RFC 7692 5.2 lets a parameter value be quoted, and RFC 7230's
            quoted-string may contain commas and semicolons. Splitting the raw
            header on ',' cut through the quotes, so a client could smuggle a
            whole offer through the middle of an INVALID value and have the
            server accept a phantom it never made."
    (is (nil? (pmd (str "permessage-deflate; client_max_window_bits="
                        "\"10, permessage-deflate,11\"")))
        "one offer, one invalid value: decline it — do not find a second offer inside it")
    (is (nil? (pmd "permessage-deflate; client_max_window_bits=\"10; server_max_window_bits=10\""))
        "a quoted semicolon is not a parameter separator either")

    (testing "a legitimately quoted value is still accepted (5.2)"
      (let [p (pmd "permessage-deflate; client_max_window_bits=\"12\"")]
        (is (some? p))
        (.end p)))

    (testing "unbalanced quoting is not parseable, so nothing in the header is trustworthy"
      (is (nil? (pmd "permessage-deflate; client_max_window_bits=\"12")))
      (is (nil? (pmd "permessage-deflate; client_max_window_bits=\"12\\"))))

    (testing "an offer after a quoted comma is a real offer when the quotes close"
      (let [p (pmd "permessage-deflate; server_max_window_bits=\"10\", permessage-deflate")]
        (is (some? p) "first offer unsatisfiable, second one plain")
        (.end p)))))

(deftest duplicate-parameters-decline-in-every-locale
  (testing "RFC 7692 7.1 requires declining duplicate parameter names. The
            duplicate check lowercased with the JVM default locale, so under a
            Turkish default 'CLIENT_MAX_WINDOW_BITS' folded to a dotless-i
            spelling that did not collide with the lowercase one — a bug that
            reproduces on a Turkish-locale JVM and nowhere else."
    (let [original (Locale/getDefault)]
      (try
        (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
        (is (nil? (pmd (str "permessage-deflate; CLIENT_MAX_WINDOW_BITS=10; "
                            "client_max_window_bits=11"))))
        (is (nil? (pmd (str "permessage-deflate; CLIENT_NO_CONTEXT_TAKEOVER; "
                            "client_no_context_takeover"))))
        (finally (Locale/setDefault original))))))

(deftest zero-byte-compressed-payload-is-rejected
  (testing "RFC 7692 7.2.3.6: a compressed message with no content still
            carries the single octet 0x00. Zero octets is malformed, and
            accepting it is worse than useless: appending the tail to nothing
            gives 00 00 FF FF, whose LEN field reads as 0xFF00, leaving the
            inflater mid stored-block. The empty message arrives fine and the
            NEXT message dies with 'invalid stored block lengths', one message
            away from the actual fault."
    (let [p (pmd "permessage-deflate")]
      (try
        (is (= 1002 (try (.decompress p (byte-array 0))
                         :accepted
                         (catch org.httpkit.server.WebSocketException e
                           (.getCloseStatus e))))
            "fail the frame with a protocol error, at the frame that caused it")

        (testing "and the codec is still usable afterwards — rejecting the frame
                  does not touch inflater state"
          (let [msg (.getBytes "hello hello hello" "UTF-8")]
            (is (= "hello hello hello"
                   (String. (.decompress p (.compress p msg (alength msg))) "UTF-8")))))
        (finally (.end p))))))

(defn- bytes= [expected ^bytes actual]
  (= (seq (byte-array (map unchecked-byte expected))) (seq actual)))

(deftest a-bfinal-block-does-not-poison-the-stream
  (testing "RFC 7692 7.2.3.4 lets a sender flush with a BFINAL=1 block instead
            of an empty uncompressed one, and gives the wire format for
            \"Hello\" verbatim. A single Inflater reports finished() forever
            after such a block, so the old loop exited quietly and delivered
            every LATER message as an empty string, with no error at either
            end -- silent, permanent, whole-connection data loss triggered by
            an 8-byte frame."
    (let [;; 7.2.3.4: "Hello", flushed with BFINAL=1.
          bfinal (byte-array (map unchecked-byte [0xf3 0x48 0xcd 0xc9 0xc9 0x07 0x00 0x00]))
          ;; 7.2.3.1: "Hello", flushed with SYNC_FLUSH.
          sync   (byte-array (map unchecked-byte [0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00]))
          p (pmd "permessage-deflate")]
      (try
        (is (= "Hello" (String. (.decompress p bfinal) "UTF-8"))
            "the RFC's own BFINAL vector decodes")
        (is (= "Hello" (String. (.decompress p sync) "UTF-8"))
            "and the NEXT message still decodes")
        (is (= "Hello" (String. (.decompress p sync) "UTF-8"))
            "and the one after that")
        (finally (.end p)))))

  (testing "RFC 7692 7.2.1: \"The next DEFLATE block follows the padded data if
            any\" -- blocks may follow a BFINAL block inside ONE message, so a
            receiver that stops at the first one hands the application a
            silently truncated message."
    (let [p (pmd "permessage-deflate")]
      (try
        (is (= "HelloWorld"
               (String. (.decompress p (byte-array
                                        (map unchecked-byte
                                             [0xf3 0x48 0xcd 0xc9 0xc9 0x07 0x00
                                              0x0a 0xcf 0x2f 0xca 0x49 0x01 0x00])))
                        "UTF-8"))
            "blocks after the BFINAL one must not be dropped")
        (finally (.end p))))))

(defn- bfinal-payload
  "Compress `s` the way a BFINAL-flushing sender does (RFC 7692 7.2.3.4): finish
  the DEFLATE stream, then append the empty-block header octet so the payload
  can be decompressed like a SYNC_FLUSH one. Each message is an independent
  stream, which is what such a sender necessarily produces -- finishing ends its
  own compression history too."
  ^bytes [^String s]
  (let [d (java.util.zip.Deflater. java.util.zip.Deflater/DEFAULT_COMPRESSION true)
        b (.getBytes s "UTF-8")
        out (byte-array (+ 64 (* 2 (alength b))))]
    (.setInput d b)
    (.finish d)
    (let [n (.deflate d out)
          r (byte-array (inc n))]
      (.end d)
      (System/arraycopy out 0 r 0 n)
      (aset-byte r n (byte 0))
      r)))

(deftest a-bfinal-flushing-peer-round-trips-a-whole-conversation
  (testing "the case the restart logic exists for, end to end rather than as
            single vectors: a peer that flushes every message with BFINAL (RFC
            7692 7.2.3.4) must be readable for the WHOLE conversation, not just
            its first message. Before the restart, message 1 decoded and every
            message after it silently arrived empty."
    (let [p (pmd "permessage-deflate")]
      (try
        (doseq [s ["first message"
                   "second message, quite similar to the first"
                   ""
                   "third"
                   (apply str (repeat 500 "repetitive payload "))]]
          (is (= s (String. (.decompress p (bfinal-payload s)) "UTF-8"))
              (str "BFINAL-flushed round-trip of " (pr-str (subs s 0 (min 20 (count s)))))))
        (finally (.end p))))))

(defn- stream-storm
  "A `size`-byte payload made of back-to-back complete BFINAL DEFLATE streams.
  `unit` 03 00 inflates to nothing; 73 04 00 inflates to one byte each."
  ^bytes [unit size]
  (let [^bytes u (byte-array (map unchecked-byte unit))
        n (* (quot size (alength u)) (alength u))
        out (byte-array n)]
    (dotimes [i (quot n (alength u))]
      (System/arraycopy u 0 out (* i (alength u)) (alength u)))
    out))

(deftest a-stream-storm-is-bounded
  (testing "the cost of a DEFLATE stream restart is a native Inflater.reset(),
            which the post-inflation size guard cannot see because it counts
            OUTPUT. Unbounded, a 4 MiB frame of restarts cost two to three
            ORDERS OF MAGNITUDE more of the server's single IO thread than a
            legitimate 4 MiB message; the absolute figures vary several-fold
            with machine, load and JIT, so the ratio is the claim, not any
            particular millisecond count.

            Both shapes matter: a ceiling counting only empty streams is
            dodged by giving each stream one byte of output, so there is a
            ceiling on empty streams AND an absolute one.

            1008, not 1002: RFC 7692 7.2.1 sets no limit on blocks per message,
            so this is our resource policy rather than a protocol violation."
    (doseq [[label unit] [["empty streams (03 00)" [0x03 0x00]]
                          ["1-byte output (73 04 00)" [0x73 0x04 0x00]]]]
      (testing label
        (let [p (PerMessageDeflate/negotiate "permessage-deflate" (* 8 1024 1024))
              storm (stream-storm unit (* 4 1024 1024))]
          (try
            (let [t0 (System/nanoTime)
                  status (try (.decompress p storm) :accepted
                              (catch org.httpkit.server.WebSocketException e
                                (.getCloseStatus e)))
                  ms (/ (- (System/nanoTime) t0) 1000000.0)]
              (is (= 1008 status) "bounded rather than ground through")
              ;; No timing assertion: it would be load-sensitive, and the
              ;; ceilings are what bound the work. Reported for context only.
              (is (number? ms) (str "took " ms " ms")))
            (finally (.end p))))))))

(deftest the-stream-ceilings-are-exactly-where-they-are-documented
  (testing "the CHANGELOG promises at most 64 empty and at most 65536 total
            DEFLATE streams per MESSAGE. decompress() inflates in two calls,
            payload then 4-octet trailer, and the ceilings must span both or
            they sit one higher than advertised. Pin both, from both sides."
    (let [;; n empty streams (03 00), then `last` as the final octet. 0x00 is a
          ;; non-final stored-block header, so the appended trailer merely
          ;; completes a block and finishes no stream. 0x01 is the same block
          ;; with BFINAL set, so the trailer completes a whole stream -- inside
          ;; decompress()'s SECOND inflate() call, which is where a per-call
          ;; counter granted a free extra stream.
          empties (fn [n last]
                    (let [b (java.io.ByteArrayOutputStream.)]
                      (dotimes [_ n] (.write b 0x03) (.write b 0x00))
                      (.write b (int last))
                      (.toByteArray b)))
          status (fn [^bytes payload]
                   (let [p (PerMessageDeflate/negotiate "permessage-deflate" (* 8 1024 1024))]
                     (try (.decompress p payload) :accepted
                          (catch org.httpkit.server.WebSocketException e (.getCloseStatus e))
                          (finally (.end p)))))]
      (is (= :accepted (status (empties 64 0x00)))
          "exactly 64 empty streams is within the ceiling")
      (is (= 1008 (status (empties 65 0x00)))
          "65 is over it")
      (is (= 1008 (status (empties 64 0x01)))
          "and so is 64 plus one the TRAILER completes, in the second
           inflate() call"))))

(deftest many-non-empty-streams-in-one-message-are-fine
  (testing "RFC 7692 7.2.1 puts no limit on how many DEFLATE blocks a message
            may contain and 7.2.3.4 lets any of them set BFINAL, so the
            ceilings have to sit far above anything a real sender emits. This
            message carries several hundred streams, every one with data in
            it, and decodes whole."
    (let [n 300
          parts (map #(str "part-" % ";") (range n))
          payload (java.io.ByteArrayOutputStream.)]
      ;; Concatenate n independent finished DEFLATE streams into ONE payload.
      (doseq [s parts]
        (let [^bytes b (bfinal-payload s)]
          ;; Drop the trailing empty-block header from all but the last, so the
          ;; streams butt directly against each other.
          (.write payload b 0 (dec (alength b)))))
      (.write payload 0)
      (let [p (pmd "permessage-deflate")]
        (try
          (is (= (apply str parts)
                 (String. (.decompress p (.toByteArray payload)) "UTF-8"))
              (str n " concatenated data-bearing streams must all decode"))
          (finally (.end p)))))))

(deftest a-truncated-bfinal-payload-is-tolerated-but-does-not-corrupt
  (testing "a payload ending exactly at a finished stream omits the trailing
            empty-block header RFC 7692 7.2.1 step 3 guarantees, so it is
            strictly malformed -- 7.2.3.4's legal vector is eight octets, not
            seven. We accept it deliberately: the rule this class follows is to
            reject what desynchronises the stream, not everything imperfect.
            What matters is that the codec stays usable afterwards."
    (let [p (pmd "permessage-deflate")
          sync (byte-array (map unchecked-byte [0xf2 0x48 0xcd 0xc9 0xc9 0x07 0x00]))]
      (try
        (is (= "Hello"
               (String. (.decompress p (byte-array
                                        (map unchecked-byte
                                             [0xf3 0x48 0xcd 0xc9 0xc9 0x07 0x00])))
                        "UTF-8"))
            "seven-octet BFINAL, no trailing 0x00")
        (is (= "Hello" (String. (.decompress p sync) "UTF-8"))
            "and the stream is still in sync -- unlike the zero-byte payload,
             which we reject precisely because it is not")
        (is (= "Hello" (String. (.decompress p sync) "UTF-8")))
        (finally (.end p))))))

(deftest ^:integration decompression-bound-defaults-to-max-ws
  (testing "*websocket-max-message-size* nil means \"use :max-ws\". When it was
            a fixed 4 MB, a server started with a small :max-ws rejected a
            slightly-too-big uncompressed message while still accepting a
            compressed one that inflated to nearly 4 MB -- so lowering :max-ws
            stopped bounding memory for any client that negotiated compression."
    (let [tiny (* 64 1024)
          ;; Highly compressible: a small frame that inflates well past :max-ws
          ;; but stays well under the old fixed 4 MB constant.
          payload (apply str (repeat (* 256 1024) \a))
          ;; Returns :rejected-1009 or the echoed message.
          run (fn [max-message-size]
                (let [server (server/run-server
                              (fn [req]
                                (server/as-channel
                                 req {:on-receive (fn [ch msg] (server/send! ch msg))}))
                              {:port 0 :join? false :max-ws tiny
                               :websocket-compression? true
                               :websocket-max-message-size max-message-size})
                        port (:local-port (meta server))]
                    (try
                      (let [[sock in out _] (ws-handshake! port)
                            client (pmd "permessage-deflate")]
                        (try
                          (let [^bytes big (.getBytes ^String payload "UTF-8")
                                deflated (.compress client big (alength big))]
                            (is (< (alength deflated) tiny)
                                "the frame itself is under :max-ws, so only the
                                 post-inflation bound can catch it")
                            (send-masked-large! out 0x01 deflated true)
                            (.setSoTimeout ^java.net.Socket sock 3000)
                            (let [[rsv1 body] (read-frame! in)]
                              (if (and (>= (alength ^bytes body) 2) (not rsv1)
                                       (= 1009 (bit-or (bit-shift-left
                                                        (bit-and (aget ^bytes body 0) 0xFF) 8)
                                                       (bit-and (aget ^bytes body 1) 0xFF))))
                                :rejected-1009
                                (String. (.decompress client body) "UTF-8"))))
                          (finally
                            (.end client)
                            (.close ^java.net.Socket sock))))
                      (finally (server)))))]

      ;; Positive control: pinned to the OLD fixed default, the very same
      ;; message sails through despite :max-ws being 64 KiB. That is the bug.
      (is (= payload (run (* 4 1024 1024)))
          "with a fixed 4 MB bound, :max-ws 64 KiB does not bound a compressed message at all")

      ;; 0 => derive from :max-ws, so the same message is now bounded.
      (is (= :rejected-1009 (run 0))
          "defaulting to :max-ws makes the one knob mean what it says"))))

(deftest compress-after-end-is-still-well-formed
  (testing "whatever compress() returns is framed with RSV1 set, so returning
            zero bytes on a shutdown race put the malformed payload we reject
            on the wire and corrupted the PEER's inflater. RFC 7692 7.2.3.6's
            empty block is the well-formed way to say \"no content\"."
    (let [p (pmd "permessage-deflate")
          msg (.getBytes "anything" "UTF-8")]
      (.end p)
      (is (bytes= [0x00] (.compress p msg (alength msg)))))))

(deftest server-max-window-bits-15-is-satisfiable
  (testing "RFC 7692 7.1.2.1: accepting requires echoing the parameter \"with
            the same or smaller value as the offer\". java.util.zip fixes our
            deflater's window at 15, so 15 is honourable exactly while nothing
            smaller is. Autobahn's compression cases offer =15 as a single
            offer with no fallback element, so declining it lost compression
            outright."
    (let [p (pmd "permessage-deflate; server_max_window_bits=15")]
      (is (some? p))
      (is (= "permessage-deflate; server_max_window_bits=15" (.responseHeader p))
          "and the acceptance must echo it, or the client sees an invalid response")
      (.end p))

    (testing "a smaller window still cannot be honoured and must be declined --
              a client sizing its inflate window at 1 KiB against our
              32 KiB-distance references corrupts silently"
      (is (nil? (pmd "permessage-deflate; server_max_window_bits=10")))
      (is (nil? (pmd "permessage-deflate; server_max_window_bits=14"))))

    (testing "the parameter has no valueless form (7.1.2.1: \"This parameter
              has a decimal integer value\"), so a bare one is an invalid
              value, not a hint"
      (is (nil? (pmd "permessage-deflate; server_max_window_bits"))))

    (testing "combined with the other parameters"
      (let [p (pmd "permessage-deflate; client_max_window_bits; server_max_window_bits=15")]
        (is (some? p))
        (is (= "permessage-deflate; server_max_window_bits=15" (.responseHeader p))
            "client_max_window_bits is accepted but never echoed (7.1.2.2 MAY)")
        (.end p)))))

(deftest ^:integration compression-threshold-clears-rsv1
  (testing "RFC 7692 6.1 lets any individual message go out uncompressed.
            Below the threshold we must send it with RSV1 CLEAR and, crucially,
            must not have fed it to the deflater -- otherwise our LZ77 window
            and the peer's inflater window disagree and every later message
            decodes to garbage."
    (let [server (server/run-server
                  (fn [req]
                    (server/as-channel req {:on-receive (fn [ch msg] (server/send! ch msg))}))
                  {:port 0 :join? false :websocket-compression? true
                   :websocket-compression-threshold 64})
            port (:local-port (meta server))]
        (try
          (let [[sock in out _] (ws-handshake! port)
                client (pmd "permessage-deflate")
                seen-rsv1 (atom #{})]
            (try
              ;; Interleave short (below 64) and long (above) messages, and
              ;; assert the compressed stream stays in step across the gaps.
              (doseq [[i s] (map-indexed vector ["hi" "x"
                                                 (apply str (repeat 40 "compress me "))
                                                 "no" (apply str (repeat 40 "and me "))
                                                 "tiny"])]
                (let [^bytes msg (.getBytes ^String s "UTF-8")]
                  (send-masked! out 0x01 (.compress client msg (alength msg)) true)
                  (let [[rsv1 payload] (read-frame! in)
                        decoded (if rsv1
                                  (String. (.decompress client payload) "UTF-8")
                                  (String. ^bytes payload "UTF-8"))]
                    (swap! seen-rsv1 conj rsv1)
                    (is (= s decoded) (str "round-trip " i " (rsv1=" rsv1 ")"))
                    (is (= (>= (alength msg) 64) rsv1)
                        (str "message " i " of " (alength msg)
                             " bytes: RSV1 must track the threshold")))))
              (is (= #{true false} @seen-rsv1)
                  "the test is only meaningful if BOTH paths were exercised")
              (finally
                (.end client)
                (.close ^java.net.Socket sock))))
          (finally (server))))))

(deftest compression-threshold-is-a-pre-compression-decision
  (testing "the threshold must be consulted BEFORE compressing. Deciding
            afterwards -- 'did it get smaller?' -- would desynchronise context
            takeover, because the bytes are already in the deflater's window by
            then."
    (let [p (PerMessageDeflate/negotiate "permessage-deflate" max-size 128)]
      (try
        (is (false? (.shouldCompress p 0)))
        (is (false? (.shouldCompress p 127)))
        (is (true? (.shouldCompress p 128)))
        (is (true? (.shouldCompress p 100000)))
        (finally (.end p))))

    (testing "and the default threshold of 0 compresses everything, which is
              what the flagship case wants: with context takeover a 16-byte
              repetitive message compresses to ~0.25x, so ws's 1024 default
              would discard the win rather than protect it"
      (let [p (pmd "permessage-deflate")]
        (try
          (is (true? (.shouldCompress p 0)))
          (is (true? (.shouldCompress p 2)))
          (finally (.end p)))))))

(deftest ^:integration releasing-the-codec-never-waits-on-the-channel-monitor
  (testing "HttpServer.closeKey releases the codec from the selector thread
            while doWrite holds the connection's ServerAtta monitor, and
            AsyncChannel.send takes the channel monitor and then asks tryWrite
            for that same ServerAtta monitor. A synchronized accessor on the
            release path closes that cycle and deadlocks the single selector
            thread against an application thread -- the whole server, on every
            connection, compressed or not. Assert the invariant directly rather
            than trying to race it: with the channel monitor held, releasing
            must still complete."
    (let [chan (atom nil)
          server (server/run-server
                  (fn [req] (server/as-channel req {:on-open #(reset! chan %)}))
                  {:port 0 :join? false :websocket-compression? true})
          port (:local-port (meta server))]
      (try
        (let [[sock _in _out _] (ws-handshake! port)]
          (try
            (let [deadline (+ (System/currentTimeMillis) 2000)]
              (while (and (nil? @chan) (< (System/currentTimeMillis) deadline))
                (Thread/sleep 10)))
            (is (some? @chan) "handshake completed")
            (let [^org.httpkit.server.AsyncChannel ch @chan
                  released (promise)]
              ;; Hold the channel monitor, exactly as an application thread
              ;; parked inside send! would.
              (locking ch
                (future (.releasePerMessageDeflate ch) (deliver released :done))
                (is (= :done (deref released 3000 :BLOCKED))
                    "release must not need the channel monitor")))
            (finally (.close ^java.net.Socket sock))))
        (finally (server))))))

(deftest a-codec-installed-after-release-is-not-leaked
  (testing "a connection can die while its handshake is still running --
            HttpServer.stop closes keys from the stopping thread. Installing
            after release would leave a Deflater and an Inflater on a dead
            channel that nothing ever frees, and installing DURING release
            would leave the decoder pointing at an ended codec while this
            channel's reference is null."
    (let [chan (atom nil)
          server (server/run-server
                  (fn [req] (server/as-channel req {:on-open #(reset! chan %)}))
                  {:port 0 :join? false :websocket-compression? true})
          port (:local-port (meta server))]
      (try
        (let [[sock _in _out _] (ws-handshake! port)]
          (try
            (let [deadline (+ (System/currentTimeMillis) 2000)]
              (while (and (nil? @chan) (< (System/currentTimeMillis) deadline))
                (Thread/sleep 10)))
            (is (some? @chan) "handshake completed")
            (let [^org.httpkit.server.AsyncChannel ch @chan
                  late (pmd "permessage-deflate")]
              (.releasePerMessageDeflate ch)
              (.setPerMessageDeflate ch late)
              (is (nil? (.getPerMessageDeflate ch))
                  "a codec must not be installed on a released channel")
              (is (thrown? org.httpkit.server.WebSocketException
                           (.decompress late (byte-array [0x00])))
                  "and the one we handed over was ended, not leaked"))
            (finally (.close ^java.net.Socket sock))))
        (finally (server))))))

(deftest end-is-idempotent-and-safe
  (testing "both close paths reach end(), and HttpServer.stop can close a
            channel while the selector is still decoding, so end() must be
            idempotent and must not leave a released Inflater reachable"
    (let [p (pmd "permessage-deflate")]
      (.end p)
      (.end p)
      (is (thrown? org.httpkit.server.WebSocketException
                   (.decompress p (byte-array [0x00])))
          "decompress after end fails cleanly rather than touching freed native memory"))))
