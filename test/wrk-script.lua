-- Wrk script to return structured results used by benching code
-- Ref. https://github.com/wg/wrk/blob/master/SCRIPTING

done = function(summary, latency, requests)
   io.write("\nStructured results:")

   io.write(
      string.format("%f,%f,%f,%f,%f,%f,%f,%f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
		    latency.mean, latency.stdev, latency.min,
		    latency:percentile(50),     latency:percentile(75),
		    latency:percentile(80),     latency:percentile(90),
		    latency:percentile(98),     latency:percentile(99),
		    latency:percentile(99.9),   latency:percentile(99.99),
		    latency:percentile(99.999), latency.max,
		    summary["duration"], summary["requests"], summary["bytes"],
		    summary["errors"]["connect"],
		    summary["errors"]["read"],
		    summary["errors"]["write"],
		    summary["errors"]["status"],
		    summary["errors"]["timeout"]))
end
