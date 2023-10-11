task :default => :test

desc "Run Clojure unit tests"
task :test do
  sh './scripts/javac with-test && lein test'
end

desc "Run Java unit tests"
task :test_java do
  sh './scripts/junit'
end

desc "Run benchmark suite"
task :bench, [:edn_opts] do |t, args|
  puts ""
  puts "Running benchmark suite via Rake"
  puts "Example call: rake bench['{:profile :quick :metadata {:author \"Your name\" :description \"Your description\"}}']"
  puts ""

  edn_opts = args[:edn_opts]
  sh "./scripts/benchmark '#{edn_opts}'"
end
