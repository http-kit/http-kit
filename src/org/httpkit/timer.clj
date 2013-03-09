(ns org.httpkit.timer
  "TimerService wrapper:
     * Can schedule many tasks at once.
     * When scheduled for 1000ms, may run in 1000ms, 10001ms, etc.
     * Cancel returns true => future task guaranteed cancelled;
             returns false => already cancelled || already run.
     * Scheduling a new task is O(log(N)) where N is # active tasks.
     * Cancelling a task is O(N).
     * Timer-service thread will kill itself automatically when no task is
       scheduled for 4 minutes, and will restart automatically when a new task
       is added."
  (:import [org.httpkit.timer TimerService CancelableFutureTask]))

(defn cancel [^CancelableFutureTask task]
  (.cancel task))

(defmacro schedule-task
  "Schedules body for invocation after given time and returns a
  CancelableFutureTask. `(cancel task)` will cancel the task if possible and
  return true iff cancellation was successful.

    (let [task (schedule-task 800 (println \"Task triggered\"))]
      (Thread/sleep (rand-nth [900 700]))
      (when (cancel task) ; Returns true iff task successfully cancelled
        (println \"Task was cancelled\")))"
  [ms & body]
  `(.scheduleTask TimerService/SERVICE ~ms (fn [] ~@body)))

(comment (let [task (schedule-task 800 (println "Task triggered"))]
           (Thread/sleep (rand-nth [900 700]))
           (when (cancel task)
             (println "Task was cancelled"))))

(defmacro with-timeout
  "Schedules timeout-form for invocation after given timeout and wraps named
  fn so that calling it with any arguments also cancels the timeout if possible.
  If the timeout has already been invoked, the fn will not run and will
  immediately return nil.

    (with-timeout println 800 (println \"Timeout task triggered\")
      (Thread/sleep (rand-nth [900 700]))
      (println \"Timeout task was cancelled\"))"
  [f ms timeout-form & body]
  `(let [timeout-task# (schedule-task ~ms ~timeout-form)
         ~f (fn [& args#]
              (when (cancel timeout-task#)
                (apply ~f args#)))]
     ~@body))

(comment (with-timeout println 800 (println "Timeout task triggered")
           (Thread/sleep (rand-nth [900 700]))
           (println "Timeout task was cancelled")))
