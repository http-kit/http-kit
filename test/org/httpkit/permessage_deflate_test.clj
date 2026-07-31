(ns org.httpkit.permessage-deflate-test
  "RFC 7692 permessage-deflate.

  The negotiation and codec tests are unit-level because the interesting cases
  are protocol edge cases -- a compressed control frame, RSV1 on a
  continuation, an unknown extension parameter -- that are awkward to provoke
  through a real client and trivial to state directly."
  (:require
   [clojure.test :refer :all]
   [org.httpkit.server :as server]
   [hato.websocket])
  (:import
   [org.httpkit.server PerMessageDeflate WSDecoder]
   [org.httpkit ProtocolException]
   [java.nio ByteBuffer]))

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

;;;; Opt-out

(deftest compression-can-be-refused
  (testing "already-compressed payloads and memory-sensitive servers want out;
            binding the var off must make negotiation a no-op"
    (binding [server/*websocket-compression?* false]
      (is (nil? (#'server/negotiate-permessage-deflate!
                 nil {:headers {"sec-websocket-extensions" "permessage-deflate"}}))))))

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
    (let [headers (loop [acc []]
                    (let [line (.readLine in)]
                      (if (or (nil? line) (= "" line)) acc (recur (conj acc line)))))]
      [sock in out headers])))

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
                  {:port 0 :join? false})
          port (:local-port (meta server))]
      (try
        (let [[sock in out headers] (ws-handshake! port)
              ext (some #(when (re-find #"(?i)^Sec-WebSocket-Extensions:" %) %) headers)]
          (try
            (is (some? ext) "the server echoed Sec-WebSocket-Extensions")
            (is (re-find #"permessage-deflate" ext))
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
                  {:port 0 :join? false})
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
