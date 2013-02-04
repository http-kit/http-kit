(ns org.httpkit.timer
  (:import [org.httpkit TimerService CancelableFutureTask]))

;; TimerService
;; 1. can schedule many many timeout tasks at once
;; 2. schedule in 1000ms, maybe run in 1000ms, 10001ms, etc.
;; 3. cancel return true => future task guaranteed canceled; return false => already cancelled || already runned
;; 4. schedule a new timeout task O(log(N))  : N is active task
;; 5. cancel O(N)
;; 6. not used, not pay => timer-service thread will kill itself when no task to schedule for 4 minites, restart the thread automatically if new task get added

(defmacro set-timeout [ms & forms]
  `(.timeout TimerService/SERVICE ~ms (fn [] ~@forms)))

(defn cancel [^CancelableFutureTask timer]
  (.cancel timer))

(comment
  (let [timers (doall
                (map (fn [idx]
                       (let [time (+ (rand-int 10000) 1000)]
                         (set-timeout time
                                      (println (str "#" idx ", with timeout " time)))))
                     (range 10)))]
    (if (cancel (nth timers 3))
      (println "3 get canceled"))))
