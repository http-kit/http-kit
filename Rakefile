task :default => :test

desc "Run Clojure unit tests"
task :test do
  sh './scripts/javac with-test && lein test'
end

desc "Run Java unit tests"
task :test_java do
  sh './scripts/junit'
end

desc "Run ^:benchmark tests"
task :bench do
  sh './scripts/javac with-test && lein test :benchmark'
end
