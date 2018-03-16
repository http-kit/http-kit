(ns org.httpkit.encode)

(defmacro base64-encode [bs]
  (if (try (import 'javax.xml.bind.DatatypeConverter)
           (catch ClassNotFoundException _))
    `(javax.xml.bind.DatatypeConverter/printBase64Binary ~bs)
    (do
      (import 'java.util.Base64)
      `(.encodeToString (java.util.Base64/getEncoder) ~bs))))
