task :default => :test

desc "Run unit test"
task :test do
  sh './scripts/javac with-test && lein test'
end

desc "Run some benchmark test"
task :benchmark do
  sh './scripts/javac with-test && lein test :benchmark'
end

desc "Install in local repository"
task :install_local => :test do
  sh 'lein deps && rm -rf *.jar pom.xml classes target && lein jar && lein install'
  sh 'cd ~/workspace/rssminer && lein deps'
end

desc "Install in clojars repository"
task :clojars => :test do
  sh 'rm -rf *.jar pom.xml classes target && lein pom && lein jar'
  sh "cp target/provided/*.jar ."
  sh 'scp pom.xml *.jar clojars@clojars.org:'
end

desc "Start swank server for emacs"
task :swank do
  sh "./scripts/javac with-test && lein swank"
end

# desc "Benchmark to an idea how fast it can run"
# task :bench do
#   sh 'rm -rf classes/ && lein deps && lein javac'
#   sh './scripts/benchmark bench'
# end
